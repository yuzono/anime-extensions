package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class OneThreeTwoAnimeExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ------------------------------------------------------------------ //
    //  DTOs                                                              //
    // ------------------------------------------------------------------ //

    @Serializable
    data class EpisodeInfoDto(
        val target: String = "",
        val grabber: String = "",
        val type: String = "",
        val name: String = "",
        val subtitle: String = "",
        val backup: Int = 0,
    )

    @Serializable
    data class SvResponseDto(val html: String = "")

    @Serializable
    data class SourcesDto(val sources: String = "")

    // ------------------------------------------------------------------ //
    //  Shared headers                                                     //
    // ------------------------------------------------------------------ //

    private fun headers(referer: String = "$baseUrl/") = headers.newBuilder()
        .set("Referer", referer)
        .build()

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    fun fetchVideos(animeSlug: String, episodeNum: String): List<Video> {
        val serverIds = fetchServerIds(animeSlug)
        if (serverIds.isEmpty()) return emptyList()

        // Different tabs can resolve to the same stream URL (verified live:
        // "F5 - HQ" and "No Ads 4" both return the identical m3u8), but each
        // entry is already labeled with its server name, so keep them all
        // visible like on the site instead of collapsing equal URLs.
        return serverIds.parallelCatchingFlatMapBlocking { (serverLabel, serverId) ->
            fetchVideoForServer(animeSlug, episodeNum, serverId, serverLabel)
        }
    }

    // ------------------------------------------------------------------ //
    //  Step 1 – server tab IDs                                           //
    // ------------------------------------------------------------------ //

    private fun fetchServerIds(animeSlug: String): List<Pair<String, String>> {
        val svUrl = "$baseUrl/ajax/film/sv?id=$animeSlug"
        val svJson = retry { client.newCall(GET(svUrl, headers())).execute().parseAs<SvResponseDto>() }
            ?: return emptyList()
        val svDoc = Jsoup.parse(svJson.html)
        val tabs = svDoc.select("span.tab[data-name]").map { tab ->
            Pair(tab.text().trim(), tab.attr("data-name"))
        }
        return tabs
    }

    // ------------------------------------------------------------------ //
    //  Step 2 – episode info                                             //
    // ------------------------------------------------------------------ //

    private suspend fun fetchEpisodeInfo(animeSlug: String, episodeNum: String, serverId: String): EpisodeInfoDto? {
        val infoUrl = "$baseUrl/ajax/episode/info?epr=$animeSlug/$episodeNum/$serverId"
        return retry { client.newCall(GET(infoUrl, headers())).awaitSuccess().parseAs<EpisodeInfoDto>() }
    }

    // ------------------------------------------------------------------ //
    //  Step 3+4 – per-server video resolution                           //
    // ------------------------------------------------------------------ //

    private suspend fun fetchVideoForServer(
        animeSlug: String,
        episodeNum: String,
        serverId: String,
        serverLabel: String,
    ): List<Video> {
        val info = fetchEpisodeInfo(animeSlug, episodeNum, serverId) ?: return emptyList()

        val embedUrl = info.target.takeIf { it.isNotBlank() } ?: return emptyList()

        val embedBase = embedUrl.toHttpBaseOrNull() ?: "https://play2.echovideo.ru"
        val embedHostReferer = "$embedBase/"

        val innerToken = fetchInnerToken(embedUrl) ?: return emptyList()

        val streamUrl = resolvePlayerPage(embedBase, innerToken)

        if (streamUrl != null) {
            val videoHeaders = hlsHeaders(embedHostReferer)

            // The resolved m3u8 is usually a master playlist; expand it into
            // per-quality videos. Falls back to the raw master URL when the
            // playlist cannot be inspected.
            val videos = (
                catching<List<Video>> {
                    playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = embedHostReferer,
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                        videoNameGen = { quality -> "[$serverLabel] $quality" },
                    )
                } ?: emptyList()
                )
                .ifEmpty {
                    listOf(
                        Video(
                            url = streamUrl,
                            quality = "[$serverLabel] HLS",
                            videoUrl = streamUrl,
                            headers = videoHeaders,
                        ),
                    )
                }
            return videos
        }

        return emptyList()
    }

    // ------------------------------------------------------------------ //
    //  Step 3: Extract zrpart2 from embed-3 wrapper page                //
    // ------------------------------------------------------------------ //

    private suspend fun fetchInnerToken(embed3Url: String): String? {
        val body = retry {
            client.newCall(GET(embed3Url, headers("$baseUrl/")))
                .awaitSuccess()
                .bodyString()
        } ?: return null

        val token = ZRPART2_REGEX.find(body)?.groupValues?.getOrNull(1)

        if (token == null) {
            val fallback = HS_LINK_REGEX.find(body)?.groupValues?.getOrNull(1)
            return fallback
        }
        return token
    }

    private suspend fun resolvePlayerPage(embedBase: String, innerToken: String): String? = resolveJwPlayer(embedBase, innerToken)
        ?: resolveLegacyPlayer(embedBase, innerToken)
        ?: resolveSubv2Player(embedBase, innerToken)

    private suspend fun resolveJwPlayer(embedBase: String, innerToken: String): String? {
        val hsUrl = "$embedBase/hs/$innerToken"

        val body = catching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        } ?: ""

        val dataId = DATA_ID_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?: DATA_ID_REGEX2.find(body)?.groupValues?.getOrNull(1)

        if (!dataId.isNullOrBlank()) {
            val result = callGetSources("$embedBase/hs/getSources_z?id=$dataId", hsUrl)
                ?: callGetSources("$embedBase/hs/getSources?id=$dataId", hsUrl)
            if (result != null) return result
        }

        val fallback = extractM3u8(body) ?: extractMp4(body)
        return fallback
    }

    private suspend fun resolveLegacyPlayer(embedBase: String, innerToken: String): String? {
        val hsUrl = "$embedBase/hs/$innerToken?pl_usn=1"

        val body = catching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        } ?: ""

        val sourcesJson = DIV_SOURCES_REGEX.find(body)?.groupValues?.getOrNull(1)
        if (!sourcesJson.isNullOrBlank()) {
            val streamUrl = catching { sourcesJson.parseAs<SourcesDto>().sources.trim() }
            if (!streamUrl.isNullOrBlank()) {
                return streamUrl
            }
        }

        val fallback = extractM3u8(body) ?: extractMp4(body)
        return fallback
    }

    // ------------------------------------------------------------------ //
    //  Bonus: /sbv2/ alternate player (soft-sub variant)                //
    //  Same structure as JW player — data-id → getSources               //
    // ------------------------------------------------------------------ //

    private suspend fun resolveSubv2Player(embedBase: String, innerToken: String): String? {
        val sbv2Url = "$embedBase/sbv2/$innerToken"

        val body = catching {
            client.newCall(GET(sbv2Url, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        } ?: ""

        val dataId = DATA_ID_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?: DATA_ID_REGEX2.find(body)?.groupValues?.getOrNull(1)
        if (!dataId.isNullOrBlank()) {
            val getSourcesUrl = "$embedBase/sbv2/getSources?id=$dataId"
            val result = callGetSources(getSourcesUrl, sbv2Url)
            if (result != null) return result
        }

        return extractM3u8(body) ?: extractMp4(body)
    }

    // ------------------------------------------------------------------ //
    //  GET /hs/getSources_z?id=...  or  /hs/getSources?id=...            //
    //  → { "sources": "https://...m3u8" }                               //
    // ------------------------------------------------------------------ //

    private suspend fun callGetSources(url: String, referer: String): String? = catching {
        client.newCall(GET(url, headers(referer)))
            .awaitSuccess()
            .parseAs<SourcesDto>()
            .sources.trim()
            .takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ //
    //  HLS playback headers                                              //
    //  These are passed to the Video object so Aniyomi / ExoPlayer        //
    //  sends them with every HLS manifest and segment request.            //
    // ------------------------------------------------------------------ //

    private fun hlsHeaders(embedReferer: String): Headers = headers.newBuilder()
        .set("Referer", embedReferer)
        .set("Origin", embedReferer.toHttpBaseOrNull() ?: "")
        // Explicit browser UA so playback does not depend on the app's
        // user-configurable default (can be empty or non-browser), which
        // Cloudflare-fronted stream hosts are known to treat differently.
        .set("User-Agent", PLAYER_USER_AGENT)
        .build()

    // ------------------------------------------------------------------ //
    //  The site's endpoints intermittently answer 5xx / drop; one retry   //
    //  recovers most of them instead of failing the whole server.         //
    // ------------------------------------------------------------------ //

    private inline fun <T> retry(attempts: Int = 2, block: () -> T): T? {
        repeat(attempts) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // fallthrough to next attempt
            }
        }
        return null
    }

    private inline fun <T> catching(block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ //
    //  URL helpers                                                        //
    // ------------------------------------------------------------------ //

    private fun String.toHttpBaseOrNull(): String? = toHttpUrlOrNull()?.let {
        "${it.scheme}://${it.host}"
    }

    // ------------------------------------------------------------------ //
    //  Stream extraction helpers                                          //
    // ------------------------------------------------------------------ //

    private fun extractM3u8(source: String): String? = M3U8_REGEX.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun extractMp4(source: String): String? = MP4_REGEX.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ //
    //  Constants & Regexes                                               //
    // ------------------------------------------------------------------ //

    companion object {
        // Sent on every Video so the player requests look browser-made.
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // embed-3 wrapper page: var zrpart2 = '<base64>';
        private val ZRPART2_REGEX = Regex(
            """var\s+zrpart2\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""",
        )

        // Fallback: direct /hs/ link in embed-3 page
        private val HS_LINK_REGEX = Regex(
            """`/hs/([A-Za-z0-9+/=]+)`""",
        )

        // JW player page (/hs/ no param): <div id="mg-player" data-id="...">
        private val DATA_ID_REGEX = Regex(
            """<div[^>]+id=["']mg-player["'][^>]*data-id=["']([A-Za-z0-9+/=]{10,})["']""",
        )

        // Also handles data-id before id= in the tag
        private val DATA_ID_REGEX2 = Regex(
            """<div[^>]*data-id=["']([A-Za-z0-9+/=]{10,})["'][^>]*id=["']mg-player["']""",
        )

        // Legacy/Plyr page (/hs/?pl_usn=1): <div id="sources">{...}</div>
        private val DIV_SOURCES_REGEX = Regex(
            """<div[^>]+id=["']sources["'][^>]*>\s*(\{[^<]+\})\s*</div>""",
            RegexOption.IGNORE_CASE,
        )

        private val M3U8_REGEX = Regex(
            """["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""",
        )

        private val MP4_REGEX = Regex(
            """["'`](https?://[^"'`\s]+\.mp4[^"'`\s]*)["'`]""",
        )
    }
}
