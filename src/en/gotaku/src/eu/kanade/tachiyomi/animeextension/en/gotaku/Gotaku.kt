package eu.kanade.tachiyomi.animeextension.en.gotaku

import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.get
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.nanohttpd.protocols.http.NanoHTTPD
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

@Source
abstract class Gotaku :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val apiUrl: String get() = "$baseUrl/api/v1"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val latestTrack: String
        get() = preferences.getString(PREF_LATEST_TRACK_KEY, PREF_LATEST_TRACK_DEFAULT) ?: PREF_LATEST_TRACK_DEFAULT

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredServerType: String
        get() {
            if (!showDownloads) return "embed"
            return preferences.getString(PREF_SERVER_TYPE_KEY, PREF_SERVER_TYPE_DEFAULT) ?: PREF_SERVER_TYPE_DEFAULT
        }

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, emptySet()) ?: emptySet()

    private val showDownloads: Boolean
        get() = preferences.getBoolean(PREF_SHOW_DOWNLOADS_KEY, PREF_SHOW_DOWNLOADS_DEFAULT)

    private val hideNsfw: Boolean
        get() = preferences.getBoolean(PREF_HIDE_NSFW_KEY, PREF_HIDE_NSFW_DEFAULT)

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val playlistUtils by lazy { PlaylistUtils(network.client, headers) }

    private val detailCache by lazy { LruCache<String, DetailsDataDto>(8) }

    private val dateFormatLock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun apiHeaders(referer: String = baseUrl): Headers = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    /** Reads at most [limit] bytes; null if the body is larger (never buffers an oversized response). */
    private fun Response.readBodyBounded(limit: Int): ByteArray? = try {
        use {
            val source = it.body.source()
            source.request((limit + 1).toLong())
            if (source.buffer.size > limit) {
                Log.w("Gotaku", "Oversized body (${it.body.contentLength()}B) — refusing")
                return@use null
            }
            source.readByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun Response.readBodyBoundedString(limit: Int): String? = readBodyBounded(limit)?.let { String(it, Charsets.UTF_8) }

    private val sync by lazy {
        GotakuSync(preferences, baseUrl, headers)
    }

    private val credentials: GotakuSync.GotakuCredentials
        get() {
            ensureSync()
            return sync.readStoredCredentials() ?: GotakuSync.GotakuCredentials(
                Crypto.DEFAULT_API_VERSION,
                Crypto.DEFAULT_API_SEED,
                "api-seal|v1",
            )
        }

    @Volatile private var initialSyncStarted = false

    @Synchronized
    private fun ensureSync() {
        if (!initialSyncStarted) {
            initialSyncStarted = true
            if (sync.readStoredCredentials() == null) {
                Thread {
                    try {
                        sync.sync(force = false)
                    } catch (_: Exception) {}
                }.start()
            }
        }
    }

    /** GET + native decrypt of a ?k=N endpoint. Returns inflated JSON text or null. */
    private suspend fun decryptApiCall(
        url: String,
        referer: String = baseUrl,
        isRetry: Boolean = false,
    ): String? {
        val creds = credentials
        val version = creds.version
        val seed = creds.seed
        val info = creds.info

        val finalUrl = if (url.contains("k=")) {
            url.replace(API_VERSION_REGEX, "$1k=$version")
        } else {
            val sep = if (url.contains("?")) "&" else "?"
            "$url${sep}k=$version"
        }

        var response: Response? = null
        val responseBytes = try {
            response = client.get(finalUrl, apiHeaders(referer))
            response.readBodyBounded(MAX_API_BODY_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "decryptApiCall request failed: $finalUrl", e)
            null
        } finally {
            response?.close()
        }

        if (responseBytes != null) {
            val decrypted = Crypto.decryptInfo(responseBytes, version, seed, info)
            if (decrypted != null) {
                return decrypted
            }
            Log.w(TAG, "decryptInfo failed for $finalUrl with version=$version. Triggering WebView sync...")
            val newCreds = sync.sync(force = true)
            if (newCreds != null && !isRetry) {
                return decryptApiCall(url, referer, isRetry = true)
            }
        } else {
            Log.w(TAG, "decryptApiCall received no body for $finalUrl (network/size error)")
        }

        return null
    }

    // ========================== Popular & Latest ==========================
    private fun titlesRequest(page: Int, sort: String, track: String = ""): Request {
        val url = "$apiUrl/titles".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "28")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .apply { if (track.isNotBlank()) addQueryParameter("track", track) }
            .build()

        val referer = buildString {
            append("$baseUrl/browse?sort=$sort")
            if (track.isNotBlank()) append("&track=$track")
        }
        return GET(url, apiHeaders(referer))
    }

    override fun popularAnimeRequest(page: Int): Request = titlesRequest(page, "trending_week")

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchTitlesSkippingNsfw(page) { popularAnimeRequest(it) }

    override fun latestUpdatesRequest(page: Int): Request = titlesRequest(page, "latest", latestTrack)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchTitlesSkippingNsfw(page) { latestUpdatesRequest(it) }

    private fun parseTitles(response: Response): AnimesPage {
        if (!Filters.hasCache()) {
            Filters.fetchAndCacheFilters(client, headers, apiUrl)
        }
        val dto = response.parseAs<TitlesResponseDto>()
        val animes = dto.data
            .filter { !hideNsfw || it.isAdult != true }
            .map { it.toSAnime() }
        return AnimesPage(animes, dto.meta?.hasMore ?: false)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseTitles(response)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseTitles(response)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = mutableListOf<Pair<String, String>>()

        if (query.isNotBlank()) params += "q" to query
        filters.forEach { f ->
            when (f) {
                is Filters.SortFilter -> params += "sort" to f.getValue()
                is Filters.SelectFilter -> f.getValue()?.let { params += f.param to it }
                is Filters.TrackFilter -> f.getValue()?.let { params += "track" to it }
                is Filters.TriStateGroup -> {
                    f.state.forEach { item ->
                        when (item.state) {
                            AnimeFilter.TriState.STATE_INCLUDE -> params += "${f.param}[]" to item.value
                            AnimeFilter.TriState.STATE_EXCLUDE -> params += "${f.param}_exclude[]" to item.value
                            else -> {}
                        }
                    }
                }
                is Filters.TagModeFilter -> f.getValue()?.let { params += "tag_mode" to it }
                else -> {}
            }
        }

        val url = "$apiUrl/titles".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("limit", "28")
            addQueryParameter("page", page.toString())
        }.build()

        val referer = buildString {
            append("$baseUrl/browse")
            val spaParams = buildList {
                addAll(params.map { (k, v) -> k.removeSuffix("[]") to v })
                if (page > 1) add("page" to page.toString())
            }
            if (spaParams.isNotEmpty()) {
                spaParams.joinTo(this, prefix = "?", separator = "&") { (k, v) ->
                    "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
                }
            }
        }
        return GET(url, apiHeaders(referer))
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = fetchTitlesSkippingNsfw(page) { searchAnimeRequest(it, query, filters) }

    private suspend fun fetchTitlesSkippingNsfw(
        page: Int,
        requestFn: (Int) -> Request,
    ): AnimesPage {
        var currentPage = page
        val totalPages = if (hideNsfw) 20 else 1 // cap; or calculate from total

        while (currentPage - page < totalPages) {
            val response = client.newCall(requestFn(currentPage)).awaitSuccess()
            val dto = response.parseAs<TitlesResponseDto>()

            if (!hideNsfw) {
                return AnimesPage(dto.data.map { it.toSAnime() }, dto.meta?.hasMore ?: false)
            }

            val safeTitles = dto.data.filter { it.isAdult != true }.map { it.toSAnime() }
            val hasMore = dto.meta?.hasMore ?: false

            if (safeTitles.isNotEmpty() || !hasMore) {
                return AnimesPage(safeTitles, hasMore)
            }

            // All-NSFW page — calculate if more pages exist
            val meta = dto.meta
            val total = meta.total
            val limit = meta.limit
            if (total > 0 && currentPage * limit >= total) {
                return AnimesPage(emptyList(), false) // past the end
            }
            currentPage++
        }
        return AnimesPage(emptyList(), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseTitles(response)

    // =============================== Details ==============================
    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/title/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/titles/${anime.url}", apiHeaders("$baseUrl/title/${anime.url}"))

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<DetailsResponseDto>().data
        detailCache.put(data.title.id, data)
        return data.title.toSAnime().apply { description = buildDescription(data.title) }
    }

    private suspend fun fetchDetails(slug: String): DetailsDataDto? {
        detailCache.get(slug)?.let { return it }
        return try {
            client.get("$apiUrl/titles/$slug", apiHeaders("$baseUrl/title/$slug"))
                .parseAs<DetailsResponseDto>().data
                .also { detailCache.put(slug, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDescription(dto: TitleDetailDto): String = buildString {
        val score = dto.malScore ?: dto.ratingsAvg
        if (score != null && score > 0) {
            val stars = (score / 2.0).roundToInt().coerceIn(1, 5)
            val scoreStr = if (score % 1.0 == 0.0) score.toInt().toString() else score.toString()
            append("★".repeat(stars)).append("☆".repeat(5 - stars)).append(" $scoreStr\n\n")
        }

        dto.synopsis?.let { raw ->
            val cleaned = raw.replace(BR_REGEX, "\n").replace(HTML_TAG_REGEX, "").trim()
            if (cleaned.isNotBlank()) append(cleaned).append("\n\n")
        }

        val infoLines = mutableListOf<String>()

        dto.altNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { infoLines.add("**Alternative Titles**: ${it.joinToString(" • ")}") }

        dto.format?.takeIf { it.isNotBlank() }?.let { infoLines.add("**Format**: $it") }

        val sourceStr = dto.source?.takeIf { it.isNotBlank() }
        val countryStr = dto.country?.takeIf { it.isNotBlank() }
        if (sourceStr != null || countryStr != null) {
            infoLines.add("**Source**: " + listOfNotNull(sourceStr, countryStr).joinToString(" • "))
        }

        when {
            dto.aired != null && dto.ended != null ->
                infoLines.add("**Airing**: From ${dto.aired} to ${dto.ended}")
            dto.aired != null -> infoLines.add("**Start Date**: ${dto.aired}")
            dto.ended != null -> infoLines.add("**End Date**: ${dto.ended}")
        }

        dto.season?.takeIf { it.isNotBlank() }?.let { infoLines.add("**Season**: $it") }
        dto.durationMinutes?.takeIf { it > 0 }?.let { infoLines.add("**Duration**: ${it}m") }
        dto.ageRating?.takeIf { it.isNotBlank() }?.let { infoLines.add("**Age Rating**: $it") }

        val production = (
            dto.studios.mapNotNull { it.name.takeIf(String::isNotBlank) } +
                dto.producers.mapNotNull { it.name.takeIf(String::isNotBlank) }
            )
            .distinct().takeIf { it.isNotEmpty() }
        production?.let { infoLines.add("**Production**: ${it.joinToString(", ")}") }

        if (infoLines.isNotEmpty()) append(infoLines.joinToString("\n")).append("\n")

        if (dto.links.isNotEmpty()) {
            val trackers = dto.links.entries.mapNotNull { (key, url) ->
                url?.takeIf { it.isNotBlank() }?.let { "[${TRACKER_LABELS[key] ?: key.uppercase()}]($it)" }
            }
            if (trackers.isNotEmpty()) append("**Trackers**: ${trackers.joinToString(" • ")}\n")
        }
    }

    // ============================== Related ===============================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val data = fetchDetails(anime.url) ?: return emptyList()

        // Seasons first (same series, different season)
        val seasons = data.seasons.mapNotNull { season ->
            val id = season.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (id == anime.url) return@mapNotNull null // skip current season
            // Prefer full_name ("Cyberpunk: Edgerunners 2") over name ("Season 2")
            val name = season.fullName?.takeIf { it.isNotBlank() }
                ?: season.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SAnime.create().apply {
                url = id
                title = name
                thumbnail_url = season.posterUrl?.takeIf { it.isNotBlank() }
            }
        }

        // Relations and recommendations
        val related = (data.relations + data.recommendations).mapNotNull { rel ->
            val relId = rel.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val relName = rel.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (relId == anime.url) return@mapNotNull null

            SAnime.create().apply {
                url = relId
                title = relName
                thumbnail_url = rel.posterUrl?.takeIf { it.isNotBlank() }
            }
        }

        return (seasons + related).distinctBy { it.url }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ========================= Episodes (encrypted) =======================
    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/titles/${anime.url}/episodes?k=${credentials.version}", apiHeaders("$baseUrl/title/${anime.url}"))

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val json = decryptApiCall("$apiUrl/titles/${anime.url}/episodes?k=${credentials.version}", "$baseUrl/title/${anime.url}")
            ?: throw Exception("Failed to load or decrypt episode list. Please try refreshing or check the site in WebView.")

        val episodes = try {
            json.parseAs<GotakuEnvelope<List<EpisodeDto>>>().data
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotEmpty() }
            ?: throw Exception("No episodes found. Check if there are any in WebView.")

        return episodes.mapIndexed { index, ep ->
            SEpisode.create().apply {
                val num = ep.number ?: ep.label?.toDoubleOrNull() ?: (index + 1).toDouble()
                val numStr = if (num % 1.0 == 0.0) num.toInt().toString() else num.toString()

                episode_number = num.toFloat()
                url = "${anime.url}/${ep.id}"
                name = ep.name?.takeIf { it.isNotBlank() }?.let { "Episode $numStr - $it" }
                    ?: "Episode $numStr"

                scanlator = when {
                    ep.sub && ep.dub -> "Sub & Dub"
                    ep.sub -> "Sub"
                    ep.dub -> "Dub"
                    else -> null
                }
                fillermark = ep.isFiller
                preview_url = ep.thumbnailUrl?.takeIf { it.isNotBlank() }
                date_upload = synchronized(dateFormatLock) { dateFormat.tryParse(ep.airedAt) }
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val bits = episode.url.split("/", limit = 2)
        val slug = bits.getOrNull(0).orEmpty()
        val epId = bits.getOrNull(1).orEmpty()
        return "$baseUrl/title/$slug?ep=$epId"
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // =================== Hosters (one encrypted fetch) ====================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val bits = episode.url.split("/", limit = 2)
        val slug = bits.getOrNull(0).orEmpty()
        val epId = bits.getOrNull(1).orEmpty()
        if (slug.isEmpty() || epId.isEmpty()) return emptyList()

        val watchReferer = "$baseUrl/title/$slug?ep=$epId"

        val flags = try {
            decryptApiCall("$apiUrl/episodes/$epId?k=${credentials.version}", watchReferer)
                ?.parseAs<GotakuEnvelope<AudioFlagsDto>>()?.data
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val excluded = excludedAudioTypes
        val includeDownloads = showDownloads

        fun hoster(type: String, label: String, isDownload: Boolean = false) = Hoster(
            hosterName = if (isDownload) "$label (Download)" else label,
            internalData = "${if (isDownload) "download" else "embed"}::$slug::$epId::$type",
        )

        return buildList {
            if (flags.softSub) {
                add(hoster("soft_sub", "Soft Sub"))
                if (includeDownloads) add(hoster("soft_sub", "Soft Sub", isDownload = true))
            }
            if (flags.hardSub) {
                add(hoster("hard_sub", "Hard Sub"))
                if (includeDownloads) add(hoster("hard_sub", "Hard Sub", isDownload = true))
            }
            if (flags.dub) {
                add(hoster("dub", "Dub"))
                if (includeDownloads) add(hoster("dub", "Dub", isDownload = true))
            }
        }.filterNot { it.internalData.substringAfterLast("::") in excluded }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ============================ Hoster Sorting ==========================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredLabel = HOSTER_LABELS[preferredAudio] ?: "Soft Sub"
        val preferredServer = preferredServerType
        val preferredAud = preferredAudio
        return sortedByDescending { h ->
            val isDownload = h.internalData.startsWith("download")
            val matchesServerType = if (preferredServer == "download") isDownload else !isDownload
            val matchesAudio = h.hosterName.startsWith(preferredLabel, ignoreCase = true)

            when {
                matchesAudio && matchesServerType -> 5
                matchesAudio -> 4
                matchesServerType -> 3
                preferredAud != "dub" && h.hosterName.contains("Sub", ignoreCase = true) -> 2
                else -> 1
            }
        }
    }

    // ============================== Videos ================================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("::", limit = 4)
        if (parts.size < 4) return emptyList()
        val (kind, slug, epId, type) = parts
        val watchReferer = "$baseUrl/title/$slug?ep=$epId"

        return when (kind) {
            "embed" -> {
                val embedJson = decryptApiCall("$apiUrl/episodes/$epId/embed?type=$type&k=${credentials.version}", watchReferer)
                val embedDto = try {
                    embedJson?.parseAs<GotakuEnvelope<EmbedDto>>()?.data
                } catch (_: Exception) {
                    null
                } ?: return emptyList()
                val embedPath = embedDto.url ?: return emptyList()
                val embedUrl = if (embedPath.startsWith("https")) embedPath else "$baseUrl$embedPath"
                extractViaHttp(embedUrl, watchReferer, embedDto, hoster)
            }
            "download" -> extractDownload(epId, type, watchReferer, hoster)
            else -> emptyList()
        }
    }

    private suspend fun extractViaHttp(
        embedUrl: String,
        watchReferer: String,
        embedDto: EmbedDto,
        hoster: Hoster,
    ): List<Video> = try {
        extractViaHttpImpl(embedUrl, watchReferer, embedDto, hoster)
    } catch (e: Exception) {
        Log.w(TAG, "extractViaHttp failed: $embedUrl", e)
        emptyList()
    }

    private suspend fun extractViaHttpImpl(
        embedUrl: String,
        watchReferer: String,
        embedDto: EmbedDto,
        hoster: Hoster,
    ): List<Video> {
        // Embed page -> manifest base + stamp
        val html = client.get(embedUrl, apiHeaders(watchReferer))
            .readBodyBoundedString(MAX_HTML_BODY_BYTES) ?: return emptyList()
        val attrs = MANIFEST_ATTRS_REGEX.find(html) ?: return emptyList()
        val (base, stamp) = attrs.destructured
        if (!base.startsWith("https://")) return emptyList()

        // Handshake: the nonce salts the response key, so the same
        // object must build the request and decrypt the response.
        val handshake = Crypto.ManifestHandshake(base, stamp)
        val requestPath = handshake.requestPath() ?: return emptyList()

        val manifestHeaders = apiHeaders(embedUrl).newBuilder()
            .set("Accept", "text/plain")
            .build()
        val encBody = client.get("$base/$requestPath", manifestHeaders)
            .readBodyBoundedString(MAX_HTML_BODY_BYTES) ?: return emptyList()
        val manifest = try {
            handshake.decryptResponse(encBody)?.parseAs<ManifestDto>()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val streamUrl = manifest.source?.takeIf { it.isNotBlank() } ?: return emptyList()
        val keySeed = manifest.keySeed.orEmpty()
        val segmentBytes = manifest.obf?.segmentBytes ?: 0 // 0 => proxy passthrough

        val server = getProxyServer()
        val localManifest = server.createProxyUrl(streamUrl, keySeed, segmentBytes, isManifest = true)

        val subtitleTracks = manifest.subtitles.orEmpty().mapNotNull { s ->
            s.url?.takeIf { it.isNotBlank() }?.let {
                Track(
                    server.createSubtitleProxyUrl(it),
                    s.language ?: s.lang ?: s.label ?: s.name ?: "Unknown",
                )
            }
        }

        val timestamps = buildList {
            embedDto.skip?.let { skip ->
                skip.intro
                    ?.takeIf { it.size >= 2 && it[1] > it[0] }
                    ?.let { add(TimeStamp(it[0], it[1], name = "Intro", type = ChapterType.Opening)) }
                skip.outro
                    ?.takeIf { it.size >= 2 && it[1] > it[0] }
                    ?.let { add(TimeStamp(it[0], it[1], name = "Outro", type = ChapterType.Ending)) }
            }
        }

        val videos = playlistUtils.extractFromHls(
            playlistUrl = localManifest,
            referer = baseUrl,
            masterHeaders = headers,
            videoHeaders = headers,
            videoNameGen = { it },
            subtitleList = subtitleTracks,
        ).map { v -> if (timestamps.isNotEmpty()) v.copy(timestamps = timestamps) else v }

        val preferredLabel = HOSTER_LABELS[preferredAudio] ?: "Soft Sub"
        val isDownloadHoster = hoster.internalData.startsWith("download")
        val matchesServerType = if (preferredServerType == "download") isDownloadHoster else !isDownloadHoster
        val hosterMatches = hoster.hosterName.startsWith(preferredLabel, ignoreCase = true) && matchesServerType
        val preferredVideo = videos.firstOrNull { it.videoTitle.startsWith(preferredQuality) }
            ?: videos.firstOrNull()

        return videos
            .sortedByDescending { it === preferredVideo }
            .map { v ->
                if (v === preferredVideo && hosterMatches) v.copy(preferred = true) else v
            }
    }

    private suspend fun extractDownload(
        epId: String,
        type: String,
        watchReferer: String,
        hoster: Hoster,
    ): List<Video> = try {
        extractDownloadImpl(epId, type, watchReferer, hoster)
    } catch (e: Exception) {
        Log.w(TAG, "extractDownload failed: epId=$epId type=$type", e)
        emptyList()
    }

    private suspend fun extractDownloadImpl(
        epId: String,
        type: String,
        watchReferer: String,
        hoster: Hoster,
    ): List<Video> {
        // 1. Fetch the embed URL (same encrypted endpoint as streaming)
        val embedJson = decryptApiCall("$apiUrl/episodes/$epId/embed?type=$type&k=${credentials.version}", watchReferer)
        val embedDto = try {
            embedJson?.parseAs<GotakuEnvelope<EmbedDto>>()?.data
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val embedPath = embedDto.url ?: return emptyList()
        val embedUrl = if (embedPath.startsWith("https")) embedPath else "$baseUrl$embedPath"

        // 2. Fetch the embed page HTML to scrape data-download-url
        val embedHtml = client.get(embedUrl, apiHeaders(watchReferer))
            .readBodyBoundedString(MAX_HTML_BODY_BYTES) ?: return emptyList()
        val downloadUrl = DOWNLOAD_URL_REGEX.find(embedHtml)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("https://") } ?: return emptyList()

        // 3. Fetch the download page (follows redirect to gotakudl.top)
        val downloadResponse = client.get(downloadUrl, apiHeaders(embedUrl))
        val finalDownloadUrl = downloadResponse.request.url.toString()
        val downloadHtml = downloadResponse
            .readBodyBoundedString(MAX_HTML_BODY_BYTES) ?: return emptyList()

        // 4. Verify the source-types list includes our audio type
        val sourceTypes = SOURCE_TYPE_REGEX.findAll(downloadHtml)
            .map { it.groupValues[1].trim() }.toList()
        val typeLabels = mapOf("soft_sub" to "Soft sub", "hard_sub" to "Hard sub", "dub" to "Dub")
        val expectedLabel = typeLabels[type]
        if (expectedLabel != null && sourceTypes.isNotEmpty() && expectedLabel !in sourceTypes) {
            return emptyList() // download page doesn't offer this audio type
        }

        // 5. Scrape manifest attrs + quality options
        val dlAttrs = MANIFEST_ATTRS_REGEX.find(downloadHtml) ?: return emptyList()
        val (dlBase, dlStamp) = dlAttrs.destructured
        if (!dlBase.startsWith("https://")) return emptyList()
        val qualitiesByHeight = parseDownloadQualities(downloadHtml).associateBy { it.height }

        // 6. Handshake + manifest fetch
        val handshake = Crypto.ManifestHandshake(dlBase, dlStamp)
        val requestPath = handshake.requestPath() ?: return emptyList()
        val manifestHeaders = apiHeaders(finalDownloadUrl).newBuilder()
            .set("Accept", "text/plain")
            .build()
        val encBody = client.get("$dlBase/$requestPath", manifestHeaders)
            .readBodyBoundedString(MAX_HTML_BODY_BYTES) ?: return emptyList()
        val manifest = try {
            handshake.decryptResponse(encBody)?.parseAs<ManifestDto>()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val streamUrl = manifest.source?.takeIf { it.isNotBlank() } ?: return emptyList()
        val keySeed = manifest.keySeed.orEmpty()
        val segmentBytes = manifest.obf?.segmentBytes ?: 0

        // 7. Proxy URL for the master m3u8
        val server = getProxyServer()
        val downloadReferer = dlBase.toHttpUrl().let { "${it.scheme}://${it.host}/" }

        val localManifest = server.createProxyUrl(
            streamUrl,
            keySeed,
            segmentBytes,
            isManifest = true,
            referer = downloadReferer,
        )

        // 8. Extract HLS variants, labeling each with its file size
        val videos = playlistUtils.extractFromHls(
            playlistUrl = localManifest,
            referer = baseUrl,
            masterHeaders = headers,
            videoHeaders = headers,
            videoNameGen = { qualityLabel ->
                val height = QUALITY_REGEX.find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull()
                val quality = height?.let { qualitiesByHeight[it] }
                if (quality != null) {
                    "$qualityLabel • ${formatFileSize(quality.size)}"
                } else {
                    qualityLabel
                }
            },
            subtitleList = emptyList(),
        )

        val preferredLabel = HOSTER_LABELS[preferredAudio] ?: "Soft Sub"
        val isDownloadHoster = hoster.internalData.startsWith("download")
        val matchesServerType = if (preferredServerType == "download") isDownloadHoster else !isDownloadHoster
        val hosterMatches = hoster.hosterName.startsWith(preferredLabel, ignoreCase = true) && matchesServerType
        val preferredVideo = videos.firstOrNull { it.videoTitle.startsWith(preferredQuality) }
            ?: videos.firstOrNull()

        return videos
            .sortedByDescending { it === preferredVideo }
            .map { v ->
                if (v === preferredVideo && hosterMatches) v.copy(preferred = true) else v
            }
    }

    private data class DownloadQuality(val height: Int, val fileName: String, val size: Long)

    private fun parseDownloadQualities(html: String): List<DownloadQuality> {
        val valueRegex = Regex("""value="(\d+)"""")
        val fileNameRegex = Regex("""data-file-name="([^"]*)"""")
        val sizeRegex = Regex("""data-size="(\d+)"""")
        return QUALITY_INPUT_REGEX.findAll(html).mapNotNull { match ->
            val input = match.value
            val height = valueRegex.find(input)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val fileName = fileNameRegex.find(input)?.groupValues?.get(1).orEmpty()
            val size = sizeRegex.find(input)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            DownloadQuality(height, fileName, size)
        }.toList()
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "%.2f GB".format(mb / 1024)
            mb >= 10 -> "${mb.toInt()} MB"
            else -> "%.1f MB".format(mb)
        }
    }

    // ================================ Proxy ===============================
    @Volatile private var proxyServer: ProxyServer? = null

    @Volatile private var proxyServerDomain: String? = null

    @Synchronized
    private fun getProxyServer(): ProxyServer {
        if (proxyServer == null || !proxyServer!!.isAlive || proxyServerDomain != baseUrl) {
            proxyServer?.stop()
            proxyServer = ProxyServer(headers, baseUrl)
            proxyServerDomain = baseUrl
            proxyServer!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        return proxyServer!!
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LATEST_TRACK_KEY,
            title = "Preferred Type For Latest",
            entries = listOf("All", "Sub", "Dub"),
            entryValues = listOf("", "sub", "dub"),
            default = PREF_LATEST_TRACK_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "360p"),
            entryValues = listOf("1080", "720", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        val serverTypePref = screen.getListPreference(
            key = PREF_SERVER_TYPE_KEY,
            title = "Preferred Server Type",
            entries = listOf("Streaming (Embed)", "Download"),
            entryValues = listOf("embed", "download"),
            default = PREF_SERVER_TYPE_DEFAULT,
            summary = "%s",
            enabled = showDownloads,
        ).also(screen::addPreference)
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio Type",
            entries = listOf("Soft Sub", "Hard Sub", "Dub"),
            entryValues = listOf("soft_sub", "hard_sub", "dub"),
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
        )
        MultiSelectListPreference(screen.context).apply {
            key = PREF_AUDIO_EXCLUDE_KEY
            title = "Exclude Audio Types"
            entries = arrayOf("Soft Sub", "Hard Sub", "Dub")
            entryValues = arrayOf("soft_sub", "hard_sub", "dub")
            setDefaultValue(emptySet<String>())
            summary = "Hide hosters of the selected audio types."
        }.also(screen::addPreference)
        screen.addSwitchPreference(
            key = PREF_SHOW_DOWNLOADS_KEY,
            title = "Show Download Hosters",
            summary = "Include download links in the hoster list alongside streaming links.",
            default = PREF_SHOW_DOWNLOADS_DEFAULT,
            onChange = { _, newValue ->
                serverTypePref.setEnabled(newValue)
                true
            },
        )
        screen.addSwitchPreference(
            key = PREF_HIDE_NSFW_KEY,
            title = "Hide NSFW Entries",
            summary = "Filter out NSFW titles from browse and search results.",
            default = PREF_HIDE_NSFW_DEFAULT,
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "soft_sub"

        private const val PREF_SERVER_TYPE_KEY = "preferred_server_type"
        private const val PREF_SERVER_TYPE_DEFAULT = "embed"

        private const val PREF_LATEST_TRACK_KEY = "preferred_latest_track"
        private const val PREF_LATEST_TRACK_DEFAULT = ""

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"

        private const val PREF_SHOW_DOWNLOADS_KEY = "show_downloads"
        private const val PREF_SHOW_DOWNLOADS_DEFAULT = false

        private const val PREF_HIDE_NSFW_KEY = "hide_nsfw"
        private const val PREF_HIDE_NSFW_DEFAULT = true

        private val HOSTER_LABELS = mapOf(
            "soft_sub" to "Soft Sub",
            "hard_sub" to "Hard Sub",
            "dub" to "Dub",
        )
        private val TRACKER_LABELS = mapOf(
            "al" to "AniList", "mal" to "MyAnimeList", "kitsu" to "Kitsu",
            "anidb" to "AniDB", "ap" to "Anime-Planet", "ann" to "ANN",
            "anisearch" to "Anisearch", "simkl" to "Simkl",
            "tmdb" to "TMDB", "tvdb" to "TVDB", "imdb" to "IMDB",
        )

        private const val MAX_API_BODY_BYTES = 8 * 1024 * 1024

        private const val TAG = "Gotaku"

        private const val MAX_HTML_BODY_BYTES = 2 * 1024 * 1024

        private val API_VERSION_REGEX = Regex("""([?&])k=\d+""")

        private val SOURCE_TYPE_REGEX = Regex("""<li class="source-type">([^<]+)</li>""")
        private val DOWNLOAD_URL_REGEX = Regex("""data-download-url="([^"]+)"""")
        private val QUALITY_INPUT_REGEX = Regex("""<input\s+[^>]*name="quality"[^>]*>""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val MANIFEST_ATTRS_REGEX =
            Regex("""data-manifest-base="([^"]+)"\s+data-manifest-stamp="([^"]+)"""")
        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)
    }
}
