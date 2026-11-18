@file:OptIn(AnimiruOnly::class, AnikkuOnly::class)
@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")

package keiyoushi.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.TileInfo
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.annotation.AnikkuOnly
import keiyoushi.annotation.AnimiruOnly
import keiyoushi.network.get
import keiyoushi.source.model.Related
import keiyoushi.source.model.SAnimeSeasonUpdate
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.source.model.VideoThumbnails
import keiyoushi.source.stream.ProxyUrl
import keiyoushi.source.stream.ProxyUrlInterceptor
import keiyoushi.source.stream.SelfProxy
import keiyoushi.source.stream.StreamDispatchInterceptor
import keiyoushi.source.stream.StreamInterceptor
import keiyoushi.source.stream.StreamSources
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate as AppSAnimeSeasonUpdate

/**
 * Base class for sources that run on both Anikku and Animiru. Implement the `fetch*` methods;
 * the request/parse helpers are final.
 */
abstract class AniSource : AniSourceBase() {

    protected open fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this

    /** Videos with a url one of these handles are played through a local proxy. */
    protected open val streamInterceptors: List<StreamInterceptor> = emptyList()

    final override val client: OkHttpClient by lazy {
        // Not in init: the generated id isn't set yet
        if (streamInterceptors.isNotEmpty()) StreamSources.register(this)
        network.client.newBuilder()
            .configureClient()
            .apply {
                interceptors().add(0, ProxyUrlInterceptor())
                if (streamInterceptors.isNotEmpty()) addInterceptor(StreamDispatchInterceptor(streamInterceptors))
            }
            .build()
    }

    internal fun handlesStream(url: HttpUrl): Boolean = streamInterceptors.any { it.handles(url) }

    abstract override suspend fun getPopularAnime(page: Int): AnimesPage

    override suspend fun getLatestUpdates(page: Int): AnimesPage = throw UnsupportedOperationException()

    abstract override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage

    /** Only fetch what is requested: Anikku asks for details and episodes separately. */
    protected abstract suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate

    /** [fetchAnimeUpdate] for anime with [FetchType.Seasons]. */
    protected open suspend fun fetchAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = throw UnsupportedOperationException()

