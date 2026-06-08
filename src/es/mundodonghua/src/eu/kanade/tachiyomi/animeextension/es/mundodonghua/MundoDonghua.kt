package eu.kanade.tachiyomi.animeextension.es.mundodonghua

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MundoDonghua :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MundoDonghua"

    override val baseUrl = "https://www.mundodonghua.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun popularAnimeSelector() = "div.md-card-grid > div.md-card > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/lista-donghuas/$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".md-card-title")!!.text()
        thumbnail_url = element.selectFirst("div.md-card-img img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = "nav.md-pagination > a:last-of-type"

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element).apply {
        url = url.replace("/ver/", "/donghua/").substringBeforeLast("/")
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lista-episodios/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.md-detail-poster img")?.attr("abs:src")
        anime.title = document.selectFirst("h1.md-detail-title")?.text() ?: ""
        anime.description = document.selectFirst("p.md-detail-synopsis")?.text() ?: ""
        anime.genre = document.select("div.md-genres-block a.md-genre-tag").joinToString { it.text() }
        anime.status = parseStatus(
            document.selectFirst("span.md-emision-badge")?.text().orEmpty(),
        )
        return anime
    }

    override fun episodeListSelector() = "ul.md-episode-list li.md-episode-item a.md-ep-link"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("href").split("/").last().toFloat()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = epNum
        episode.name = "Episodio $epNum"
        return episode
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e")) {
                val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                packedRegex.findAll(script.data()).map {
                    it.value
                }.toList().forEach {
                    val unpack = autoUnpacker(it) ?: return@forEach
                    if (unpack.contains("amagi_tab")) {
                        fetchUrls(unpack).forEach { url ->
                            try {
                                VoeExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("fmoon_tab")) {
                        fetchUrls(unpack).forEach { url ->
                            try {
                                val newHeaders = headers.newBuilder()
                                    .add("authority", url.toHttpUrl().host)
                                    .add("referer", "$baseUrl/")
                                    .add("Origin", "https://${url.toHttpUrl().host}")
                                    .build()
                                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = newHeaders).also(videoList::addAll)
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("vhide_tab")) {
                        fetchUrls(unpack).forEach { url ->
                            try {
                                if (url.contains("vidhide")) {
                                    val newHeaders = headers.newBuilder()
                                        .add("authority", url.toHttpUrl().host)
                                        .add("referer", "$baseUrl/")
                                        .add("Origin", baseUrl)
                                        .build()
                                    runBlocking {
                                        VidHideExtractor(client, newHeaders).videosFromUrl(url) { "VidHide:$it" }.also(videoList::addAll)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("swish_tab")) {
                        fetchUrls(unpack).forEach { url ->
                            try {
                                if (url.contains("embedwish")) {
                                    runBlocking {
                                        StreamWishExtractor(client, headers).videosFromUrl(url, "StreamWish:").also(videoList::addAll)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("asura_tab")) {
                        fetchUrls(unpack).forEach { url ->
                            try {
                                if (url.contains("redirector")) {
                                    val newHeaders = headers.newBuilder()
                                        .add("authority", "www.mdnemonicplayer.xyz")
                                        .add("accept", "*/*")
                                        .add("origin", baseUrl)
                                        .add("referer", "$baseUrl/")
                                        .build()
                                    PlaylistUtils(client, newHeaders).extractFromHls(url, videoNameGen = { "Asura:$it" }).let { videoList.addAll(it) }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "VoeCDN")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/busquedas/$query")
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<Selecionar>", ""),
                Pair("Acción", "Acción"),
                Pair("Artes Marciales", "Artes Marciales"),
                Pair("Aventura", "Aventura"),
                Pair("Ciencia Ficción", "Ciencia Ficción"),
                Pair("Comedia", "Comedia"),
                Pair("Comida", "Comida"),
                Pair("Cultivación", "Cultivación"),
                Pair("Demonios", "Demonios"),
                Pair("Deportes", "Deportes"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Escolar", "Escolar"),
                Pair("Fantasía", "Fantasía"),
                Pair("Harem", "Harem"),
                Pair("Harem Inverso", "Harem Inverso"),
                Pair("Historico", "Historico"),
                Pair("Idols", "Idols"),
                Pair("Juegos", "Juegos"),
                Pair("Lucha", "Lucha"),
                Pair("Magia", "Magia"),
                Pair("Mechas", "Mechas"),
                Pair("Militar", "Militar"),
                Pair("Misterio", "Misterio"),
                Pair("Música", "Música"),
                Pair("Por Definir", "Por Definir"),
                Pair("Psicológico", "Psicológico"),
                Pair("Reencarnación", "Reencarnación"),
                Pair("Romance", "Romance"),
                Pair("Seinen", "Seinen"),
                Pair("Shojo", "Shojo"),
                Pair("Shonen", "Shonen"),
                Pair("Sobrenatural", "Sobrenatural"),
                Pair("Sucesos de la Vida", "Sucesos de la Vida"),
                Pair("Superpoderes", "Superpoderes"),
                Pair("Suspenso", "Suspenso"),
                Pair("Terror", "Terror"),
                Pair("Vampiros", "Vampiros"),
                Pair("Viaje a Otro Mundo", "Viaje a Otro Mundo"),
                Pair("Videojuegos", "Videojuegos"),
                Pair("Zombis", "Zombis"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("En Emisión") -> SAnime.ONGOING
        statusString.contains("Finalizada") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "VoeCDN",
            "Filemoon:1080p",
            "Filemoon:720p",
            "Filemoon:480p",
            "Asura:1080p",
            "Asura:720p",
            "Asura:480p",
            "VidHide:1080p",
            "VidHide:720p",
            "VidHide:480p",
            "StreamWish:1080p",
            "StreamWish:720p",
            "StreamWish:480p",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("VoeCDN")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
