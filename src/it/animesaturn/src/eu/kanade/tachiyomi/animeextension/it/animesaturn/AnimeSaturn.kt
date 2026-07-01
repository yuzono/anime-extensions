package eu.kanade.tachiyomi.animeextension.it.animesaturn

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import kotlin.io.encoding.Base64

class AnimeSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeSaturn"

    override val lang = "it"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        val currentDomain = getString(PREF_DOMAIN, DOMAIN_DEFAULT)!!
        if (currentDomain !in DOMAIN_VALUES) {
            edit()
                .putString(PREF_DOMAIN, DOMAIN_DEFAULT)
                .apply()
        }
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN, DOMAIN_DEFAULT)

    override fun popularAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing/$page")

    private fun formatTitle(titlestring: String): String = titlestring.replace("(ITA)", "Dub ITA")

    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "nav.flex a[href*=ongoing]:last-child"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "a.ep-tile"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href").replace("episode/", "anime/").replace("watch/", "stream/"))
        val epText = element.attr("title")
        episode.episode_number = epText.substringAfter("Episodio ").toFloatOrNull() ?: 0f
        episode.name = epText
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        if (response.code != 200) {
            return emptyList()
        }

        val token = response.request.url.queryParameter("token") ?: return emptyList()
        val playlistModel = response.parseAs<PlaylistModel>()
        val videoUrl = decodeUrl(playlistModel.d, token)
        if (videoUrl.contains(".mp4")) {
            return listOf(
                Video(
                    videoUrl,
                    "Qualità predefinita",
                    videoUrl,
                ),
            )
        }

        val basePlaylist = client.newCall(GET(videoUrl)).execute().use { it.body.string() }
        val videoList = mutableListOf<Video>()

        for (quality in QUALITY_ENTRIES) {
            if (basePlaylist.contains(quality)) {
                val sourceUrl = videoUrl.replace("playlist.m3u8", "$quality/playlist_$quality.m3u8")
                videoList.add(
                    Video(
                        sourceUrl,
                        quality,
                        sourceUrl,
                    ),
                )
            }
        }

        return videoList
    }

    private fun decodeUrl(url: String, token: String): String {
        val base = Base64.Default.decode(url).decodeToString()
        val builder = StringBuilder()
        for (i in base.indices) {
            builder.append(base[i].code.xor(token[i % token.length].code).toChar())
        }
        return builder.toString()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val url = episodePage.selectFirst("iframe")!!.attr("src")
        return Request.Builder()
            .url(url.replace("?", "/playlist?"))
            .header("Referer", url)
            .build()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        return qualityList
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = formatTitle(element.selectFirst("h3")!!.text())
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "nav.flex a[href*=ongoing]:last-child"

    override fun searchAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return GET("$baseUrl/filter/$page?key=$query$parameters")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = formatTitle(document.selectFirst("h1")!!.text())
        anime.author = document.selectFirst("div a[href*=studios]")?.text()
        anime.status = parseStatus(document.selectFirst("div a[href*=states]")?.text().orEmpty())
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        val alterTitle = formatTitle(
            document.selectFirst("p.mt-1")?.text().orEmpty(),
        ).replace("(ITA)", "").trim()
        anime.description = document.selectFirst("section:has(h2) div")?.text()?.trim()
        if (!anime.title.contains(alterTitle, true) && !alterTitle.isEmpty()) anime.description = anime.description + "\n\nTitolo Alternativo: " + alterTitle
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("In corso") -> SAnime.ONGOING
        statusString.contains("Finito") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesSelector(): String = "a.group[href]:not(.flex)"

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page")

    override fun latestUpdatesNextPageSelector(): String = "nav.flex a[href*=ongoing]:last-child"

    // Filters
    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("Arti Marziali"),
        Genre("Avventura"),
        Genre("Azione"),
        Genre("Bambini"),
        Genre("Commedia"),
        Genre("Demenziale"),
        Genre("Demoni"),
        Genre("Drammatico"),
        Genre("Ecchi"),
        Genre("Fantasy"),
        Genre("Gioco"),
        Genre("Harem"),
        Genre("Hentai"),
        Genre("Horror"),
        Genre("Isekai"),
        Genre("Josei"),
        Genre("Magia"),
        Genre("Mecha"),
        Genre("Militari"),
        Genre("Mistero"),
        Genre("Musicale"),
        Genre("Parodia"),
        Genre("Polizia"),
        Genre("Psicologico"),
        Genre("Romantico"),
        Genre("Samurai"),
        Genre("Sci-Fi"),
        Genre("Scolastico"),
        Genre("Seinen"),
        Genre("Sentimentale"),
        Genre("Shoujo Ai"),
        Genre("Shoujo"),
        Genre("Shounen Ai"),
        Genre("Shounen"),
        Genre("Slice of Life"),
        Genre("Soprannaturale"),
        Genre("Spazio"),
        Genre("Sport"),
        Genre("Storico"),
        Genre("Superpoteri"),
        Genre("Thriller"),
        Genre("Vampiri"),
        Genre("Veicoli"),
        Genre("Yaoi"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears(): List<Year> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (1967..currentYear).map { Year(it.toString()) }
    }

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(langs: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", langs)
    private fun getLangs() = listOf(
        Lang("jp", "Subbato"),
        Lang("it", "Doppiato"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        LangList(getLangs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var variantgenre = 0
        var variantstate = 0
        var variantyear = 0
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            totalstring = totalstring + "&categories%5B" + variantgenre.toString() + "%5D=" + genre.id
                            variantgenre++
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { year ->
                        if (year.state) {
                            totalstring = totalstring + "&years%5B" + variantyear.toString() + "%5D=" + year.id
                            variantyear++
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { state ->
                        if (state.state) {
                            totalstring = totalstring + "&states%5B" + variantstate.toString() + "%5D=" + state.id
                            variantstate++
                        }
                    }
                }

                is LangList -> { // ---Lang
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            totalstring = totalstring + "&languages%5B0%5D=" + lang.id
                        }
                    }
                }

                else -> {}
            }
        }
        return totalstring
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Qualità preferita",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DOMAIN,
            title = "Domain in uso (riavvio dell'app richiesto)",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = DOMAIN_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()

        private const val PREF_DOMAIN = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("anisaturn.net", "animesaturn.cx", "animesaturn.cc", "animesaturn.com", "animemars.org")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val DOMAIN_DEFAULT = DOMAIN_VALUES.first()
    }

    @Serializable
    data class PlaylistModel(val d: String, val p: String, val t: String)
}
