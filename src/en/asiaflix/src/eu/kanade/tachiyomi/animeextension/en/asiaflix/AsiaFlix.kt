package eu.kanade.tachiyomi.animeextension.en.asiaflix

import android.util.Base64
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.EntryDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.EpisodePayload
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.PagedDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.StreamResultDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.StreamUrlDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class AsiaFlix : AnimeHttpSource() {

    override val name = "AsiaFlix"

    override val baseUrl = "https://asiaflix.net"

    private val apiUrl = "https://api.asiaflix.net/v1"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Access-Control", "web")
            .build()
    }

    // the hls proxy 403s requests without these
    private val videoHeaders by lazy {
        headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$apiUrl/drama/list?type=popular&page=$page&limit=$LIMIT", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<PagedDto<EntryDto>>()

        return AnimesPage(result.body.map(EntryDto::toSAnime), result.hasNext)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$apiUrl/drama/dynamic-fetch?type=latest%20updates&page=$page&limit=$LIMIT", apiHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/drama/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString())
            .build()
            .toString()

        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/drama/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime) = "$apiUrl/drama/detail".toHttpUrl().newBuilder()
        .addQueryParameter("slug", anime.url)
        .build()
        .let { GET(it.toString(), apiHeaders) }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<EntryDto>().toSAnime()

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val result = response.parseAs<EntryDto>()
        val episodes = result.episodes.orEmpty()

        if (episodes.isEmpty()) throw Exception("No episodes found.")

        return episodes.map {
            SEpisode.create().apply {
                val number = it.number
                name = if (number % 1f == 0f) "Episode ${number.toInt()}" else "Episode $number"
                episode_number = number
                scanlator = it.type?.uppercase(Locale.US)
                url = Base64.encodeToString(
                    json.encodeToString(EpisodePayload(number, it.streamUrls)).toByteArray(),
                    Base64.NO_WRAP,
                )
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, videoHeaders) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, videoHeaders) }
    private val doodStreamExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, videoHeaders) }

    // dummy request that carries the encoded stream server list
    override fun videoListRequest(episode: SEpisode): Request = "$baseUrl/".toHttpUrl().newBuilder()
        .addQueryParameter("ep", episode.url)
        .build()
        .let(::GET)

    override fun videoListParse(response: Response): List<Video> {
        val payload = response.use { it.request.url.queryParameter("ep") } ?: return emptyList()
        val hostUrls = runCatching {
            json.decodeFromString<EpisodePayload>(Base64.decode(payload, Base64.NO_WRAP).decodeToString()).urls
        }.getOrElse { return emptyList() }

        return hostUrls.filter { it.url.isNotBlank() }
            .parallelCatchingFlatMapBlocking(::getVideos)
            .distinctBy { it.videoUrl }
    }

    private suspend fun getVideos(host: StreamUrlDto): List<Video> = getApiVideos(host).ifEmpty { getExtractorVideos(host.url) }

    private suspend fun getApiVideos(host: StreamUrlDto): List<Video> = runCatching {
        val value = Base64.encodeToString(host.url.toByteArray(), Base64.NO_WRAP)

        val result = client.newCall(
            GET(
                "$apiUrl/drama/get-stream-url".toHttpUrl().newBuilder()
                    .addQueryParameter("value", value)
                    .addQueryParameter("server", host.source.lowercase(Locale.US))
                    .build()
                    .toString(),
                apiHeaders,
            ),
        ).execute().parseAs<StreamResultDto>()

        result.sources.filter { it.url.isNotBlank() }.flatMap { file ->
            if (file.isM3U8) {
                playlistUtils.extractFromHls(
                    file.url,
                    referer = "$baseUrl/",
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { quality -> "${host.source} - $quality" },
                )
            } else {
                listOf(Video(file.url, "${host.source} - Video", file.url, headers = videoHeaders))
            }
        }
    }.getOrDefault(emptyList())

    private suspend fun getExtractorVideos(hostUrl: String): List<Video> = when {
        hostUrl.containsAny("dood", "d000d", "do0od", "playmogo") ->
            doodStreamExtractor.videosFromUrl(hostUrl)

        hostUrl.contains("streamtape") -> streamTapeExtractor.videoFromUrl(hostUrl).let(::listOfNotNull)

        hostUrl.contains("mixdrop") -> mixDropExtractor.videoFromUrl(hostUrl)

        hostUrl.containsAny("dwish", "streamwish", "cybervynx", "vibuxer") ->
            streamWishExtractor.videosFromUrl(hostUrl)

        hostUrl.containsAny("dlions", "smoothpre", "vidhide") -> vidHideExtractor.videosFromUrl(hostUrl)

        // the api resolver 500s on vidmoly embeds, so this branch is the only path for them
        hostUrl.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(hostUrl)

        else -> emptyList()
    }

    private fun String.containsAny(vararg needles: String) = needles.any { contains(it) }

    companion object {
        private const val LIMIT = 20
    }
}
