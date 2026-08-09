package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

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
    private val preferredPlayer: String = PLAYER_JW,
) {

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

        return serverIds.parallelCatchingFlatMapBlocking { (serverLabel, serverId) ->
            fetchVideoForServer(animeSlug, episodeNum, serverId, serverLabel)
        }
    }

    // ------------------------------------------------------------------ //
    //  Step 1 – server tab IDs                                           //
    // ------------------------------------------------------------------ //

    private fun fetchServerIds(animeSlug: String): List<Pair<String, String>> {
        val svUrl = "$baseUrl/ajax/film/sv?id=$animeSlug"
        val svJson = client.newCall(GET(svUrl, headers())).execute()
            .parseAs<SvResponseDto>()
        val svDoc = Jsoup.parse(svJson.html)
        val tabs = svDoc.select("span.tab[data-name]").map { tab ->
            Pair(tab.text().trim(), tab.attr("data-name"))
        }
        return tabs
    }

    // ------------------------------------------------------------------ //
    //  Step 2 – episode info                                             //
    // ------------------------------------------------------------------ //

    private suspend fun fetchEpisodeInfo(animeSlug: String, episodeNum: String, serverId: String): EpisodeInfoDto {
        val infoUrl = "$baseUrl/ajax/episode/info?epr=$animeSlug/$episodeNum/$serverId"
        return client.newCall(GET(infoUrl, headers())).awaitSuccess()
            .parseAs<EpisodeInfoDto>()
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
        val info = fetchEpisodeInfo(animeSlug, episodeNum, serverId)

        val embedUrl = info.target.takeIf { it.isNotBlank() } ?: return emptyList()

        val embedBase = embedUrl.toHttpBaseOrNull() ?: "https://play2.echovideo.ru"
        val embedHostReferer = "$embedBase/"

        val innerToken = fetchInnerToken(embedUrl) ?: run {
            return Video(
                url = embedUrl,
                quality = "[$serverLabel] Embed",
                videoUrl = embedUrl,
                headers = headers(embedHostReferer),
            ).let(::listOf)
        }

        val videos = mutableListOf<Video>()
        val streamUrl = resolvePlayerPage(embedBase, innerToken)

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

    private suspend fun fetchInnerToken(embed3Url: String): String? {
        val body = runCatching {
            client.newCall(GET(embed3Url, headers("$baseUrl/")))
                .awaitSuccess()
                .bodyString()
        }.getOrNull() ?: return null

        val token = ZRPART2_REGEX.find(body)?.groupValues?.getOrNull(1)

        if (token == null) {
            val fallback = HS_LINK_REGEX.find(body)?.groupValues?.getOrNull(1)
            return fallback
        }
        return token
    }

    private suspend fun resolvePlayerPage(embedBase: String, innerToken: String): String? = if (preferredPlayer == PLAYER_JW) {
        resolveJwPlayer(embedBase, innerToken)
    } else {
        resolveLegacyPlayer(embedBase, innerToken)
    }

    private suspend fun resolveJwPlayer(embedBase: String, innerToken: String): String? {
        val hsUrl = "$embedBase/hs/$innerToken"

        val body = runCatching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        }.getOrElse { "" }

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

        val body = runCatching {
            client.newCall(GET(hsUrl, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        }.getOrElse { "" }

        val sourcesJson = DIV_SOURCES_REGEX.find(body)?.groupValues?.getOrNull(1)
        if (!sourcesJson.isNullOrBlank()) {
            val streamUrl = runCatching { sourcesJson.parseAs<SourcesDto>().sources.trim() }.getOrNull()
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

        val body = runCatching {
            client.newCall(GET(sbv2Url, headers("$embedBase/")))
                .awaitSuccess()
                .bodyString()
        }.getOrElse { "" }

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

    private suspend fun callGetSources(url: String, referer: String): String? = runCatching {
        client.newCall(GET(url, headers(referer)))
            .awaitSuccess()
            .parseAs<SourcesDto>()
            .sources.trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------------ //
    //  HLS playback headers                                              //
    //  These are passed to the Video object so Aniyomi / ExoPlayer        //
    //  sends them with every HLS manifest and segment request.            //
    //  The CDN (hlsx3cdn.burntburst45.store) requires the Referer to     //
    //  point back to the embed player host, otherwise it 403s.           //
    // ------------------------------------------------------------------ //

    private fun hlsHeaders(embedReferer: String): Headers = headers.newBuilder()
        .set("Referer", embedReferer)
        .set("Origin", embedReferer.toHttpBaseOrNull() ?: "")
        .build()

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
