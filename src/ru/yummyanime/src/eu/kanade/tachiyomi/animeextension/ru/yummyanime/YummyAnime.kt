package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.sibnetextractor.SibnetExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class YummyAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "YummyAnime"
    override val baseUrl = "https://ru.yummyani.me"
    override val lang = "ru"
    override val supportsLatest = true

    private val apiUrl = "https://api.yani.tv"
    private val appToken = "o0nap18m_7a0od86"
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json")
        .add("X-Application", appToken)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Предпочитаемое качество / Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET("$apiUrl/anime/catalog?limit=20&offset=$offset", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<YummyResponse<YummyCatalogDto>>().response
        val animes = data?.data?.map { it.toSAnime() } ?: emptyList()
        return AnimesPage(animes, animes.size == 20)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/anime/schedule", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<YummyResponse<List<YummyAnimeDto>>>().response
        val animes = data?.map { it.toSAnime() } ?: emptyList()
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$apiUrl/search?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<YummyResponse<List<YummyAnimeDto>>>().response
        val animes = data?.map { it.toSAnime() } ?: emptyList()
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url.substringAfterLast('/')}", headers)

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/catalog/item/${anime.url.substringAfterLast('/')}"

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<YummyResponse<YummyDetailsDto>>().response ?: return SAnime.create()

        return SAnime.create().apply {
            title = data.title ?: ""
            description = data.description
            genre = data.genres?.joinToString { it.title ?: "" }
            status = when (data.status?.value?.content) {
                "0" -> SAnime.COMPLETED
                "1" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            author = data.studios?.joinToString { it.title ?: "" }
            thumbnail_url = data.poster?.huge?.fixProtocol() ?: data.poster?.big?.fixProtocol()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url.substringAfterLast('/')}?need_videos=true", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeSlug = response.request.url.pathSegments.last()
        val data = response.parseAs<YummyResponse<YummyDetailsDto>>().response ?: return emptyList()

        val videos = data.videos ?: return emptyList()

        val isMovie = data.type?.alias?.contains("movie") == true

        val episodes = videos
            .groupBy { it.number?.content ?: "1" }
            .map { (num, _) ->
                SEpisode.create().apply {
                    name = "Серия $num"
                    episode_number = num.toFloatOrNull() ?: 1f
                    url = "$animeSlug|$num"
                }
            }
            .sortedByDescending { it.episode_number }

        if (isMovie && episodes.size == 1) {
            episodes.first().name = "Фильм"
        }

        return episodes
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|", limit = 2)
        val animeSlug = parts.getOrElse(0) { "" }
        val episodeNum = parts.getOrElse(1) { "1" }
        return GET("$apiUrl/anime/$animeSlug?need_videos=true&episode=$episodeNum", headers)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode))
        .awaitSuccess()
        .use { videoListParseAsync(it) }
        .let(::applyQualityPreference)
        .let(::voicesBeforeSubtitles)

    private fun voicesBeforeSubtitles(videos: List<Video>): List<Video> = videos.sortedBy {
        if (it.quality.contains("Субтитры", ignoreCase = true) ||
            it.quality.contains("Subtitle", ignoreCase = true)
        ) {
            1
        } else {
            0
        }
    }

    private fun applyQualityPreference(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.toIntOrNull()
            ?: return videos
        val available = videos.mapNotNull { it.quality.parseQuality() }.distinct()
        if (available.isEmpty()) return videos
        val target = available.minWithOrNull(
            compareBy({ kotlin.math.abs(it - pref) }, { -it }),
        ) ?: return videos
        return videos.filter { v -> v.quality.parseQuality()?.let { it == target } ?: true }
    }

    private fun String.parseQuality(): Int? = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun videoListParseAsync(response: Response): List<Video> {
        val episodeNum = response.request.url.queryParameter("episode") ?: return emptyList()

        val data = response.parseAs<YummyResponse<YummyDetailsDto>>().response ?: return emptyList()

        val allVideos = data.videos ?: return emptyList()

        val episodeVideos = allVideos.filter { it.number?.content == episodeNum }

        return episodeVideos.parallelCatchingFlatMap { video ->
            val dubbing = video.data?.dubbing ?: "Unknown"
            val player = video.data?.player ?: ""
            val iframeUrl = video.iframeUrl?.fixProtocol() ?: return@parallelCatchingFlatMap emptyList()

            when {
                player.contains("Kodik", ignoreCase = true) -> kodikVideoLinks(iframeUrl, dubbing)
                else -> fallbackVideoLinks(iframeUrl, dubbing)
            }
        }
    }

    // ============================ Kodik Player ===============================

    private fun kodikVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val kodikHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("X-Application", appToken)
            .build()

        val page = runCatching {
            client.newCall(GET(iframeUrl, kodikHeaders)).execute().useAsJsoup()
        }.getOrNull() ?: return emptyList()

        val pageHtml = page.html()

        val rawParams = URL_PARAMS_REGEX.find(pageHtml)?.groupValues?.get(1)
            ?: URL_PARAMS_REGEX_ALT.find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val formData = runCatching {
            rawParams.parseAs<KodikFormData>()
        }.getOrNull() ?: return emptyList()

        if (formData.dSign.isEmpty()) return emptyList()

        var videoType: String? = null
        var videoId: String? = null
        var videoHash: String? = null
        for (script in page.select("script").map { it.data() }) {
            val t = TYPE_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val h = HASH_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val i = ID_REGEX.find(script)?.groupValues?.get(1) ?: continue
            videoType = t
            videoHash = h
            videoId = i
            break
        }
        if (videoType == null || videoHash == null || videoId == null) {
            videoType = videoType ?: TYPE_REGEX.find(pageHtml)?.groupValues?.get(1)
            videoHash = videoHash ?: HASH_REGEX.find(pageHtml)?.groupValues?.get(1)
            videoId = videoId ?: ID_REGEX.find(pageHtml)?.groupValues?.get(1)
        }

        val urlParts = iframeUrl.removePrefix("https://").removePrefix("http://").split('/')
        val resolvedType = videoType ?: urlParts.getOrNull(1)
        val resolvedId = videoId ?: urlParts.getOrNull(2)
        val resolvedHash = videoHash ?: urlParts.getOrNull(3)
        if (resolvedType == null || resolvedId == null || resolvedHash == null) return emptyList()

        val postBody = FormBody.Builder()
            .add("d", formData.d)
            .add("d_sign", Uri.decode(formData.dSign))
            .add("pd", formData.pd)
            .add("pd_sign", Uri.decode(formData.pdSign))
            .add("ref", Uri.decode(formData.ref))
            .add("ref_sign", Uri.decode(formData.refSign))
            .add("type", resolvedType)
            .add("id", resolvedId)
            .add("hash", resolvedHash)
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val postHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("X-Application", appToken)
            .build()

        val playerHost = iframeUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .ifEmpty { "kodikplayer.com" }

        val kodikData = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://$playerHost/ftor")
                    .post(postBody)
                    .headers(postHeaders)
                    .build(),
            ).execute().parseAs<KodikData>()
        }.getOrNull() ?: return emptyList()

        val hlsHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("X-Application", appToken)
            .build()

        val qualityMap = mapOf(
            "360" to kodikData.links.ugly,
            "480" to kodikData.links.bad,
            "720" to kodikData.links.good,
        )

        val scriptUrl = (
            page.selectFirst("script[src*=player_single]")
                ?: page.selectFirst("script[src*=player_serial]")
                ?: page.selectFirst("script[src*=player]")
            )?.attr("abs:src") ?: return emptyList()

        val jsScript = runCatching {
            client.newCall(GET(scriptUrl, kodikHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val atobMatch = ATOB_REGEX.find(jsScript) ?: return emptyList()

        var encodeScript = "("
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atobMatch.range.last until jsScript.length) {
            val char = jsScript[i]
            when (char) {
                '(', '{' -> deque.addFirst(char)
                ')', '}' -> if (deque.isNotEmpty()) deque.removeFirst()
            }
            encodeScript += char
            if (deque.isEmpty()) break
        }

        return QuickJs.create().use { qjs ->
            qualityMap.flatMap { (qualityName, links) ->
                val encodedSrc = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val base64Url = runCatching {
                    qjs.evaluate("t='$encodedSrc'; $encodeScript").toString()
                }.getOrNull() ?: return@flatMap emptyList()

                val hlsUrl = runCatching {
                    Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8)
                }.getOrNull()?.fixProtocol() ?: return@flatMap emptyList()

                buildKodikVideos(hlsUrl, qualityName, dubbing, hlsHeaders)
            }
        }
    }

    private fun buildKodikVideos(
        hlsUrl: String,
        qualityName: String,
        dubbing: String,
        hlsHeaders: Headers,
    ): List<Video> = if (hlsUrl.contains(".mpd")) {
        PlaylistUtils(client, headers).extractFromDash(
            hlsUrl,
            { res: String -> "$dubbing (${qualityName}p Kodik - $res)" },
            hlsHeaders,
            hlsHeaders,
        )
    } else {
        listOf(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
    }

    // =========================== Fallback Player =============================

    private fun fallbackVideoLinks(iframeUrl: String, dubbing: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(iframeUrl, headers)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        if (iframeUrl.contains("sibnet.ru") || body.contains("player.src")) {
            val videoId = VIDEO_ID_REGEX.find(body)?.groupValues?.get(1)
                ?: VIDEO_ID_REGEX.find(iframeUrl)?.groupValues?.get(1)

            if (!videoId.isNullOrBlank()) {
                runCatching {
                    val rn = (Math.random() * 1_0000_0000).toInt()
                    val catchUrl = "https://vst.sibnet.ru/catch?event=load&val=null&videoid=$videoId&referrer=$iframeUrl&rn=$rn"
                    client.newCall(GET(catchUrl, headers.newBuilder().set("Referer", iframeUrl).build())).execute().close()
                }
            }

            val sibVideos = runCatching { sibnetExtractor.videosFromUrl(iframeUrl, "$dubbing (Sibnet) ") }.getOrNull()
            if (!sibVideos.isNullOrEmpty()) return sibVideos
        }

        val mpd = MPD_REGEX.find(body)?.value
        if (mpd != null) {
            val videoHeaders = Headers.Builder()
                .add("Referer", iframeUrl)
                .add("Origin", iframeUrl.toOrigin())
                .add("X-Application", appToken)
                .build()

            return PlaylistUtils(client, headers).extractFromDash(
                mpd,
                { res: String -> "$dubbing (DASH $res)" },
                videoHeaders,
                videoHeaders,
            )
        }

        val stream = M3U8_REGEX.find(body)?.value ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", iframeUrl)
            .add("Origin", iframeUrl.toOrigin())
            .add("X-Application", appToken)
            .build()

        return listOf(Video(stream, "$dubbing (Unknown)", stream, headers = videoHeaders))
    }

    // ============================= Utilities ==============================

    private fun String.fixProtocol(): String = if (startsWith("//")) "https:$this" else this

    private fun String.toOrigin(): String = ORIGIN_REGEX.find(this)?.groupValues?.get(1) ?: this

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""")
        private val ATOB_REGEX = Regex("atob\\([^\"]")
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'([^']+)'""")
        private val URL_PARAMS_REGEX_ALT = Regex("""urlParams\s*=\s*"([^"]+)"""")
        private val TYPE_REGEX = Regex("""\.type\s*=\s*['"]([^'"]+)['"]""")
        private val HASH_REGEX = Regex("""\.hash\s*=\s*['"]([^'"]+)['"]""")
        private val ID_REGEX = Regex("""\.id\s*=\s*['"]?([A-Za-z0-9]+)['"]?""")
        private val VIDEO_ID_REGEX = Regex("videoid=(\\d+)")
        private val MPD_REGEX = Regex("""https?://[^"'\s\\]+\.mpd[^"'\s\\]*""")
        private val M3U8_REGEX = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
        private val ORIGIN_REGEX = Regex("^(https?://[^/]+)")
    }
}
