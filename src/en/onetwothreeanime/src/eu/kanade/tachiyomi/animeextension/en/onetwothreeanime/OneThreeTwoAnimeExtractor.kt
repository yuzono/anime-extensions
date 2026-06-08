package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient

class OneThreeTwoAnimeExtractor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val preferredPlayer: String = PLAYER_JW,
) {

    // ------------------------------------------------------------------ //
    //  DTOs                                                               //
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

    private fun headers(referer: String = "$baseUrl/") = Headers.Builder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", referer)
        .build()

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    fun fetchVideos(animeSlug: String, episodeNum: String): List<Video> {
        val serverIds = runCatching { fetchServerIds(animeSlug) }.getOrElse {
            Log.e(TAG, "fetchServerIds failed", it)
            emptyList()
        }
        if (serverIds.isEmpty()) return emptyList()

        return serverIds.flatMap { (serverLabel, serverId) ->
            runCatching {
                fetchVideoForServer(animeSlug, episodeNum, serverId, serverLabel)
            }.getOrElse {
                Log.e(TAG, "fetchVideoForServer failed server=$serverLabel/$serverId", it)
                emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Step 1 – server tab IDs                                           //
    // ------------------------------------------------------------------ //

    fun fetchServerIds(animeSlug: String): List<Pair<String, String>> {
        val svUrl = "$baseUrl/ajax/film/sv?id=$animeSlug"
        val body = client.newCall(GET(svUrl, headers())).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        }
        val svJson = runCatching {
            body.parseAs<SvResponseDto>()
        }.getOrElse {
            Log.e(TAG, "fetchServerIds: JSON parse failed, body=${body.take(200)}", it)
            return emptyList()
        }
        val svDoc = org.jsoup.Jsoup.parse(svJson.html)
        val tabs = svDoc.select("span.tab[data-name]").map { tab ->
            Pair(tab.text().trim(), tab.attr("data-name"))
        }
        return tabs
    }

    // ------------------------------------------------------------------ //
    //  Step 2 – episode info                                             //
    // ------------------------------------------------------------------ //

    private fun fetchEpisodeInfo(animeSlug: String, episodeNum: String, serverId: String): EpisodeInfoDto? {
        val infoUrl = "$baseUrl/ajax/episode/info?epr=$animeSlug/$episodeNum/$serverId"
        val body = runCatching {
            client.newCall(GET(infoUrl, headers())).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body.string()
                }
            }
        }.getOrElse {
            Log.e(TAG, "fetchEpisodeInfo: request failed", it)
            return null
        } ?: return null
        return runCatching {
            body.parseAs<EpisodeInfoDto>()
        }.getOrElse {
            Log.e(TAG, "fetchEpisodeInfo: JSON parse failed", it)
            null
        }
    }

    // ------------------------------------------------------------------ //
    //  Step 3+4 – per-server video resolution                           //
    // ------------------------------------------------------------------ //

    private fun fetchVideoForServer(
        animeSlug: String,
        episodeNum: String,
        serverId: String,
        serverLabel: String,
    ): List<Video> {
        Log.d(TAG, "fetchVideoForServer: server=$serverLabel id=$serverId")
        val info = fetchEpisodeInfo(animeSlug, episodeNum, serverId) ?: return emptyList()

        val embedUrl = info.target.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "fetchVideoForServer: no target URL")
            return emptyList()
        }
        Log.d(TAG, "fetchVideoForServer: embed-3 url=${embedUrl.take(80)}")

        val embedBase = embedUrl.toHttpBaseOrNull() ?: "https://play2.echovideo.ru"
        val embedHostReferer = "$embedBase/"

        val innerToken = fetchInnerToken(embedUrl) ?: run {
            Log.w(TAG, "fetchVideoForServer: could not get inner token, falling back to embed URL")
            return listOf(
                Video(
                    url = embedUrl,
                    quality = "[$serverLabel] Embed",
                    videoUrl = embedUrl,
                    headers = headers(embedHostReferer),
                ),
            )
        }
        Log.d(TAG, "fetchVideoForServer: innerToken=${innerToken.take(60)}")

        val videos = mutableListOf<Video>()
        val streamUrl = resolvePlayerPage(embedBase, innerToken)
        Log.d(TAG, "fetchVideoForServer: streamUrl=$streamUrl")
        if (streamUrl != null) {
            val playerTag = if (preferredPlayer == PLAYER_JW) "JW" else "Legacy"
            videos.add(
                Video(
                    url = streamUrl,
                    quality = "[$serverLabel] $playerTag HLS",
                    videoUrl = streamUrl,
                    headers = hlsHeaders(embedHostReferer),
                ),
            )
        }

        val sbv2Url = resolveSubv2Player(embedBase, innerToken)
        Log.d(TAG, "fetchVideoForServer: sbv2Url=$sbv2Url")
        if (sbv2Url != null && sbv2Url != streamUrl) {
            videos.add(
                Video(
                    url = sbv2Url,
                    quality = "[$serverLabel] SBv2 HLS",
                    videoUrl = sbv2Url,
                    headers = hlsHeaders(embedHostReferer),
                ),
            )
        }

        if (videos.isEmpty()) {
            Log.w(TAG, "fetchVideoForServer: all paths failed, returning embed URL")
            videos.add(
                Video(
                    url = embedUrl,
                    quality = "[$serverLabel] Embed",
                    videoUrl = embedUrl,
                    headers = headers(embedHostReferer),
                ),
            )
        }

        return videos
    }

    // ------------------------------------------------------------------ //
    //  Step 3: Extract zrpart2 from embed-3 wrapper page                //
    // ------------------------------------------------------------------ //

    private fun fetchInnerToken(embed3Url: String): String? {
        Log.d(TAG, "fetchInnerToken: GET $embed3Url")
        val body = runCatching {
            client.newCall(GET(embed3Url, headers("$baseUrl/")))
                .execute()
                .use { response ->
                    Log.d(TAG, "fetchInnerToken: HTTP ${response.code}")
                    if (response.isSuccessful) {
                        response.body.string()
                    } else {
                        null
                    }
                }
        }.getOrElse {
            Log.e(TAG, "fetchInnerToken: request failed", it)
            null
        } ?: return null

        val token = ZRPART2_REGEX.find(body)?.groupValues?.getOrNull(1)
        Log.d(TAG, "fetchInnerToken: zrpart2=${token?.take(60) ?: "NOT FOUND"}")

        if (token == null) {
            val fallback = HS_LINK_REGEX.find(body)?.groupValues?.getOrNull(1)
            Log.d(TAG, "fetchInnerToken: fallback hs link=${fallback?.take(60) ?: "NOT FOUND"}")
            return fallback
        }
        return token
    }

    private fun resolvePlayerPage(embedBase: String, innerToken: String): String? = if (preferredPlayer == PLAYER_JW) {
        resolveJwPlayer(embedBase, innerToken)
    } else {
        resolveLegacyPlayer(embedBase, innerToken)
    }

    private fun resolveJwPlayer(embedBase: String, innerToken: String): String? {
        val hsUrl = "$embedBase/hs/$innerToken"
        Log.d(TAG, "resolveJwPlayer: GET $hsUrl")

        val body = runCatching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .execute()
                .use { response ->
                    Log.d(TAG, "resolveJwPlayer: HTTP ${response.code}")
                    if (response.isSuccessful) {
                        response.body.string()
                    } else {
                        null
                    }
                }
        }.getOrElse {
            Log.e(TAG, "resolveJwPlayer: request failed", it)
            null
        } ?: return null

        Log.d(TAG, "resolveJwPlayer: body length=${body.length} snippet=${body.take(150)}")

        val dataId = (
            DATA_ID_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?: DATA_ID_REGEX2.find(body)?.groupValues?.getOrNull(1)
            )
        Log.d(TAG, "resolveJwPlayer: data-id=${dataId?.take(60) ?: "null"}")
        if (!dataId.isNullOrBlank()) {
            val result = callGetSources("$embedBase/hs/getSources_z?id=$dataId", hsUrl)
                ?: callGetSources("$embedBase/hs/getSources?id=$dataId", hsUrl)
            Log.d(TAG, "resolveJwPlayer: getSources result=$result")
            if (result != null) return result
        }

        val fallback = extractM3u8(body) ?: extractMp4(body)
        Log.d(TAG, "resolveJwPlayer: inline fallback=$fallback")
        return fallback
    }

    private fun resolveLegacyPlayer(embedBase: String, innerToken: String): String? {
        val hsUrl = "$embedBase/hs/$innerToken?pl_usn=1"
        Log.d(TAG, "resolveLegacyPlayer: GET $hsUrl")

        val body = runCatching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .execute()
                .use { response ->
                    Log.d(TAG, "resolveLegacyPlayer: HTTP ${response.code}")
                    if (response.isSuccessful) {
                        response.body.string()
                    } else {
                        null
                    }
                }
        }.getOrElse {
            Log.e(TAG, "resolveLegacyPlayer: request failed", it)
            null
        } ?: return null

        Log.d(TAG, "resolveLegacyPlayer: body length=${body.length} snippet=${body.take(150)}")

        val sourcesJson = DIV_SOURCES_REGEX.find(body)?.groupValues?.getOrNull(1)
        Log.d(TAG, "resolveLegacyPlayer: sourcesJson=$sourcesJson")
        if (!sourcesJson.isNullOrBlank()) {
            val streamUrl = runCatching {
                sourcesJson.parseAs<SourcesDto>().sources.trim()
            }.getOrElse {
                Log.e(TAG, "resolveLegacyPlayer: SourcesDto parse failed", it)
                null
            }
            if (!streamUrl.isNullOrBlank()) {
                Log.d(TAG, "resolveLegacyPlayer: found via div#sources: $streamUrl")
                return streamUrl
            }
        }

        val fallback = extractM3u8(body) ?: extractMp4(body)
        Log.d(TAG, "resolveLegacyPlayer: inline fallback=$fallback")
        return fallback
    }

    // ------------------------------------------------------------------ //
    //  Bonus: /sbv2/ alternate player (soft-sub variant)                //
    //  Same structure as JW player — data-id → getSources               //
    // ------------------------------------------------------------------ //

    private fun resolveSubv2Player(embedBase: String, innerToken: String): String? {
        val sbv2Url = "$embedBase/sbv2/$innerToken"
        Log.d(TAG, "resolveSubv2Player: GET $sbv2Url")

        val body = runCatching {
            client.newCall(GET(sbv2Url, headers("$embedBase/")))
                .execute()
                .use { response ->
                    Log.d(TAG, "resolveSubv2Player: HTTP ${response.code}")
                    if (response.isSuccessful) {
                        response.body.string()
                    } else {
                        null
                    }
                }
        }.getOrElse {
            Log.e(TAG, "resolveSubv2Player: request failed", it)
            null
        } ?: return null

        val dataId = (
            DATA_ID_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?: DATA_ID_REGEX2.find(body)?.groupValues?.getOrNull(1)
            )
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

    private fun callGetSources(url: String, referer: String): String? = runCatching {
        client.newCall(GET(url, headers(referer))).execute().use { resp ->
            val body = resp.body.string()

            if (resp.isSuccessful && body.isNotBlank()) {
                body.parseAs<SourcesDto>()
                    .sources
                    .trim()
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }.getOrElse {
        Log.e(TAG, "callGetSources: failed for $url", it)
        null
    }

    // ------------------------------------------------------------------ //
    //  HLS playback headers                                              //
    //  These are passed to the Video object so Aniyomi / ExoPlayer        //
    //  sends them with every HLS manifest and segment request.            //
    //  The CDN (hlsx3cdn.burntburst45.store) requires the Referer to     //
    //  point back to the embed player host, otherwise it 403s.           //
    // ------------------------------------------------------------------ //

    private fun hlsHeaders(embedReferer: String): Headers = Headers.Builder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", embedReferer)
        .set("Origin", embedReferer.toHttpBaseOrNull() ?: "")
        .build()

    // ------------------------------------------------------------------ //
    //  URL helpers                                                        //
    // ------------------------------------------------------------------ //

    private fun String.toHttpBaseOrNull(): String? = runCatching {
        val u = java.net.URL(this)
        "${u.protocol}://${u.host}"
    }.getOrNull()

    // ------------------------------------------------------------------ //
    //  Stream extraction helpers                                          //
    // ------------------------------------------------------------------ //

    private fun extractM3u8(source: String): String? = M3U8_REGEX.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun extractMp4(source: String): String? = MP4_REGEX.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ //
    //  Constants & Regexes                                               //
    // ------------------------------------------------------------------ //

    companion object {
        private const val TAG = "123AnimeExtractor"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        const val PLAYER_JW = "jw"
        const val PLAYER_LEGACY = "legacy"

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
