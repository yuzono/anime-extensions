package eu.kanade.tachiyomi.animeextension.es.doramasflix

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.vudeoextractor.VudeoExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class Doramasflix :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Doramasflix"

    override val baseUrl = "https://doramasflix.in"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val brandHost = baseUrl.toHttpUrl().host

    private val actionIds = ConcurrentHashMap(DEFAULT_ACTION_IDS)

    private val actionIdsMutex = Mutex()

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf(
            "[ENG]", "[CAST]", "[LAT]", "[SUB]", "[POR]",
            "[COR]", "[JAP]", "[MAN]", "[TAI]", "[FIL]",
            "[IND]", "[VIET]",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "Upstream", "StreamTape", "Fastream", "Filemoon",
            "StreamWish", "Okru", "Streamlare", "Uqload",
        )

        private const val DORAMA_PATH = "doramas-online"
        private const val MOVIE_PATH = "peliculas-online"
        private const val EPISODE_PATH = "episodios"

        private const val PAGE_SIZE = 24
        private const val SEARCH_LIMIT = 30
        private const val EPISODES_LIMIT = 5000

        private const val SORT_POPULARITY = "POPULARITY_DESC"
        private const val SORT_LATEST = "_ID_DESC"
        private const val SORT_EPISODES = "NUMBER_DESC"

        private const val ACTION_PAGINATION_DORAMAS = "getPaginationDoramas"
        private const val ACTION_MOVIES = "getMovies"
        private const val ACTION_SEARCH = "searchQuickAction"
        private const val ACTION_EPISODES = "getEpisodesPagination"
        private const val ACTION_EPISODE_LINKS = "getEpisodeLinks"
        private const val ACTION_MOVIE_LINKS = "getMovieLinks"

        // Next.js server action ids are generated at build time; they are re-scraped from the
        // site's JS chunks when the server answers "Server action not found".
        private val DEFAULT_ACTION_IDS = mapOf(
            ACTION_PAGINATION_DORAMAS to "c078cc0fb52d994ccfbb9ae505092c444182b32bee",
            ACTION_MOVIES to "c0c3f9f4ae9e70fc3e1934e162a9bb469d4917ee3c",
            ACTION_SEARCH to "405ed996744ddd110d953845f332ab56811b896686",
            ACTION_EPISODES to "40b6d43174dd2350c91535c115434850724f4e5054",
            ACTION_EPISODE_LINKS to "40c6078a8a671297b1458299a5b27a01081afdcb7e",
            ACTION_MOVIE_LINKS to "40d13c95d97d42603131850ca52dc09bd280b08d5a",
        )

        private val ACTION_ID_REGEX = Regex("""createServerReference\)?\("([0-9a-f]+)"[^"]*"(\w+)"\)""")
        private val CHUNK_REGEX = Regex("""/_next/static/chunks/[A-Za-z0-9_~.\-]+\.js""")
        private val FLIGHT_ROOT_REGEX = Regex("""\$@([0-9a-f]+)""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        // Server code -> token matched against by serverVideoResolver
        private val SERVERS = mapOf(
            "7286" to "doodstream",
            "958695" to "filemoon",
            "453634" to "mega",
            "3889" to "mixdrop",
            "1234" to "mp4upload",
            "1113" to "okru",
            "4721" to "primeload",
            "8309" to "streamtape",
            "38585" to "streamwish",
            "1233" to "uqload",
            "576857" to "streamhide",
            "1230" to "voe",
        )
    }

    // ============================== Server actions ==============================

    private suspend fun callAction(name: String, body: RequestBody): String {
        val actionId = actionIds.getValue(name)
        var response = client.newCall(actionRequest(actionId, body)).await()
        if (response.code == 404) {
            response.close()
            refreshActionIds(name, actionId)
            response = client.newCall(actionRequest(actionIds.getValue(name), body)).await()
        }
        return response.use {
            check(it.isSuccessful) { "HTTP ${it.code}" }
            it.body.string()
        }
    }

    private fun actionRequest(actionId: String, body: RequestBody) = POST(
        "$baseUrl/",
        headers.newBuilder().set("Next-Action", actionId).build(),
        body,
    )

    private suspend fun refreshActionIds(name: String, staleId: String) = actionIdsMutex.withLock {
        if (actionIds[name] != staleId) return@withLock

        val html = client.newCall(GET(baseUrl, headers)).awaitSuccess().bodyString()
        val chunks = CHUNK_REGEX.findAll(html).map { it.value }.distinct().toList()
        val scripts = chunks.parallelCatchingMapNotNull { path ->
            client.newCall(GET(baseUrl + path, headers)).awaitSuccess().bodyString()
        }
        scripts.forEach { script ->
            ACTION_ID_REGEX.findAll(script).forEach { match ->
                val (id, actionName) = match.destructured
                if (actionName in DEFAULT_ACTION_IDS) actionIds[actionName] = id
            }
        }
    }

    private inline fun <reified T> String.parseFlight(): T {
        val lines = lines()
        val rowId = FLIGHT_ROOT_REGEX.find(lines.first())?.groupValues?.get(1) ?: "1"
        val row = lines.firstOrNull { it.startsWith("$rowId:") }?.substring(rowId.length + 1) ?: "null"
        return row.parseAs<T>()
    }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchList(page, SORT_POPULARITY, GenreFilter.DORAMAS)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchList(page, SORT_LATEST, GenreFilter.DORAMAS)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            val type = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: GenreFilter.DORAMAS
            return fetchList(page, SORT_POPULARITY, type)
        }
        if (page > 1) return AnimesPage(emptyList(), false)

        val body = listOf(SearchRequest(query.replace("+", " "), SEARCH_LIMIT)).toJsonRequestBody()
        val result = callAction(ACTION_SEARCH, body).parseFlight<SearchDto>().data
        val animes = result.doramas.map { it.toSAnime(DORAMA_PATH) } + result.movies.map { it.toSAnime(MOVIE_PATH) }
        return AnimesPage(animes, false)
    }

    private suspend fun fetchList(page: Int, sort: String, type: String): AnimesPage {
        if (type == GenreFilter.MOVIES) {
            // getMovies only supports a limit, so fetch through this page plus one item.
            val offset = (page - 1) * PAGE_SIZE
            val request = MoviesRequest(offset + PAGE_SIZE + 1, sort, FilterRequest(), brandHost)
            val movies = callAction(ACTION_MOVIES, listOf(request).toJsonRequestBody())
                .parseFlight<List<MediaDto>>()
            return AnimesPage(
                movies.drop(offset).take(PAGE_SIZE).map { it.toSAnime(MOVIE_PATH) },
                movies.size > offset + PAGE_SIZE,
            )
        }

        val request = PaginationRequest(page, PAGE_SIZE, sort, FilterRequest(type == GenreFilter.VARIETIES), brandHost)
        val result = callAction(ACTION_PAGINATION_DORAMAS, listOf(request).toJsonRequestBody())
            .parseFlight<PaginationDto>()
        return AnimesPage(result.items.map { it.toSAnime(DORAMA_PATH) }, result.pageInfo.hasNextPage)
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().useAsJsoup()
        return SAnime.create().apply {
            title = anime.title
            url = anime.url
            thumbnail_url = anime.thumbnail_url ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("p.line-clamp-4")?.wholeText()
            genre = document.select("a[href^=/generos/]").map { it.text() }.distinct().joinToString()
            author = document.detail("Red")
            artist = document.selectFirst("a[href^=/reparto/]")?.text()
            status = if (MOVIE_PATH in anime.url) {
                SAnime.COMPLETED
            } else {
                val state = document.detail("Estado")?.lowercase().orEmpty()
                when {
                    "emisi" in state || "subiendo" in state -> SAnime.ONGOING
                    "finaliz" in state -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        }
    }

    private fun Document.detail(label: String): String? = select("dt").firstOrNull { it.text() == label }
        ?.nextElementSibling()
        ?.text()
        ?.takeIf { it.isNotEmpty() }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (MOVIE_PATH in anime.url) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "Película"
                    url = anime.url
                },
            )
        }

        val document = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().useAsJsoup()
        val series = document.extractNextJs<SeriesDto>() ?: return emptyList()
        val now = System.currentTimeMillis()
        val dateFormats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd").map {
            SimpleDateFormat(it, Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }

        return series.seasons.map { it.seasonNumber }.sortedDescending().flatMap { season ->
            val request = EpisodesRequest(series.serieId, season, 1, EPISODES_LIMIT, SORT_EPISODES, brandHost)
            callAction(ACTION_EPISODES, listOf(request).toJsonRequestBody())
                .parseFlight<EpisodesDto>()
                .items
                .map { episode ->
                    val number = episode.episodeNumber.toString().removeSuffix(".0")
                    val airDate = episode.airDate?.toLongOrNull()
                        ?: dateFormats.firstNotNullOfOrNull { it.tryParse(episode.airDate).takeIf { date -> date != 0L } }
                        ?: 0L
                    SEpisode.create().apply {
                        name = "T${episode.seasonNumber} - E$number - Capítulo $number"
                        episode_number = episode.episodeNumber
                        date_upload = airDate
                        scanlator = if (airDate > now) "Próximamente..." else null
                        url = "/$EPISODE_PATH/${episode.slug}?id=${episode.id}"
                    }
                }
        }
    }

    // ============================== Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val pageUrl = (baseUrl + episode.url).toHttpUrl()
        val links = if (pageUrl.pathSegments.firstOrNull() == MOVIE_PATH) {
            val body = listOf(MovieLinksRequest(fetchMovieId(pageUrl))).toJsonRequestBody()
            callAction(ACTION_MOVIE_LINKS, body).parseFlight<List<LinkDto>?>()
        } else {
            val episodeId = pageUrl.queryParameter("id") ?: fetchEpisodeId(pageUrl)
            val body = listOf(EpisodeLinksRequest(episodeId)).toJsonRequestBody()
            callAction(ACTION_EPISODE_LINKS, body).parseFlight<List<LinkDto>?>()
        }

        return links.orEmpty().mapNotNull { link ->
            val url = link.link.decodeEmbedLink().toHttpUrlOrNull() ?: return@mapNotNull null
            val prefix = link.lang?.getLang().orEmpty()
            val server = SERVERS[link.server]
            if (server == "mega" || server == "primeload") return@mapNotNull null
            Hoster(
                hosterUrl = url.toString(),
                hosterName = "$prefix ${server ?: url.host}",
                internalData = HosterData(prefix, server).toJsonString(),
            )
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData.parseAs<HosterData>()
        return serverVideoResolver(hoster.hosterUrl, data.prefix, data.server).sortVideos()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(language) }
                .thenByDescending { it.hosterName.contains(server, true) },
        )
    }

    @Serializable
    private class HosterData(val prefix: String, val server: String?)

    private suspend fun fetchMovieId(url: HttpUrl): String {
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        return document.extractNextJs<MoviePageDto> { it is JsonObject && it["movie"] is JsonObject }?.movie?.id
            ?: throw Exception("No se pudo obtener la película")
    }

    private suspend fun fetchEpisodeId(url: HttpUrl): String {
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        return document.extractNextJs<EpisodePageDto> { it is JsonObject && it["episode"] is JsonObject }?.episode?.id
            ?: throw Exception("No se pudo obtener el episodio")
    }

    private val languages = arrayOf(
        Pair("36", "[ENG]"),
        Pair("37", "[CAST]"),
        Pair("38", "[LAT]"),
        Pair("192", "[SUB]"),
        Pair("1327", "[POR]"),
        Pair("13109", "[COR]"),
        Pair("13110", "[JAP]"),
        Pair("13111", "[MAN]"),
        Pair("13112", "[TAI]"),
        Pair("13113", "[FIL]"),
        Pair("13114", "[IND]"),
        Pair("343422", "[VIET]"),
    )

    private fun String.getLang(): String = languages.firstOrNull { it.first == this }?.second ?: ""

    private fun String.decodeEmbedLink(): String {
        val payload = substringAfterLast('/').split('.').getOrNull(1) ?: return this
        return runCatching {
            val token = String(Base64.decode(payload, Base64.URL_SAFE)).parseAs<EmbedTokenDto>()
            String(Base64.decode(token.link, Base64.DEFAULT))
        }.getOrDefault(this)
    }

    private suspend fun serverVideoResolver(url: String, prefix: String, server: String?): List<Video> {
        val embedUrl = server ?: url.lowercase()
        return when {
            "voe" in embedUrl -> VoeExtractor(client, headers).videosFromUrl(url, " $prefix")

            "ok.ru" in embedUrl || "okru" in embedUrl -> OkruExtractor(client).videosFromUrl(url, prefix = "$prefix ")

            "filemoon" in embedUrl || "moonplayer" in embedUrl -> {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders)
            }

            "uqload" in embedUrl -> UqloadExtractor(client).videosFromUrl(url, prefix = prefix)

            "mp4upload" in embedUrl -> Mp4uploadExtractor(client).videosFromUrl(url, prefix = "$prefix ", headers = headers)

            "mixdrop" in embedUrl -> MixDropExtractor(client).videosFromUrl(url, prefix = "$prefix ")

            "doodstream" in embedUrl || "dood." in embedUrl ->
                listOfNotNull(DoodExtractor(client).videoFromUrl(url.replace("https://doodstream.com/e/", "https://dood.to/e/"), "$prefix DoodStream"))

            "streamlare" in embedUrl -> StreamlareExtractor(client).videosFromUrl(url, prefix = prefix)

            "yourupload" in embedUrl || "upload" in embedUrl -> YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ")

            "wishembed" in embedUrl || "streamwish" in embedUrl || "strwish" in embedUrl || "wish" in embedUrl -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            }

            "burstcloud" in embedUrl || "burst" in embedUrl -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ")

            "fastream" in embedUrl -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:")

            "upstream" in embedUrl -> UpstreamExtractor(client).videosFromUrl(url, prefix = "$prefix ")

            "streamtape" in embedUrl || "stp" in embedUrl || "stape" in embedUrl ->
                listOfNotNull(StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape"))

            "ahvsh" in embedUrl || "streamhide" in embedUrl ->
                VidHideExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix StreamHide:$it" })

            "filelions" in embedUrl || "lion" in embedUrl -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" })

            "vudeo" in embedUrl || "vudea" in embedUrl -> VudeoExtractor(client).videosFromUrl(url, "$prefix ")

            else -> emptyList()
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("Doramas", DORAMAS),
                Pair("Películas", MOVIES),
                Pair("Variedades", VARIETIES),
            ),
        ) {
        companion object {
            const val DORAMAS = "doramas"
            const val MOVIES = "peliculas"
            const val VARIETIES = "variedades"
        }
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Settings ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(lang) },
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
