package eu.kanade.tachiyomi.animeextension.en.anikura

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * `/api/watch/streams` lists every provider for an episode but is a catalog
 * only — a provider can appear there without having a stream.
 * `/api/watch/sources?provider=<id>` returns the actual stream, or
 * `{"stream": null}` when the provider has nothing.
 *
 * Provider ids are the first `-`-delimited segment of the stream id + `:1`
 * (`kaa-native-sub` → `kaa:1`).
 *
 * Proxy `s=` signatures expire in ~10 minutes, so [getHosterList] returns
 * videos uninitialized and [resolveVideo] re-fetches the source at playback.
 */
class Anikura :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anikura"
    override val baseUrl = "https://www.anikura.club"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val streamHeaders: Headers
        get() = headers.newBuilder()
            .add("x-anikura-player", "1")
            .add("Referer", "$baseUrl/")
            .add("Accept", "application/json")
            .build()

    private val browseHeaders: Headers
        get() = headers.newBuilder()
            .add("x-anikura-player", "1")
            .add("Referer", "$baseUrl/browse")
            .add("Accept", "application/json")
            .build()

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/api/browse/page".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, browseHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.parseAs<BrowsePageDto>()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = body.items.isNotEmpty() && currentPage < MAX_PAGES
        return AnimesPage(body.items.map { it.toSAnime(baseUrl) }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val rows = response.asJsoup().parseHomeRows()
        val row = rows.firstOrNull { it.episodes.isNotEmpty() }
            ?: return AnimesPage(emptyList(), false)
        return AnimesPage(row.episodes.map { it.toSAnime(baseUrl) }, false)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val url = query.toHttpUrlOrNull()
        if (url != null && isAnikuraHost(url.host)) {
            val segments = url.pathSegments
            if (segments.size >= 3 && segments[0] == "anime") {
                val response = client.get(url, headers)
                return AnimesPage(listOf(animeDetailsParse(response)), false)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/api/browse/page".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> when (filter.selected) {
                        1 -> addQueryParameter("sort", "top")
                        2 -> addQueryParameter("sort", "newest")
                    }

                    is Filters.StatusFilter -> filter.value?.let { addQueryParameter("status", it) }

                    else -> {}
                }
            }
        }.build()
        return GET(url, browseHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.parseAs<BrowsePageDto>()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = body.items.isNotEmpty() && currentPage < MAX_PAGES
        return AnimesPage(body.items.map { it.toSAnime(baseUrl) }, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.asJsoup().extractHeroAnime()
        return anime.toSAnime(baseUrl).apply { initialized = true }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val anime = doc.extractHeroAnime()
        val payload = doc.extractEpisodeList()
        val sAnimeUrl = "${anime.id}/${anime.slug}"
        return payload.episodes
            .sortedByDescending { it.number }
            .map { ep ->
                val key = ep.number.toString()
                val preview = payload.thumbnails[key]
                    ?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
                val summary = payload.descriptions[key]?.takeIf { it.isNotBlank() }
                ep.toSEpisode(sAnimeUrl, "sub", preview, summary)
            }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = "$baseUrl${episode.url}"

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val info = parseEpisodeUrl(episode.url) ?: return emptyList()
        val referer = "$baseUrl${episode.url}"

        val streamsUrl = "$baseUrl/api/watch/streams".toHttpUrl().newBuilder()
            .addQueryParameter("id", info.animeId)
            .addQueryParameter("ep", info.episode.toString())
            .addQueryParameter("lang", info.lang)
            .build()

        val catalog = try {
            client.get(streamsUrl, streamHeaders).parseAs<StreamsResponseDto>().streams
                .filter { it.url.isNotBlank() && it.embedUrl.isNullOrBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "streams fetch failed", e)
            return emptyList()
        }

        val streams = catalog.mapNotNull { stream ->
            val provider = providerIdOf(stream) ?: return@mapNotNull null
            val serverName = serverNameOf(stream) ?: return@mapNotNull null
            val fetched = fetchSource(info, provider, referer, initialize = false) ?: return@mapNotNull null
            ServerStream(serverName, fetched.first, fetched.second)
        }

        if (streams.isEmpty()) return emptyList()

        val ordered = if (preferredServer.isBlank()) {
            streams.sortedBy { if (it.url.contains(PREFERRED_HOST)) 0 else 1 }
        } else {
            streams.sortedByDescending { it.serverName == preferredServer }
        }

        return listOf(
            Hoster(
                hosterUrl = "anikura",
                hosterName = "Anikura",
                videoList = ordered.map { it.video },
            ),
        )
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun resolveVideo(video: Video): Video? {
        val key = try {
            video.internalData.parseAs<ResolveKey>()
        } catch (e: Exception) {
            Log.w(TAG, "resolveVideo: bad internalData", e)
            return video
        }

        val info = EpisodeUrlInfo(key.animeId, key.episode, key.lang)
        val resolved = fetchSource(info, key.provider, key.referer, initialize = true)?.second
            ?: return null
        return resolved.copy(
            videoTitle = video.videoTitle.ifBlank { resolved.videoTitle },
            internalData = video.internalData,
        )
    }

    private suspend fun fetchSource(
        info: EpisodeUrlInfo,
        provider: String,
        referer: String,
        initialize: Boolean,
    ): Pair<String, Video>? {
        val url = "$baseUrl/api/watch/sources".toHttpUrl().newBuilder()
            .addQueryParameter("id", info.animeId)
            .addQueryParameter("ep", info.episode.toString())
            .addQueryParameter("lang", info.lang)
            .addQueryParameter("provider", provider)
            .build()

        val stream = try {
            client.get(url, streamHeaders).parseAs<SourceResponseDto>().stream
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sources fetch failed for $provider", e)
            return null
        }?.takeIf { it.url.isNotBlank() } ?: return null

        val videoHeaders = headers.newBuilder()
            .add("Referer", referer)
            .add(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,video/*,*/*;q=0.8",
            )
            .build()

        val absoluteUrl = if (stream.url.startsWith("/")) "$baseUrl${stream.url}" else stream.url

        val video = Video(
            videoUrl = if (initialize) absoluteUrl else "",
            videoTitle = stream.label,
            headers = videoHeaders,
            subtitleTracks = if (initialize) stream.toSubtitleTracks(baseUrl) else emptyList(),
            initialized = initialize,
            internalData = ResolveKey(
                animeId = info.animeId,
                episode = info.episode,
                lang = info.lang,
                provider = provider,
                referer = referer,
            ).toJsonString(),
        )

        return absoluteUrl to video
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun isAnikuraHost(host: String): Boolean {
        val baseHost = baseUrl.toHttpUrl().host
        return host == baseHost || host == baseHost.removePrefix("www.")
    }

    private fun serverNameOf(stream: StreamDto): String? {
        // megaplay streams advertise a manifest with no video segments.
        if (stream.id.startsWith("megaplay")) return null
        if (stream.id.startsWith("kaa")) return "KAA"
        return stream.label.substringBefore(" ·").trim().ifBlank { null }
    }

    private fun providerIdOf(stream: StreamDto): String? = stream.id.substringBefore("-").trim().ifBlank { null }?.let { "$it:1" }

    @Serializable
    private class ResolveKey(
        val animeId: String,
        val episode: Int,
        val lang: String,
        val provider: String,
        val referer: String,
    )

    private class EpisodeUrlInfo(
        val animeId: String,
        val episode: Int,
        val lang: String,
    )

    private class ServerStream(
        val serverName: String,
        val url: String,
        val video: Video,
    )

    private fun parseEpisodeUrl(url: String): EpisodeUrlInfo? {
        val match = EPISODE_URL_REGEX.find(url) ?: return null
        return EpisodeUrlInfo(
            animeId = match.groupValues[1],
            episode = match.groupValues[3].toIntOrNull() ?: return null,
            lang = match.groupValues[4],
        )
    }

    companion object {
        private const val TAG = "Anikura"
        private const val MAX_PAGES = 50
        private const val PREFERRED_HOST = "krussdomi.com"

        private val EPISODE_URL_REGEX = Regex("""/watch/(\d+)/([^?]+)\?ep=(\d+)&lang=(sub|dub)""")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "KAA"
        private val PREF_SERVER_ENTRIES = arrayOf("Auto", "KAA")
        private val PREF_SERVER_VALUES = arrayOf("", "KAA")
    }
}

private class EpisodeListPayload(
    val episodes: List<EpisodeDto>,
    val thumbnails: Map<String, String>,
    val descriptions: Map<String, String>,
)

private fun org.jsoup.nodes.Document.parseHomeRows(): List<AnimeRowDto> {
    val rows = mutableListOf<AnimeRowDto>()

    try {
        val latest = extractNextJs<AnimeRowDto> { el ->
            val obj = el as? JsonObject ?: return@extractNextJs false
            (obj["episodes"] as? JsonArray)?.isNotEmpty() == true
        }
        if (latest != null) rows += latest
    } catch (_: Exception) {
    }

    for (title in listOf("Popular Shows", "This Season")) {
        val row = try {
            extractNextJs<AnimeRowDto> { el ->
                val obj = el as? JsonObject ?: return@extractNextJs false
                (obj["title"] as? JsonPrimitive)?.content == title
            }
        } catch (_: Exception) {
            null
        }
        if (row != null) rows += row
    }

    return rows
}

private fun org.jsoup.nodes.Document.extractHeroAnime(): AnimeCoreDto {
    val hero = extractNextJs<AnimeHeroPropsDto> { el ->
        val obj = el as? JsonObject ?: return@extractNextJs false
        val inner = obj["anime"] as? JsonObject ?: return@extractNextJs false
        inner.containsKey("id") &&
            inner.containsKey("slug") &&
            inner.containsKey("title")
    } ?: throw IllegalStateException("Could not locate anime detail payload")

    return hero.anime
}

// The same top-level shape is emitted twice — as the Suspense fallback
// (placeholder rows, no metadata) and as the resolved list. Prefer the
// richer payload via a strict predicate, but fall back to any payload
// that has episodes so generic-titled lists are not dropped.
private fun org.jsoup.nodes.Document.extractEpisodeList(): EpisodeListPayload {
    val props = findEpisodeListProps(strict = true) ?: findEpisodeListProps(strict = false)
    return EpisodeListPayload(
        episodes = props?.episodes ?: emptyList(),
        thumbnails = props?.episodeThumbnails ?: emptyMap(),
        descriptions = props?.episodeDescriptions ?: emptyMap(),
    )
}

private fun org.jsoup.nodes.Document.findEpisodeListProps(strict: Boolean): EpisodeListPropsDto? = extractNextJs<EpisodeListPropsDto> { el ->
    val obj = el as? JsonObject ?: return@extractNextJs false

    if (!obj.containsKey("hasDub") || !obj.containsKey("episodeThumbnails")) {
        return@extractNextJs false
    }

    val eps = obj["episodes"] as? JsonArray ?: return@extractNextJs false
    if (eps.isEmpty()) return@extractNextJs false

    if (!strict) return@extractNextJs true

    if (eps.size > 1) {
        val hasAuxData =
            (obj["episodeThumbnails"] as? JsonObject)?.isNotEmpty() == true ||
                (obj["episodeDescriptions"] as? JsonObject)?.isNotEmpty() == true ||
                (obj["episodeDurations"] as? JsonObject)?.isNotEmpty() == true

        val hasRealTitle = eps.any { ep ->
            val eo = ep as? JsonObject ?: return@any false
            val t = (eo["title"] as? JsonPrimitive)?.content ?: return@any false
            val n = (eo["number"] as? JsonPrimitive)?.content
            t.isNotBlank() && t != "Episode $n"
        }

        hasAuxData || hasRealTitle
    } else {
        val first = eps.first() as? JsonObject ?: return@extractNextJs false
        val title = (first["title"] as? JsonPrimitive)?.content ?: return@extractNextJs false
        val number = (first["number"] as? JsonPrimitive)?.content ?: return@extractNextJs false
        title != "Episode $number"
    }
}
