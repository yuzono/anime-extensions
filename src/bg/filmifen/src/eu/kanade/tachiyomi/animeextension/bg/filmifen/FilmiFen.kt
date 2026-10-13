package eu.kanade.tachiyomi.animeextension.bg.filmifen

import android.util.LruCache
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youtubeextractor.YoutubeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parallelMap
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

class FilmiFen : AnimeHttpSource() {
    override val name = "FilmiFen"
    override val baseUrl = "https://filmifen.com"
    override val lang = "bg"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3) { it.host == baseUrl.toHttpUrl().host }
        .build()

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val seriesUrls by lazy { LruCache<String, String>(128) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val youtubeExtractor by lazy { YoutubeExtractor(client, headers, country = "BG") }
    private val byseHeaders by lazy {
        headers.newBuilder()
            .set("X-Embed-Origin", baseUrl.toHttpUrl().host)
            .build()
    }

    override fun popularAnimeRequest(page: Int) = catalogueRequest(page, "news_read;desc")

    override fun popularAnimeParse(response: Response) = catalogueParse(response)

    override suspend fun getPopularAnime(page: Int) = getCatalogue(popularAnimeRequest(page))

    override fun latestUpdatesRequest(page: Int) = catalogueRequest(page, "date;desc")

    override fun latestUpdatesParse(response: Response) = catalogueParse(response)

    override suspend fun getLatestUpdates(page: Int) = getCatalogue(latestUpdatesRequest(page))

    private fun catalogueRequest(page: Int, sort: String, category: String = ""): Request {
        val filter = buildString {
            if (category.isNotEmpty()) append("o.cat=$category&")
            append("sort=$sort")
        }
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("f")
            .addPathSegment(filter)
            .addPathSegment("page")
            .addPathSegment(page.toString())
            .addPathSegment("")
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isEmpty()) {
            val category = filters.firstInstanceOrNull<CategoryFilter>()?.value.orEmpty()
            val sort = filters.firstInstanceOrNull<SortFilter>()?.value ?: "news_read;desc"
            return catalogueRequest(page, sort, category)
        }
        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("do", "search")
            .addQueryParameter("subaction", "search")
            .addQueryParameter("story", query)
            .addQueryParameter("search_start", page.toString())
            .addQueryParameter("full_search", "0")
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = catalogueParse(response)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList) = getCatalogue(searchAnimeRequest(page, query, filters))

    private fun catalogueParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("#dle-content a.item-poster").map { element ->
            SAnime.create().apply {
                url = articlePath(element.absUrl("href"))
                title = element.selectFirst(".item-poster__title")!!.text()
                thumbnail_url = element.selectFirst("img")?.imageUrl()
                fetch_type = if (element.selectFirst(".series-season__badge") != null) {
                    FetchType.Seasons
                } else {
                    FetchType.Episodes
                }
            }
        }.distinctBy { seriesKey(it.url) ?: it.url }
        val next = document.selectFirst(".pagination__pages-btn--next a")
        return AnimesPage(anime, next != null && next.attr("href").isNotEmpty())
    }

    private suspend fun getCatalogue(request: Request): AnimesPage {
        val page = client.newCall(request).awaitSuccess().use(::catalogueParse)
        // The catalog links to the latest episode. Use the first season's first episode
        // as the library identity so new episodes don't create new series entries.
        page.animes.filter { it.fetch_type == FetchType.Seasons }.parallelMap { anime ->
            val key = seriesKey(anime.url) ?: anime.url
            anime.url = seriesUrls.get(key) ?: try {
                client.get(baseUrl + anime.url, ensureSuccess = false).use { response ->
                    if (!response.isSuccessful) return@use anime.url
                    val document = response.asJsoup()
                    val firstSeason = document.select(".series-seasons a.series-season")
                        .minByOrNull { it.selectFirst(".series-season__number")?.text()?.toDoubleOrNull() ?: Double.MAX_VALUE }
                    val firstEpisode = document.select(".series-episodes .series-episode")
                        .mapNotNull { element -> element.text().toFloatOrNull()?.let { it to element } }
                        .minByOrNull { it.first }?.second
                    val firstUrl = firstSeason?.absUrl("href") ?: firstEpisode?.let {
                        if (it.hasAttr("href")) it.absUrl("href") else document.location()
                    }
                    val path = firstUrl?.let(::articlePath) ?: anime.url
                    seriesUrls.put(key, path)
                    path
                }
            } catch (_: IOException) {
                anime.url
            }
        }
        return page
    }

    override fun animeDetailsParse(response: Response): SAnime = detailsFromDocument(response.asJsoup())

    private fun detailsFromDocument(document: Document) = SAnime.create().apply {
        url = articlePath(document.location())
        title = document.movieTitle()
        thumbnail_url = document.selectFirst(".poster__img img")?.imageUrl()
        author = document.select(".js-directors a").joinToString { it.text() }
        genre = document.select(".js-genres a").joinToString { it.text() }
        description = buildString {
            document.selectFirst(".movie__original-title")?.let { appendLine(it.text()) }
            document.selectFirst(".pmovie-info__year")?.let { appendLine(it.text()) }
            document.selectFirst(".pmovie-info__duration")?.let { appendLine(it.text()) }
            appendLine()
            append(document.selectFirst("#movie-description")?.text().orEmpty())
        }
        val isSeries = document.selectFirst(".series-episodes") != null
        fetch_type = if (isSeries) FetchType.Seasons else FetchType.Episodes
        status = if (isSeries) SAnime.UNKNOWN else SAnime.COMPLETED
        initialized = true
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select(".series-seasons a.series-season").map { element ->
            detailsFromDocument(document).apply {
                url = articlePath(element.absUrl("href"))
                season_number = element.selectFirst(".series-season__number")!!.text().toDouble()
                title = "Сезон ${season_number.toInt()}"
                thumbnail_url = element.selectFirst("img")?.imageUrl()
                fetch_type = FetchType.Episodes
            }
        }
        return seasons.sortedByDescending { it.season_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val navigation = document.selectFirst(".series-episodes")
        if (navigation == null) {
            val title = document.movieTitle()
            val path = articlePath(document.location())
            val hasTrailer = !document.playerUrls()[TRAILER_KEY].isNullOrEmpty()
            return buildList {
                add(
                    SEpisode.create().apply {
                        url = path
                        name = "Филм: $title"
                        episode_number = if (hasTrailer) 2F else 1F
                    },
                )
                if (hasTrailer) {
                    add(
                        SEpisode.create().apply {
                            url = "$path#$TRAILER_KEY"
                            name = "Трейлър: $title"
                            episode_number = 1F
                        },
                    )
                }
            }
        }
        return navigation.select(".series-episode").map { element ->
            SEpisode.create().apply {
                url = articlePath(if (element.hasAttr("href")) element.absUrl("href") else document.location())
                episode_number = element.text().toFloatOrNull() ?: 0F
                name = "Епизод ${element.text()}"
            }
        }.sortedByDescending { it.episode_number }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        if (episode.url.substringAfter('#', "") != TRAILER_KEY) return super.getHosterList(episode)
        // Filmifen redirects short article URLs, which removes the trailer fragment.
        return client.newCall(hosterListRequest(episode)).awaitSuccess().use { response ->
            val trailer = response.asJsoup().playerUrls()[TRAILER_KEY]?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Трейлърът не е намерен")
            listOf(Hoster(hosterUrl = trailer, hosterName = "YouTube", internalData = TRAILER_KEY))
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val urls = document.playerUrls()
        return document.select(".tab-btn[data-player]").mapNotNull { button ->
            val key = button.attr("data-player")
            if (key !in SUPPORTED_PLAYERS) return@mapNotNull null
            val url = urls[key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Hoster(hosterUrl = url, hosterName = button.text(), internalData = key)
        }.sortedBy { it.internalData != "okr" }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = when (hoster.internalData) {
        "vdn" -> videosFromVidon(hoster.hosterUrl)
        "okr" -> okruExtractor.videosFromUrl(hoster.hosterUrl)
        "voe" -> voeExtractor.videosFromUrl(hoster.hosterUrl)
        "fmo" -> videosFromByse(hoster.hosterUrl)
        TRAILER_KEY -> youtubeExtractor.videosFromUrl(hoster.hosterUrl, preferredCodecs = listOf("AV1", "VP9", "H.264"))
        else -> throw UnsupportedOperationException("Неподдържан плеър: ${hoster.hosterName}")
    }.map { video ->
        val quality = video.videoTitle
            .removePrefix("YouTube - ")
            .removePrefix("VDN - ")
            .removePrefix("BSE - ")
            .removePrefix("Okru:")
            .removePrefix("VOE:")
        video.copy(
            videoTitle = if (hoster.internalData == TRAILER_KEY) youtubeQuality(quality) else quality,
        )
    }

    private fun youtubeQuality(quality: String): String {
        val bandwidth = quality.substringAfterLast(" ~", "")
        val details = quality.substringBeforeLast(" ~").split(" - ").filterNot {
            it.endsWith(" fps") || it.substringBefore(" + ") in listOf("AV1", "VP9", "H.264", "HEVC", "Dolby Vision")
        }.joinToString(" - ")
        return if (bandwidth.isEmpty()) details else "$details ~$bandwidth"
    }

    private fun videosFromByse(url: String): List<Video> {
        // A fresh challenge can succeed when the shared PoW solver reaches its iteration limit.
        repeat(2) {
            val videos = filemoonExtractor.videosFromUrl(url, prefix = "BSE - ", headers = byseHeaders, referer = "$baseUrl/")
            if (videos.isNotEmpty()) return videos
        }
        throw IllegalStateException("BSE: видеото не е намерено")
    }

    private suspend fun videosFromVidon(url: String): List<Video> {
        val embedUrl = url.toHttpUrl()
        val playerHeaders = headers.newBuilder().set("Referer", url).build()
        val body = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", embedUrl.pathSegments.last())
            .add("auto", "1")
            .add("referer", "$baseUrl/")
            .build()
        val document = client.post(embedUrl.resolve("/dl")!!, headers = playerHeaders, body = body).use { it.asJsoup() }
        val script = document.select("script").joinToString("\n") { it.data() }
        val unpacked = JsUnpacker.unpackAndCombine(script) ?: script
        val playlist = HLS_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: throw IllegalStateException("VDN: видеото не е намерено")
        return playlistUtils.extractFromHls(playlist, referer = url, videoNameGen = { "VDN - $it" })
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Филтрите се прилагат при празно търсене"),
        CategoryFilter(),
        SortFilter(),
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Категория", CATEGORIES.map { it.first }.toTypedArray()) {
        val value get() = CATEGORIES[state].second
    }

    private class SortFilter : AnimeFilter.Select<String>("Подреди", SORTS.map { it.first }.toTypedArray()) {
        val value get() = SORTS[state].second
    }

    private fun Element.imageUrl() = absUrl(if (hasAttr("data-src")) "data-src" else "src")

    private fun Document.movieTitle(): String = selectFirst(".movie__bg-title")?.text()?.takeIf(String::isNotEmpty)
        ?: selectFirst(".movie__original-title")?.text()?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("Заглавието не е намерено")

    private fun Document.playerUrls(): Map<String, String> {
        val script = select("script").joinToString("\n") { it.data() }
        return PLAYER_REGEX.findAll(script).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun articlePath(url: String) = "/${url.toHttpUrl().pathSegments.last()}"

    private fun seriesKey(url: String) = SERIES_REGEX.matchEntire(url.substringAfterLast('/'))?.groupValues?.get(1)

    companion object {
        private val SERIES_REGEX = Regex("""\d+-(.+)-season-\d+-episode-\d+\.html""")
        private const val TRAILER_KEY = "trailer"
        private val PLAYER_REGEX = Regex("""\b(trailer|vdn|okr|fmo|voe):"([^"]*)"""")
        private val HLS_REGEX = Regex("""file:\s*"([^"]+\.m3u8[^\"]*)"""")
        private val SUPPORTED_PLAYERS = setOf("vdn", "okr", "fmo", "voe")
        private val CATEGORIES = listOf(
            "Всички" to "", "Филми" to "130", "Сериали" to "118",
            "Екшън" to "6", "Комедия" to "9", "Драма" to "5",
            "Ужаси" to "13", "Трилъри" to "15", "Романтични" to "12",
            "Фантастика" to "14", "Криминални" to "10", "Документални" to "4",
            "Анимационни" to "16", "Исторически" to "8", "Приключенски" to "11",
            "Военни" to "3", "Азиатски" to "2", "Индийски" to "7",
            "Семейни" to "116", "Музикални" to "17", "Уестърн" to "106",
            "Спортни" to "129", "Български" to "20",
        )
        private val SORTS = listOf(
            "По гледаемост" to "news_read;desc",
            "Най-нови" to "date;desc",
            "По IMDb" to "d.imdb_rating;desc",
            "По година" to "d.godina;desc",
            "По рейтинг" to "rating;desc",
            "От А до Я" to "bg-title;asc",
        )
    }
}
