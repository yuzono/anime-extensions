package eu.kanade.tachiyomi.animeextension.es.pelisplusph

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.pelisplus.Filters
import eu.kanade.tachiyomi.multisrc.pelisplus.PelisPlus
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Pelisplusph : PelisPlus() {

    override val name = "PelisPlusPh"

    override val baseUrl = "https://www25.pelisplushd.to"

    override val id: Long = 4917265654298497443L

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")
    }

    override fun popularAnimeSelector(): String = ".Posters-link"

    override fun popularAnimeNextPageSelector(): String = "body"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.absUrl("href"))
        anime.title = element.select(".listing-content > p").text()
        anime.thumbnail_url = element.selectFirst("img")?.absUrl("src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        document.selectFirst(".card-body h1")?.text()?.let { anime.title = it }
        document.select(".card-body p").map { p ->
            if (p.text().contains("Sinopsis:")) {
                anime.description = p.nextElementSibling()?.text()
            }
            if (p.select(".content-type").text().contains("Géneros:")) {
                anime.genre = p.select(".content-type-a a").joinToString { it.text() }
            }
            if (p.select(".content-type").text().contains("Reparto:")) {
                anime.artist = p.select(".content-type ~ span").text().substringBefore(",")
            }
            if (p.select(".content-type").text().contains("Actores:")) {
                anime.artist = p.select(".content-type ~ span").text().substringBefore(",")
            }
        }
        anime.status =
            if (document.location().contains("/serie/")) SAnime.UNKNOWN else SAnime.COMPLETED

        return anime
    }

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
            jsoup.select(".tab-content a").mapIndexed { idx, ep ->
                val episode = SEpisode.create().apply {
                    episode_number = (idx + 1).toFloat()
                    name = ep.ownText()
                    setUrlWithoutDomain(ep.attr("abs:href"))
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> throw IllegalStateException("Invalid query or filters")
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(".TbVideoNv").flatMap { serverItem ->
            serverItem.select(".TbVideoNv li").map { videoItem ->
                val langItem = videoItem.attr("data-name")
                val lang = if (langItem.contains("Subtitulado")) {
                    "[SUB]"
                } else if (langItem.contains("Latino")) {
                    "[LAT]"
                } else {
                    "[CAST]"
                }

                val url = videoItem.attr("data-url")
                val name = videoItem.text()
                serverVideoResolver(url, lang, name)
            }.flatten()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por genero ignora los otros filtros"),
        GenreFilter(),
    )

    private class GenreFilter :
        Filters.UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<selecionar>", ""),
                Pair("Peliculas", "peliculas"),
                Pair("Series", "series"),
                Pair("Estrenos", "estrenos"),
                Pair("Acción", "genero/accion"),
                Pair("Artes marciales", "genero/artes-marciales"),
                Pair("Asesinos en serie", "genero/asesinos-en-serie"),
                Pair("Aventura", "genero/aventura"),
                Pair("Baile", "genero/baile"),
                Pair("Bélico", "genero/belico"),
                Pair("Biografico", "genero/biografico"),
                Pair("Catástrofe", "genero/catastrofe"),
                Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
                Pair("Cine Adolescente", "genero/cine-adolescente"),
                Pair("Cine LGBT", "genero/cine-lgbt"),
                Pair("Cine Negro", "genero/cine-negro"),
                Pair("Cine Policiaco", "genero/cine-policiaco"),
                Pair("Clásicas", "genero/clasicas"),
                Pair("Comedia", "genero/comedia"),
                Pair("Comedia Negra", "genero/comedia-negra"),
                Pair("Crimen", "genero/crimen"),
                Pair("DC Comics", "genero/dc-comics"),
                Pair("Deportes", "genero/deportes"),
                Pair("Desapariciones", "genero/desapariciones"),
                Pair("Disney", "genero/disney"),
                Pair("Documental", "genero/documental"),
                Pair("Drama", "genero/drama"),
                Pair("Familiar", "genero/familiar"),
                Pair("Fantasía", "genero/fantasia"),
                Pair("Historia", "genero/historia"),
                Pair("Horror", "genero/horror"),
                Pair("Infantil", "genero/infantil"),
                Pair("Intriga", "genero/intriga"),
                Pair("live action", "genero/live-action"),
                Pair("Marvel Comics", "genero/marvel-comics"),
                Pair("Misterio", "genero/misterio"),
                Pair("Música", "genero/musica"),
                Pair("Musical", "genero/musical"),
                Pair("Policial", "genero/policial"),
                Pair("Político", "genero/politico"),
                Pair("Psicológico", "genero/psicologico"),
                Pair("Reality Tv", "genero/reality-tv"),
                Pair("Romance", "genero/romance"),
                Pair("Secuestro", "genero/secuestro"),
                Pair("Slasher", "genero/slasher"),
                Pair("Sobrenatural", "genero/sobrenatural"),
                Pair("Stand Up", "genero/stand-up"),
                Pair("Superhéroes", "genero/superheroes"),
                Pair("Suspenso", "genero/suspenso"),
                Pair("Terror", "genero/terror"),
                Pair("Thriller", "genero/thriller"),
                Pair("Tokusatsu", "genero/tokusatsu"),
                Pair("TV Series", "genero/tv-series"),
                Pair("Western", "genero/western"),
                Pair("Zombie", "genero/zombie"),
                Pair("Acción", "genero/accion"),
                Pair("Artes marciales", "genero/artes-marciales"),
                Pair("Asesinos en serie", "genero/asesinos-en-serie"),
                Pair("Aventura", "genero/aventura"),
                Pair("Baile", "genero/baile"),
                Pair("Bélico", "genero/belico"),
                Pair("Biografico", "genero/biografico"),
                Pair("Catástrofe", "genero/catastrofe"),
                Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
                Pair("Cine Adolescente", "genero/cine-adolescente"),
                Pair("Cine LGBT", "genero/cine-lgbt"),
                Pair("Cine Negro", "genero/cine-negro"),
                Pair("Cine Policiaco", "genero/cine-policiaco"),
                Pair("Clásicas", "genero/clasicas"),
                Pair("Comedia", "genero/comedia"),
                Pair("Comedia Negra", "genero/comedia-negra"),
                Pair("Crimen", "genero/crimen"),
                Pair("DC Comics", "genero/dc-comics"),
                Pair("Deportes", "genero/deportes"),
                Pair("Desapariciones", "genero/desapariciones"),
                Pair("Disney", "genero/disney"),
                Pair("Documental", "genero/documental"),
                Pair("Drama", "genero/drama"),
                Pair("Familiar", "genero/familiar"),
                Pair("Fantasía", "genero/fantasia"),
                Pair("Historia", "genero/historia"),
                Pair("Horror", "genero/horror"),
                Pair("Infantil", "genero/infantil"),
                Pair("Intriga", "genero/intriga"),
                Pair("live action", "genero/live-action"),
                Pair("Marvel Comics", "genero/marvel-comics"),
                Pair("Misterio", "genero/misterio"),
                Pair("Música", "genero/musica"),
                Pair("Musical", "genero/musical"),
                Pair("Policial", "genero/policial"),
                Pair("Político", "genero/politico"),
                Pair("Psicológico", "genero/psicologico"),
                Pair("Reality Tv", "genero/reality-tv"),
                Pair("Romance", "genero/romance"),
                Pair("Secuestro", "genero/secuestro"),
                Pair("Slasher", "genero/slasher"),
                Pair("Sobrenatural", "genero/sobrenatural"),
                Pair("Stand Up", "genero/stand-up"),
                Pair("Superhéroes", "genero/superheroes"),
                Pair("Suspenso", "genero/suspenso"),
                Pair("Terror", "genero/terror"),
                Pair("Thriller", "genero/thriller"),
                Pair("Tokusatsu", "genero/tokusatsu"),
                Pair("TV Series", "genero/tv-series"),
                Pair("Western", "genero/western"),
                Pair("Zombie", "genero/zombie"),
            ),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
