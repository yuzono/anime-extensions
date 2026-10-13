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
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.get
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

class Gotaku :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Gotaku"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl: String get() = "$baseUrl/api/v1"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val latestTrack: String
        get() = preferences.getString(PREF_LATEST_TRACK_KEY, PREF_LATEST_TRACK_DEFAULT) ?: PREF_LATEST_TRACK_DEFAULT

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, emptySet()) ?: emptySet()

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

    /** GET + native decrypt of a ?k=1 endpoint. Returns inflated JSON text or null. */
    private suspend fun decryptApiCall(url: String, referer: String = baseUrl): String? = try {
        client.get(url, apiHeaders(referer)).readBodyBounded(MAX_API_BODY_BYTES)
            ?.let(Crypto::decryptInfo)
            ?: run {
                Log.w(TAG, "decryptInfo failed url=$url")
                null
            }
    } catch (e: Exception) {
        Log.w(TAG, "decryptApiCall threw: $url", e)
        null
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

    override fun latestUpdatesRequest(page: Int): Request = titlesRequest(page, "latest", latestTrack)

    private fun parseTitles(response: Response): AnimesPage {
        val dto = response.parseAs<TitlesResponseDto>()
        return AnimesPage(dto.data.map { it.toSAnime() }, dto.meta?.hasMore ?: false)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseTitles(response)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseTitles(response)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = Filters.getFilterList(client, headers, apiUrl)

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
                .use { it.parseAs<DetailsResponseDto>().data }
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

        return (data.relations + data.recommendations).mapNotNull { rel ->
            val relId = rel.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val relName = rel.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (relId == anime.url) return@mapNotNull null

            SAnime.create().apply {
                url = relId
                title = relName
                thumbnail_url = rel.posterUrl?.takeIf { it.isNotBlank() }
            }
        }.distinctBy { it.url }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ========================= Episodes (encrypted) =======================
    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/titles/${anime.url}/episodes?k=1", apiHeaders("$baseUrl/title/${anime.url}"))

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val json = try {
            client.newCall(episodeListRequest(anime)).awaitSuccess()
                .readBodyBounded(MAX_API_BODY_BYTES)
                ?.let(Crypto::decryptInfo)
        } catch (_: Exception) {
            null
        } ?: throw Exception("Failed to load or decrypt episode list. Try again or check the site in WebView.")

        val episodes = json.parseAs<GotakuEnvelope<List<EpisodeDto>>>().data
            ?.takeIf { it.isNotEmpty() }
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

        val flags = decryptApiCall("$apiUrl/episodes/$epId?k=1", watchReferer)
            ?.parseAs<GotakuEnvelope<AudioFlagsDto>>()?.data
            ?: return emptyList()

        val excluded = excludedAudioTypes

        fun hoster(type: String, label: String) = Hoster(
            hosterName = label,
            internalData = "embed::$slug::$epId::$type",
        )

        return buildList {
            if (flags.softSub) add(hoster("soft_sub", "Soft Sub"))
            if (flags.hardSub) add(hoster("hard_sub", "Hard Sub"))
            if (flags.dub) add(hoster("dub", "Dub"))
        }.filterNot { it.internalData.substringAfterLast("::") in excluded }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ============================ Hoster Sorting ==========================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferredLabel = HOSTER_LABELS[preferredAudio] ?: "Soft Sub"
        return sortedByDescending { h ->
            when {
                h.hosterName.equals(preferredLabel, ignoreCase = true) -> 3
                preferredAudio != "dub" && h.hosterName.contains("Sub", ignoreCase = true) -> 2
                else -> 1
            }
        }
    }

    // ============================== Videos ================================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("::")
        if (parts.size < 4) return emptyList()
        val (_, slug, epId, type) = parts
        val watchReferer = "$baseUrl/title/$slug?ep=$epId"

        val embedJson = decryptApiCall("$apiUrl/episodes/$epId/embed?type=$type&k=1", watchReferer)
        val embedDto = try {
            embedJson?.parseAs<GotakuEnvelope<EmbedDto>>()?.data
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val embedPath = embedDto.url ?: return emptyList()
        val embedUrl = if (embedPath.startsWith("https")) embedPath else "$baseUrl$embedPath"

        return extractViaHttp(embedUrl, watchReferer, embedDto, hoster)
    }

    private suspend fun extractViaHttp(
        embedUrl: String,
        watchReferer: String,
        embedDto: EmbedDto,
        hoster: Hoster,
    ): List<Video> {
        // Embed page -> manifest base + stamp
        val html = client.get(embedUrl, apiHeaders(watchReferer)).body.string()
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
        val encBody = client.get("$base/$requestPath", manifestHeaders).body.string()
        val manifest = handshake.decryptResponse(encBody)?.parseAs<ManifestDto>() ?: return emptyList()

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

        val hosterMatches = hoster.hosterName.equals(
            HOSTER_LABELS[preferredAudio] ?: "Soft Sub",
            ignoreCase = true,
        )
        val preferredVideo = videos.firstOrNull { it.videoTitle.startsWith("${preferredQuality}p") }
            ?: videos.firstOrNull()

        return videos
            .sortedByDescending { it === preferredVideo }
            .map { v ->
                if (v === preferredVideo && hosterMatches) v.copy(preferred = true) else v
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
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )
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
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("gotaku.to")
        private val PREF_DOMAIN_VALUES = listOf("https://gotaku.to")
        private const val PREF_DOMAIN_DEFAULT = "https://gotaku.to"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "soft_sub"

        private const val PREF_LATEST_TRACK_KEY = "preferred_latest_track"
        private const val PREF_LATEST_TRACK_DEFAULT = ""

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"

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

        private val MANIFEST_ATTRS_REGEX =
            Regex("""data-manifest-base="([^"]+)"\s+data-manifest-stamp="([^"]+)"""")
        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em)>""", RegexOption.IGNORE_CASE)
    }
}
