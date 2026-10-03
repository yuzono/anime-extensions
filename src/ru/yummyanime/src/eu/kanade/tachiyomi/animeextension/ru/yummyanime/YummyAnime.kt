package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vkextractor.VkExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val allohaExtractor by lazy { AllohaExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOHA_KEY
            title = "Парсить плеер Alloha (beta) / Parse Alloha player (beta)"
            summary = "Alloha извлекается через WebView при запуске видео (5-25 секунд). " +
                "Отключите, если озвучки Alloha не воспроизводятся."
            setDefaultValue(PREF_ALLOHA_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOHA_SUBS_KEY
            title = "Субтитры Alloha (медленный режим)"
            summary = "Извлекать Alloha сразу при построении списка видео, чтобы " +
                "прикрепить дорожки субтитров. Открытие серии станет заметно дольше. " +
                "Работает только при включённом парсинге Alloha."
            setDefaultValue(PREF_ALLOHA_SUBS_DEFAULT)
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

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

    // Lib 16 drops the episode-level video request/parse pair: videos are produced per
    // hoster in getVideoList(hoster). Only the hoster/season stubs remain.
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    private fun episodeVideosRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|", limit = 2)
        val animeSlug = parts.getOrElse(0) { "" }
        val episodeNum = parts.getOrElse(1) { "1" }
        return GET("$apiUrl/anime/$animeSlug?need_videos=true&episode=$episodeNum", headers)
    }

    /**
     * One hoster per player/dubbing of the episode, read from the same API payload the old
     * code walked: `video.data.dubbing` is the dubbing, `video.data.player` the player and
     * `video.iframe_url` the player page.
     *
     * `hosterUrl` and `internalData` both carry that url. The app may read either field and
     * round-trips it through its own storage, so a plain absolute url (no separator, no
     * control characters) is what is safe to store. The Alloha token lives on the whole series
     * (only `&episode=` differs between entries), so it does not go stale per entry.
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = episodeVideos(episode).mapNotNull { video ->
        val player = video.data?.player?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (player.contains("Alloha", ignoreCase = true) &&
            !preferences.getBoolean(PREF_ALLOHA_KEY, PREF_ALLOHA_DEFAULT)
        ) {
            return@mapNotNull null
        }
        val playerUrl = video.iframeUrl?.fixProtocol()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val dubbing = video.data?.dubbing?.takeIf { it.isNotBlank() } ?: "Озвучка"
        Hoster(
            hosterUrl = playerUrl,
            hosterName = "$dubbing (${playerShortName(player)})",
            internalData = playerUrl,
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playerUrl = hoster.hosterUrl.ifBlank { hoster.internalData }
        if (playerUrl.isBlank()) return emptyList()
        val dubbing = hoster.hosterName.substringBeforeLast(" (").ifBlank { "Озвучка" }

        // The player is recognised from the url host, so nothing has to be remembered on the
        // source between getHosterList and getVideoList.

        val videos = when (playerOf(playerUrl)) {
            // Extracted right here, not deferred to resolveVideo: this app never calls
            // resolveVideo for unresolved videos, so an entry with an empty videoUrl simply
            // shows up as "No available videos". The cost is the WebView round-trip per dubbing
            // when the list is built (5-25s), which is what the subtitle setting used to gate.
            "Alloha" -> allohaExtractor.videosFromUrl(
                playerUrl,
                "$baseUrl/",
                prefix = dubbing,
            ).map { extracted ->
                Video(
                    // The extractor's own title carries the rendition; rebuilding it here
                    // would drop the quality, and applyQualityPreference could not rank it.
                    videoUrl = extracted.videoUrl,
                    videoTitle = extracted.videoTitle,
                    headers = extracted.headers,
                    subtitleTracks = if (preferences.getBoolean(PREF_ALLOHA_SUBS_KEY, PREF_ALLOHA_SUBS_DEFAULT)) {
                        extracted.subtitleTracks
                    } else {
                        emptyList()
                    },
                )
            }
            // Aksor hands the playlist over as JSON, so this costs one plain request instead
            // of a WebView round-trip and its links are not tied to a session — they keep
            // working on the second run of a series, which Alloha's do not.
            "Aksor" -> aksorVideoLinks(playerUrl, dubbing)
            "Kodik" -> kodikVideoLinks(playerUrl, dubbing)
            "VK" -> vkVideoLinks(playerUrl, dubbing)
            else -> fallbackVideoLinks(playerUrl, dubbing)
        }
        return videos.let(::applyQualityPreference).let(::voicesBeforeSubtitles)
    }

    /** The raw video entries the API returns for one episode. */
    private suspend fun episodeVideos(episode: SEpisode): List<YummyVideoDto> {
        val episodeNum = episode.url.substringAfter('|', "1")
        val data = client.get(episodeVideosRequest(episode).url.toString(), headers)
            .parseAs<YummyResponse<YummyDetailsDto>>()
            .response
            ?: return emptyList()
        return data.videos.orEmpty().filter { it.number?.content == episodeNum }
    }

    /**
     * The Aksor player page is `https://player.aksor.tv/video/<id>`; that id is all the JSON
     * endpoint needs. Verified against the live player: `GET player.aksor.tv/api/video/<id>`
     * answers 200 with the per-quality urls, and the `.mpd` they point at is served without
     * any special header.
     */
    private suspend fun aksorVideoLinks(
        playerUrl: String,
        dubbing: String,
    ): List<Video> {
        val videoId = playerUrl.substringBefore('?').substringAfterLast('/').trim()
        if (videoId.isBlank()) return emptyList()

        // Wrapped: anything thrown here propagates out of getVideoList and takes the whole
        // hoster with it, which the app shows as "No available videos" for every dubbing.
        val response = runCatching {
            client.get("$AKSOR_API/video/$videoId", headers).parseAs<AksorResponse>()
        }.onFailure {
            return emptyList()
        }.getOrElse { return emptyList() }

        val videos = mutableListOf<Video>()
        for ((label, url) in response.qualities) {
            val streamUrl = url?.takeIf { it.isNotBlank() } ?: continue
            // Aksor answers with a path that may no longer be served — the CDN is sharded and a
            // dubbing whose file is gone comes back 403/404 only when the player opens it.
            // Ask for the first byte now, so a dead entry is dropped from the list instead of
            // being offered and failing on press.
            if (!isStreamAlive(streamUrl)) continue
            // Not extractFromDash: that helper takes the stream url from the text inside
            // <Representation>, and Aksor's manifest carries a <SegmentTemplate> instead, so
            // it handed back an empty url. The manifest is a plain DASH one that the player
            // reads natively, so it is passed through as is.
            videos += Video(
                videoUrl = streamUrl,
                videoTitle = "$dubbing (${qualityLabel(label)}p Aksor)",
                headers = headers,
            )
        }
        return videos
    }

    /** One byte of the manifest: enough to tell a served file from a dead path, cheap to fetch. */
    private suspend fun isStreamAlive(url: String): Boolean = runCatching {
        client.get(url, headers.newBuilder().add("Range", "bytes=0-0").build()).isSuccessful
    }.getOrDefault(false)

    /** "q1080" -> "1080", "q2k" -> "2160", "q4k" -> "3840". */
    private fun qualityLabel(label: String): String = when (val value = label.removePrefix("q").lowercase()) {
        "2k" -> "2160"
        "4k" -> "3840"
        else -> value.ifBlank { "auto" }
    }

    private fun playerShortName(player: String): String = when {
        player.contains("Alloha", ignoreCase = true) -> "Alloha"
        player.contains("Aksor", ignoreCase = true) -> "Aksor"
        player.contains("Kodik", ignoreCase = true) -> "Kodik"
        player.contains("VK", ignoreCase = true) -> "VK"
        else -> player.trim()
    }

    /** Which player a hoster belongs to, recognised by its host. */
    private fun playerOf(playerUrl: String): String {
        val host = playerUrl.substringAfter("://").substringBefore('/').lowercase()
        return when {
            "alloha." in host -> "Alloha"
            "aksor." in host -> "Aksor"
            "kodik" in host -> "Kodik"
            "vk" in host -> "VK"
            else -> ""
        }
    }

    private fun voicesBeforeSubtitles(videos: List<Video>): List<Video> = videos.sortedBy {
        if (it.videoTitle.contains("Субтитры", ignoreCase = true) ||
            it.videoTitle.contains("Subtitle", ignoreCase = true)
        ) {
            1
        } else {
            0
        }
    }

    // Put the preferred quality first but keep the others: filtering them out would silently
    // drop a whole dubbing whose catalogue has no rendition at the preferred quality.
    private fun applyQualityPreference(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.toIntOrNull()
            ?: return videos
        return videos.sortedWith(
            compareBy(
                { it.videoTitle.parseQuality()?.let { q -> kotlin.math.abs(q - pref) } ?: Int.MAX_VALUE },
                { -(it.videoTitle.parseQuality() ?: 0) },
            ),
        )
    }

    private fun String.parseQuality(): Int? = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Lazy resolution for Alloha videos (empty videoUrl).
     *
     * MUST NEVER THROW: the app resolves unresolved videos in a batch when the player
     * opens, and a single exception marks the whole batch as HosterState.Error — the
     * user sees "No available videos" even though Kodik links were fine. On failure
     * null is returned, so only this one entry fails if the user selects it.
     */
    private suspend fun kodikVideoLinks(
        iframeUrl: String,
        dubbing: String,
    ): List<Video> {
        val kodikHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("X-Application", appToken)
            .build()

        val page = runCatching {
            client.get(iframeUrl, kodikHeaders).useAsJsoup()
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
            client.post("https://$playerHost/ftor", postHeaders, postBody).parseAs<KodikData>()
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
            client.get(scriptUrl, kodikHeaders).bodyString()
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

    private suspend fun buildKodikVideos(
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
        listOf(
            Video(
                videoUrl = hlsUrl,
                videoTitle = "$dubbing (${qualityName}p Kodik)",
                headers = hlsHeaders,
            ),
        )
    }

    // ============================== VK Player ================================

    /**
     * The YummyAnime "Плеер VK" iframe is a thin wrapper:
     * `//ru.yummyani.me/iframeVK.html?id={oid}_{videoId}`. The id is a standard VK
     * video identifier, so it is turned into a `video_ext.php` embed URL and handed to
     * the shared [VkExtractor], which resolves the direct mp4 streams.
     */
    private suspend fun vkVideoLinks(
        iframeUrl: String,
        dubbing: String,
    ): List<Video> {
        val id = runCatching { iframeUrl.toHttpUrl().queryParameter("id") }.getOrNull() ?: return emptyList()
        val parts = id.split("_", limit = 2)
        if (parts.size != 2) return emptyList()

        val embedUrl = "https://vk.com/video_ext.php?oid=${parts[0]}&id=${parts[1]}"

        // A VK failure (missing hash429 cookie, HTTP error) must not take the whole
        // hoster down — the other player paths already degrade to an empty list.
        return runCatching { vkExtractor.videosFromUrl(embedUrl, prefix = "$dubbing (VK) ") }
            .getOrDefault(emptyList())
            .map { Video(videoUrl = it.videoUrl, videoTitle = it.videoTitle, headers = it.headers) }
    }

    // =========================== Fallback Player =============================

    private suspend fun fallbackVideoLinks(
        iframeUrl: String,
        dubbing: String,
    ): List<Video> {
        val body = runCatching {
            client.get(iframeUrl, headers).bodyString()
        }.getOrNull() ?: return emptyList()

        if (iframeUrl.contains("sibnet.ru") || body.contains("player.src")) {
            val videoId = VIDEO_ID_REGEX.find(body)?.groupValues?.get(1)
                ?: VIDEO_ID_REGEX.find(iframeUrl)?.groupValues?.get(1)

            if (!videoId.isNullOrBlank()) {
                runCatching {
                    val rn = (Math.random() * 1_0000_0000).toInt()
                    val catchUrl = "https://vst.sibnet.ru/catch?event=load&val=null&videoid=$videoId&referrer=$iframeUrl&rn=$rn"
                    client.get(catchUrl, headers.newBuilder().set("Referer", iframeUrl).build())
                }
            }

            val sibVideos = runCatching { sibnetExtractor.videosFromUrl(iframeUrl, "$dubbing (Sibnet) ") }.getOrNull()
            if (!sibVideos.isNullOrEmpty()) {
                return sibVideos
            }
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

        return listOf(
            Video(
                videoUrl = stream,
                videoTitle = "$dubbing (Unknown)",
                headers = videoHeaders,
            ),
        )
    }

    // ============================= Utilities ==============================

    private fun String.fixProtocol(): String = if (startsWith("//")) "https:$this" else this

    private fun String.toOrigin(): String = ORIGIN_REGEX.find(this)?.groupValues?.get(1) ?: this

    companion object {
        private const val AKSOR_API = "https://player.aksor.tv/api"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private const val PREF_ALLOHA_KEY = "pref_parse_alloha"
        private const val PREF_ALLOHA_DEFAULT = false
        private const val PREF_ALLOHA_SUBS_KEY = "pref_alloha_subs"
        private const val PREF_ALLOHA_SUBS_DEFAULT = false

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