    final override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val updated = if (anime.fetch_type == FetchType.Seasons) {
            fetchAnimeSeasonUpdate(anime, emptyList(), fetchDetails = true, fetchSeasons = false).anime
        } else {
            fetchAnimeUpdate(anime, emptyList(), fetchDetails = true, fetchEpisodes = false).anime
        }
        return updated.apply { initialized = true }
    }

    final override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = fetchAnimeUpdate(anime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes

    final override suspend fun getSeasonList(anime: SAnime): List<SAnime> = fetchAnimeSeasonUpdate(anime, emptyList(), fetchDetails = false, fetchSeasons = true).seasons

    final override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val update = fetchAnimeUpdate(anime, episodes, fetchDetails, fetchEpisodes)
        if (fetchDetails) update.anime.initialized = true
        return SAnimeEpisodeUpdate(update.anime, update.episodes)
    }

    final override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): AppSAnimeSeasonUpdate {
        val update = fetchAnimeSeasonUpdate(anime, seasons, fetchDetails, fetchSeasons)
        if (fetchDetails) update.anime.initialized = true
        return AppSAnimeSeasonUpdate(update.anime, update.seasons)
    }

    override fun getAnimeUrl(anime: SAnime): String = absolute(anime.url)

    override fun getEpisodeUrl(episode: SEpisode): String = absolute(episode.url)

    private fun absolute(url: String) = if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl + url

    protected open val hasRelatedAnime: Boolean = false

    /** Lets Anikku also search the source for the title's keywords. */
    protected open val relatedAnimeBySearch: Boolean = false

    protected open suspend fun fetchRelatedAnime(anime: SAnime): List<Related> = emptyList()

    final override val supportsRelatedAnime: Boolean get() = hasRelatedAnime

    final override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = fetchRelatedAnime(anime).map { AnimeRelation(it.name, it.animes) }

    final override val supportsRelatedAnimes: Boolean get() = hasRelatedAnime

    final override val disableRelatedAnimesBySearch: Boolean get() = !relatedAnimeBySearch

    final override val disableRelatedAnimes: Boolean get() = false

    final override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = fetchRelatedAnime(anime).flatMap { it.animes }

    protected abstract suspend fun fetchHosterList(episode: SEpisode): List<Hoster>

    protected open suspend fun fetchVideoList(hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    protected open suspend fun fetchResolvedVideo(video: Video): Video? = video

    final override suspend fun getHosterList(episode: SEpisode): List<Hoster> = fetchHosterList(episode).map(::proxied)

    final override suspend fun getVideoList(hoster: Hoster): List<Video> = fetchVideoList(hoster).map(::proxied)

    final override suspend fun resolveVideo(video: Video): Video? = fetchResolvedVideo(video)?.let(::proxied)

    /** Seek previews, Animiru only. */
    protected open suspend fun fetchVideoThumbnails(video: Video): VideoThumbnails? = null

    final override suspend fun getVideoThumbnails(video: Video): ThumbnailInfo? {
        val thumbnails = fetchVideoThumbnails(video) ?: return null
        imageTileHeaders = thumbnails.headers?.let { tileHeaders -> thumbnails.imageUrls.associateWith { tileHeaders } }.orEmpty()
        return ThumbnailInfo(
            tileInfo = thumbnails.tiles.map { TileInfo(it.imageIndex, it.timeMs, it.x, it.y, it.width, it.height) },
            imageTileUrls = thumbnails.imageUrls,
        )
    }

    // Headers for the images of the playing video's previews, which the app fetches by url only
    @Volatile
    private var imageTileHeaders = emptyMap<String, Headers>()

    final override suspend fun getImageTile(url: String): Bitmap? {
        val tileHeaders = imageTileHeaders[url] ?: headers
        return client.get(url, tileHeaders).use { BitmapFactory.decodeStream(it.body.byteStream()) }
    }

    protected open val homePageUrl: String get() = baseUrl

    final override fun getHomeUrl(): String = homePageUrl

    final override fun servesStreams(): Boolean = streamInterceptors.isNotEmpty()

    private fun proxyBase(): HttpUrl = if (AppFeatures.animiruHttpServer) {
        "${ProxyUrl.ANIMIRU_PLACEHOLDER}/".toHttpUrl()
    } else {
        "http://127.0.0.1:${SelfProxy.ensureStarted()}/".toHttpUrl()
    }

    private fun proxied(url: String, base: Lazy<HttpUrl>): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        // Already proxied, e.g. a resolved video: re-encode, the proxy may have restarted elsewhere
        val target = ProxyUrl.decode(httpUrl)?.takeIf { it.first == id }?.second ?: httpUrl
        if (ProxyUrl.isProxyUrl(target) || !handlesStream(target)) return url
        return ProxyUrl.encode(base.value, id, target).toString()
    }

    private fun proxied(track: Track, base: Lazy<HttpUrl>): Track {
        val url = proxied(track.url, base)
        return if (url == track.url) track else Track(url, track.lang)
    }

    // Every argument instead of copy(): both apps have exactly these constructors
    private fun proxied(hoster: Hoster): Hoster {
        val videos = hoster.videoList ?: return hoster
        val proxiedVideos = videos.map(::proxied)
        if (proxiedVideos.indices.all { proxiedVideos[it] === videos[it] }) return hoster
        return Hoster(
            hosterUrl = hoster.hosterUrl,
            hosterName = hoster.hosterName,
            videoList = proxiedVideos,
            internalData = hoster.internalData,
            lazy = hoster.lazy,
        )
    }

    private fun proxied(video: Video): Video {
        if (streamInterceptors.isEmpty() || video.videoUrl.isEmpty()) return video
        val base = lazy(::proxyBase)
        val videoUrl = proxied(video.videoUrl, base)
        val subtitles = video.subtitleTracks.map { proxied(it, base) }
        val audios = video.audioTracks.map { proxied(it, base) }
        if (!base.isInitialized()) return video

        StreamSources.register(this)
        return Video(
            videoUrl = videoUrl,
            videoTitle = video.videoTitle,
            resolution = video.resolution,
            bitrate = video.bitrate,
            headers = video.headers,
            preferred = video.preferred,
            subtitleTracks = subtitles,
            audioTracks = audios,
            timestamps = video.timestamps,
            mpvArgs = video.mpvArgs,
            ffmpegStreamArgs = video.ffmpegStreamArgs,
            ffmpegVideoArgs = video.ffmpegVideoArgs,
            internalData = video.internalData,
            initialized = video.initialized,
        )
    }

    final override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    final override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    final override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    final override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    final override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    final override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    final override fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    final override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    final override fun relatedAnimeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    final override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    final override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    final override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    final override fun seasonListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    final override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    final override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    final override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    final override fun videoListRequest(hoster: Hoster): Request = throw UnsupportedOperationException()
    final override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()
    final override suspend fun getVideoUrl(video: Video): String = throw UnsupportedOperationException()
    final override fun videoUrlRequest(video: Video): Request = throw UnsupportedOperationException()
    final override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()
}
