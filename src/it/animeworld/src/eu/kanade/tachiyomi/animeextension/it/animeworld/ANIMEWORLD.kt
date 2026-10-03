package eu.kanade.tachiyomi.animeextension.it.animeworld

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class ANIMEWORLD :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ANIMEWORLD.tv"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://www.animeworld.ac"

    override val lang = "it"

    override val supportsLatest = true
    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyRedirectionInterceptor(network.client))
            .build()
    }

    private val preferences by getPreferencesLazy()

    // Popular Anime - Same Format as Search

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=6&page=$page")
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup()
        .select("div.server.active ul.episodes li.episode a")
        .map(::episodeFromElement)
        .reversed()

    private fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.absUrl("href"))
        episode.name = "Episode: " + element.text()
        val epNum = getNumberFromEpsString(element.text())
        episode.episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    // Video urls

    override fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val copyrightError = document.select("div.alert.alert-primary:contains(Copyright)")
        if (copyrightError.hasText()) throw Exception(copyrightError.text())

        val episodeId = document.selectFirst("div#player[data-episode-id]")?.attr("data-episode-id")
            ?: return emptyList()

        return document.select("div.servers > div.widget-title span.server-tab").mapNotNull { server ->
            val serverName = server.attr("data-name")
            val dataId = document.selectFirst(
                "div.server[data-name=$serverName] li.episode a[data-episode-id=$episodeId]",
            )?.attr("data-id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null

            Hoster(
                hosterUrl = "$baseUrl/api/episode/info?id=$dataId&alt=0",
                hosterName = server.text(),
                internalData = document.location(),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val apiHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Content-Type", "application/json")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", hoster.internalData)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val url = client.get(hoster.hosterUrl, apiHeaders).parseAs<ServerResponse>().grabber

        return when {
            hoster.hosterName.contains("AnimeWorld Server", ignoreCase = true) -> {
                listOf(Video(videoUrl = url, videoTitle = "AnimeWorld Server"))
            }
            url.contains("https://doo") -> {
                DoodExtractor(client).videoFromUrl(url, redirect = true)?.let(::listOf).orEmpty()
            }
            url.contains("streamtape") -> {
                StreamTapeExtractor(client).videoFromUrl(url.replace("/v/", "/e/"))?.let(::listOf).orEmpty()
            }
            url.contains("streamhide") -> {
                VidHideExtractor(client, headers).videosFromUrl(url)
            }
            url.contains("vidguard") || url.contains("listeamed") -> {
                VidGuardExtractor(client).videosFromUrl(url)
            }
            else -> emptyList()
        }.sortVideos()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString("preferred_server", "Animeworld server")!!
        return sortedByDescending { it.hosterName.contains(server, ignoreCase = true) }
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "Animeworld server")!!

        return sortedWith(
            compareBy(
                { it.videoTitle.lowercase().contains(server.lowercase()) },
                { it.videoTitle.lowercase().contains(quality.lowercase()) },
            ),
        ).reversed()
    }

    // search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("div.film-list div.item div.inner a.poster").map(::searchAnimeFromElement)
        return AnimesPage(anime, document.selectFirst("div.paging-wrapper a#go-next-page") != null)
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.absUrl("href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/filter?${getSearchParameters(filters)}&keyword=$query&page=$page")

    // Details

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.thumb img")!!.attr("src")
        anime.title = document.select("div.c1 h2.title").text()
        val dl = document.select("div.info dl")
        anime.genre = dl.select("dd:has(a[href*=language]) a, dd:has(a[href*=genre]) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.desc").text()
        anime.author = dl.select("dd:has(a[href*=studio]) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(dl.select("dd:has(a[href*=status]) a").text().replace("Status: ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "In corso" -> SAnime.ONGOING
        "Finito" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // Latest - Same format as search

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updated?page=$page")
    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // Filters

    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("and", "Mode: AND"),
        Genre("3", "Arti Marziali"),
        Genre("5", "Avanguardia"),
        Genre("2", "Avventura"),
        Genre("1", "Azione"),
        Genre("47", "Bambini"),
        Genre("4", "Commedia"),
        Genre("6", "Demoni"),
        Genre("7", "Drammatico"),
        Genre("8", "Ecchi"),
        Genre("9", "Fantasy"),
        Genre("10", "Gioco"),
        Genre("11", "Harem"),
        Genre("43", "Hentai"),
        Genre("13", "Horror"),
        Genre("14", "Josei"),
        Genre("16", "Magia"),
        Genre("18", "Mecha"),
        Genre("19", "Militari"),
        Genre("21", "Mistero"),
        Genre("20", "Musicale"),
        Genre("22", "Parodia"),
        Genre("23", "Polizia"),
        Genre("24", "Psicologico"),
        Genre("46", "Romantico"),
        Genre("26", "Samurai"),
        Genre("28", "Sci-Fi"),
        Genre("27", "Scolastico"),
        Genre("29", "Seinen"),
        Genre("25", "Sentimentale"),
        Genre("30", "Shoujo"),
        Genre("31", "Shoujo Ai"),
        Genre("32", "Shounen"),
        Genre("33", "Shounen Ai"),
        Genre("34", "Slice of Life"),
        Genre("35", "Spazio"),
        Genre("37", "Soprannaturale"),
        Genre("36", "Sport"),
        Genre("12", "Storico"),
        Genre("38", "Superpoteri"),
        Genre("39", "Thriller"),
        Genre("40", "Vampiri"),
        Genre("48", "Veicoli"),
        Genre("41", "Yaoi"),
        Genre("42", "Yuri"),
    )

    internal class Season(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SeasonList(seasons: List<Season>) : AnimeFilter.Group<Season>("Stagioni", seasons)
    private fun getSeasons() = listOf(
        Season("winter", "Inverno"),
        Season("spring", "Primavera"),
        Season("summer", "Estate"),
        Season("fall", "Autunno"),
        Season("unknown", "Sconosciuto"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears() = listOf(
        Year("1966"),
        Year("1967"),
        Year("1969"),
        Year("1970"),
        Year("1973"),
        Year("1974"),
        Year("1975"),
        Year("1977"),
        Year("1978"),
        Year("1979"),
        Year("1980"),
        Year("1981"),
        Year("1982"),
        Year("1983"),
        Year("1984"),
        Year("1985"),
        Year("1986"),
        Year("1987"),
        Year("1988"),
        Year("1989"),
        Year("1990"),
        Year("1991"),
        Year("1992"),
        Year("1993"),
        Year("1994"),
        Year("1995"),
        Year("1996"),
        Year("1997"),
        Year("1998"),
        Year("1999"),
        Year("2000"),
        Year("2001"),
        Year("2002"),
        Year("2003"),
        Year("2004"),
        Year("2005"),
        Year("2006"),
        Year("2007"),
        Year("2008"),
        Year("2009"),
        Year("2010"),
        Year("2011"),
        Year("2012"),
        Year("2013"),
        Year("2014"),
        Year("2015"),
        Year("2016"),
        Year("2017"),
        Year("2018"),
        Year("2019"),
        Year("2020"),
        Year("2021"),
        Year("2022"),
        Year("2023"),
        Year("2024"),
        Year("2025"),
    )

    internal class Type(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TypeList(types: List<Type>) : AnimeFilter.Group<Type>("Tipo", types)
    private fun getTypes() = listOf(
        Type("0", "Anime"),
        Type("4", "Movie"),
        Type("1", "OVA"),
        Type("2", "ONA"),
        Type("3", "Special"),
        Type("5", "Music"),
    )

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Studio(val input: String, name: String) : AnimeFilter.Text(name)

    internal class Sub(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SubList(subs: List<Sub>) : AnimeFilter.Group<Sub>("Sottotitoli", subs)
    private fun getSubs() = listOf(
        Sub("0", "Subbato"),
        Sub("1", "Doppiato"),
    )

    internal class Audio(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class AudioList(audios: List<Audio>) : AnimeFilter.Group<Audio>("Audio", audios)
    private fun getAudios() = listOf(
        Audio("jp", "Giapponese"),
        Audio("it", "Italiano"),
        Audio("ch", "Cinese"),
        Audio("kr", "Coreano"),
        Audio("en", "Inglese"),
    )

    private class OrderFilter :
        AnimeFilter.Select<String>(
            "Ordine",
            arrayOf(
                "Standard",
                "Ultime Aggiunte",
                "Lista A-Z",
                "Lista Z-A",
                "Più Vecchi",
                "Più Recenti",
                "Più Visti",
            ),
            0,
        )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring += if (Genre.id == "and") {
                                "&genre_mode=and"
                            } else {
                                "&genre=" + Genre.id
                            }
                        }
                    }
                }

                is SeasonList -> { // ---Season
                    filter.state.forEach { Season ->
                        if (Season.state) {
                            totalstring += "&season=" + Season.id
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring += "&year=" + Year.id
                        }
                    }
                }

                is TypeList -> { // ---Type
                    filter.state.forEach { Type ->
                        if (Type.state) {
                            totalstring += "&type=" + Type.id
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { State ->
                        if (State.state) {
                            totalstring += "&status=" + State.id
                        }
                    }
                }

                is Studio -> {
                    if (filter.state.isNotEmpty()) {
                        val studios = filter.state.split(",").toTypedArray()
                        for (x in studios.indices) {
                            totalstring += "&studio=" + studios[x]
                        }
                    }
                }

                is SubList -> { // ---Subs
                    filter.state.forEach { Sub ->
                        if (Sub.state) {
                            totalstring += "&dub=" + Sub.id
                        }
                    }
                }

                is AudioList -> { // ---Audio
                    filter.state.forEach { Audio ->
                        if (Audio.state) {
                            totalstring += "&language=" + Audio.id
                        }
                    }
                }

                is OrderFilter -> {
                    if (filter.values[filter.state] == "Standard") totalstring += "&sort=0"
                    if (filter.values[filter.state] == "Ultime Aggiunte") totalstring += "&sort=1"
                    if (filter.values[filter.state] == "Lista A-Z") totalstring += "&sort=2"
                    if (filter.values[filter.state] == "Lista Z-A") totalstring += "&sort=3"
                    if (filter.values[filter.state] == "Più Vecchi") totalstring += "&sort=4"
                    if (filter.values[filter.state] == "Più Recenti") totalstring += "&sort=5"
                    if (filter.values[filter.state] == "Più Visti") totalstring += "&sort=6"
                }

                else -> {}
            }
        }
        return totalstring
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreList(getGenres()),
        SeasonList(getSeasons()),
        YearList(getYears()),
        TypeList(getTypes()),
        StateList(getStates()),
        AnimeFilter.Header("Usa la virgola per separare i diversi studio"),
        Studio("", "Studio"),
        SubList(getSubs()),
        AudioList(getAudios()),
        OrderFilter(),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Animeworld server", "StreamHide", "Doodstream", "StreamTape", "VidGuard", "Listeamed")
            entryValues = arrayOf("Animeworld server", "StreamHide", "Doodstream", "StreamTape", "VidGuard", "Listeamed")
            setDefaultValue("Animeworld server")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // Utilities

    @Serializable
    class ServerResponse(
        val grabber: String,
    )
}
