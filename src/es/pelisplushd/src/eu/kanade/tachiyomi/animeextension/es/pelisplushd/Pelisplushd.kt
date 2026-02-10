package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.pelisplus.Filters
import eu.kanade.tachiyomi.multisrc.pelisplus.PelisPlus
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Pelisplushd : PelisPlus() {

    override val name = "PelisPlusHD"

    override val baseUrl = "https://pelisplushd.bz"

    override val id: Long = 1400819034564144238L

    companion object {
        private val REGEX_VIDEO_OPTS = "'(https?://[^']*)'".toRegex()
    }

    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        title = element.select("a div.listing-content p").text()
        thumbnail_url = element.select("a img").attr("src").replace("/w154/", "/w200/")
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(response.request.url.toString())
            }
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                val episode = SEpisode.create().apply {
                    episode_number = (index + 1).toFloat()
                    name = element.text()
                    setUrlWithoutDomain(element.attr("abs:href"))
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val data = document.selectFirst("script:containsData(video[1] = )")?.data() ?: return emptyList()

        REGEX_VIDEO_OPTS.findAll(data).map { it.groupValues[1] }
            .filter { it.contains("embed69.org") }
            .forEach { opt ->
                val apiResponse = client.newCall(GET(opt)).execute()
                if (apiResponse.isSuccessful) {
                    val docResponse = apiResponse.asJsoup()
                    val cryptoScript = docResponse.selectFirst("script:containsData(let dataLink)")?.data()
                    if (!cryptoScript.isNullOrBlank()) {
                        val jsLinksMatch = cryptoScript.substringAfter("let dataLink =").substringBefore("];") + "]"
                        json.decodeFromString<List<DataLinkDto>>(jsLinksMatch).flatMap { data ->
                            val sortEmbeds = data.sortedEmbeds
                            val links = sortEmbeds.mapNotNull { it?.link }

                            val postBody = buildJsonObject {
                                putJsonArray("links") {
                                    links.forEach { add(it) }
                                }
                            }
                            val payload = postBody.toRequestBody()

                            val decryptedLinks = runCatching {
                                client.newCall(POST("https://embed69.org/api/decrypt", body = payload))
                                    .execute()
                                    .parseAs<Embed69Dto>().links
                            }.getOrNull() ?: emptyList()

                            decryptedLinks.mapNotNull {
                                val link = it.link
                                if (link.isEmpty()) return@mapNotNull null
                                val server = sortEmbeds.getOrNull(it.index)?.servername ?: "Embed69"
                                val lng = data.videoLanguage ?: ""
                                (server to lng) to link
                            }
                        }.flatMap {
                            serverVideoResolver(it.third, it.second, it.first)
                        }.also(videoList::addAll)
                    } else {
                        docResponse.select("li[onclick]")
                            .flatMap { fetchUrls(it.attr("onclick")) }
                            .forEach { realUrl ->
                                serverVideoResolver(realUrl).also(videoList::addAll)
                            }
                    }
                }
            }
        return videoList
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val tagFilter = filters.find { it is Tags } as Tags

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state.isNotBlank() -> GET("$baseUrl/year/${tagFilter.state}?page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.m-b-5")!!.text()
        thumbnail_url = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")!!
            .attr("src").replace("/w154/", "/w500/")
        description = document.selectFirst("div.col-sm-4 div.text-large")!!.ownText()
        genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString { it.text() }
        status = SAnime.COMPLETED
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año"),
    )

    private class GenreFilter :
        Filters.UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<selecionar>", ""),
                Pair("Peliculas", "peliculas"),
                Pair("Series", "series"),
                Pair("Doramas", "generos/dorama"),
                Pair("Animes", "animes"),
                Pair("Acción", "generos/accion"),
                Pair("Animación", "generos/animacion"),
                Pair("Aventura", "generos/aventura"),
                Pair("Ciencia Ficción", "generos/ciencia-ficcion"),
                Pair("Comedia", "generos/comedia"),
                Pair("Crimen", "generos/crimen"),
                Pair("Documental", "generos/documental"),
                Pair("Drama", "generos/drama"),
                Pair("Fantasía", "generos/fantasia"),
                Pair("Foreign", "generos/foreign"),
                Pair("Guerra", "generos/guerra"),
                Pair("Historia", "generos/historia"),
                Pair("Misterio", "generos/misterio"),
                Pair("Pelicula de Televisión", "generos/pelicula-de-la-television"),
                Pair("Romance", "generos/romance"),
                Pair("Suspense", "generos/suspense"),
                Pair("Terror", "generos/terror"),
                Pair("Western", "generos/western"),
            ),
        )

    private class Tags(name: String) : AnimeFilter.Text(name)

    infix fun <A, B> Pair<A, B>.to(c: String): Triple<A, B, String> = Triple(this.first, this.second, c)

    @Serializable
    data class DataLinkDto(
        @SerialName("video_language")
        val videoLanguage: String? = null,
        @SerialName("sortedEmbeds")
        val sortedEmbeds: List<SortedEmbedsDto?> = emptyList(),
    )

    @Serializable
    data class SortedEmbedsDto(
        val link: String? = null,
        val type: String? = null,
        val servername: String? = null,
    )

    @Serializable
    data class Embed69Dto(
        val success: Boolean,
        val links: List<Embed69Links>,
    )

    @Serializable
    data class Embed69Links(
        val index: Int,
        val link: String,
    )
}
