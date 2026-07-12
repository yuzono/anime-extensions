package eu.kanade.tachiyomi.animeextension.en.miruro

import android.util.Base64
import android.util.Log
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.m3u8server.M3u8Integration
import aniyomi.lib.megacloudextractor.MegaCloudExtractor
import aniyomi.lib.omniembedextractor.OmniEmbedExtractor
import aniyomi.lib.rapidcloudextractor.RapidCloudExtractor
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class MiruroExtractor(
    private val client: OkHttpClient,
    private val pipeKey: ByteArray,
    private val proxyKey: ByteArray,
    private val headers: Headers,
    private val preferences: android.content.SharedPreferences,
    private val mirrorBaseUrl: String,
    private val resolveDisplayName: (String) -> String,
) {

    companion object {
        private const val TAG = "MiruroExtractor"

        /**
         * Host patterns that signal a Zoro-style embed (MegaCloud / RapidCloud
         * / ChillX family). Pre-routed to their dedicated extractors rather
         * than handed to OmniEmbedExtractor, which has no entry for these
         * domains and would silently return an empty list.
         */
        private val MEGACLOUD_HOSTS = listOf("megacloud.tv", "megacloud.club")
        private val RAPID_CLOUD_HOSTS = listOf("rapid-cloud.co", "scloud")

        /**
         * Placeholder for MegaCloud's external decryption endpoint; only used
         * by the encrypted-code path. The vast majority of current streams
         * return `.m3u8` directly (`data.encrypted == false` OR `.m3u8 in
         * encoded`), short-circuiting any key fetch. No prior call site ever
         * instantiated these extractors, so no production value exists to
         * mirror (confirmed by grep).
         */
        private const val MEGACLOUD_API_PLACEHOLDER = "https://megacloud.example/decrypt/"

        /**
         * Referer that StreamDto defaults to in [MiruroDto.StreamDto]. The
         * pipe API populates this as `https://kwik.cx/` for kwik-served HLS
         * streams (AnimePahe) but leaves the kwik default for many other
         * providers, including Miruro's own `vault-*.owocdn.top` CDN. Using
         * the kwik referer to fetch owocdn m3u8 is wrong — that host expects
         * a Miruro referer (the active mirror baseUrl) and 403s otherwise,
         * which previously triggered [CloudflareInterceptor] and the crash
         * chain documented in [MiruroExtractor]'s m3u8 path.
         */
        internal const val KWIK_DEFAULT_REFERER = "https://kwik.cx/"

        /**
         * Miruro frontend proxy servers (from `VITE_PROXY_A` / `VITE_PROXY_B`
         * in `env2.js`). The frontend wraps every provider stream URL through
         * one of these proxies: the proxy fetches the upstream m3u8/segment
         * and relays it back, bypassing CORS and header-gating that would
         * 403 a direct fetch from outside the browser.
         */
        private const val PROXY_A = "https://vault01.ultracloud.cc/"
        private const val PROXY_B = "https://vault02.ultracloud.cc/"

        /**
         * FNV-1a 32-bit hash constants (IETF RFC 7020).
         * Used by the frontend to deterministically select between
         * [PROXY_A] and [PROXY_B] based on episode/anilist IDs.
         */
        private const val FNV_OFFSET_BASIS: Int = 2166136261.toInt()
        private const val FNV_PRIME: Int = 16777619

        /**
         * Encode a [ByteArray] to base64url without padding, matching the
         * frontend's `ix()` / `ax()` obfuscation step (`btoa` + replace
         * `+`→`-`, `/`→`_`, strip `=`).
         */
        private fun base64UrlNoPad(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

        /**
         * XOR-obfuscate a UTF-8 string with [key] bytes (cycled) and
         * base64url-encode the result. Mirrors the frontend's `ix()`
         * function from `WatchRoute-B2vRFobK.js`.
         */
        private fun xorEncode(input: String, key: ByteArray): String {
            val bytes = input.toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) {
                out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            return base64UrlNoPad(out)
        }

        /**
         * FNV-1a 32-bit hash of a string, returning the hash mod 2 to
         * deterministically select between [PROXY_A] (even) and [PROXY_B]
         * (odd). Mirrors the frontend's `Xb()` function.
         *
         * If [seed] is blank, defaults to 0 (→ PROXY_A).
         */
        private fun fnv1aMod2(seed: String): Int {
            if (seed.isEmpty()) return 0
            var hash = FNV_OFFSET_BASIS
            for (b in seed.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toInt() and 0xFF)
                hash *= FNV_PRIME
            }
            return hash and 1
        }

        /**
         * Build a Miruro proxy URL wrapping [streamUrl] and [referer]
         * through `vault01/02.ultracloud.cc`. The proxy fetches the upstream
         * content and relays it, bypassing CORS/403s from direct fetches.
         *
         * URL format (from frontend `cx()` / `lx()`):
         * `{proxyBase}{xorEncode(streamUrl)}~{xorEncode(referer)}/pl.m3u8`
         *
         * If [proxyKey] is empty, returns the original [streamUrl] unchanged
         * (no proxy wrapping possible).
         */
        fun buildProxiedUrl(
            streamUrl: String,
            referer: String,
            proxyKey: ByteArray,
            proxySeed: String,
        ): String {
            if (proxyKey.isEmpty()) return streamUrl
            val proxyBase = if (fnv1aMod2(proxySeed) == 0) PROXY_A else PROXY_B
            val obfUrl = xorEncode(streamUrl, proxyKey)
            val obfReferer = xorEncode(referer, proxyKey)
            return "${proxyBase}$obfUrl~$obfReferer/pl.m3u8"
        }
    }

    private val embedExtractor by lazy { OmniEmbedExtractor(client, headers) }

    /**
     * Dedicated HTTP/1.1 client for media / m3u8 fetches. Some provider CDNs
     * (notably Zoro's edge) reject HTTP/2 connections with the host app's
     * default OkHttp fingerprint by stamping a 444 status and closing the
     * socket. Forcing HTTP/1.1 + a 30 s read timeout (matching the proven
     * AnikotoTheme `m3u8Client` shape) avoids that fingerprint check.
     *
     * A [CloudflareInterceptor] is wired in to transparently solve Cloudflare
     * challenges for upstreams that WAF the m3u8 / segment URL (AnimePahe →
     * kwik.cx CDN is the canonical case — see [lib/kwikextractor] for the
     * precedent). The interceptor caches `cf_clearance` per host, so after
     * the first WebView solve the cached cookies clear subsequent requests
     * without burning another solve.
     */
    private val mediaClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(CloudflareInterceptor(client))
            .build()
    }

    /**
     * Header-gated fallback leg: identical to [mediaClient] minus the
     * [CloudflareInterceptor]. Supplied to [M3u8Integration] / [m3u8Integration]
     * so that when the primary client's WebView solve fails (the canonical
     * crash chain: `vault-99.owocdn.top` returns 403 → CloudflareInterceptor
     * WebView solve produces no cookies → IOException propagates as 500 to
     * mpv), the m3u8 server retries through this client with whatever browser
     * headers the caller threaded via the proxied URL. Many header-gated
     * CDNs serve 200 to a plain HTTP/1.1 request with the correct Referer
     * once the WebView detour is bypassed.
     */
    private val mediaClientFallback by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val m3u8Integration by lazy { M3u8Integration(mediaClient, mediaClientFallback) }

    private val megaCloudExtractor by lazy {
        MegaCloudExtractor(mediaClient, headers, MEGACLOUD_API_PLACEHOLDER)
    }

    private val rapidCloudExtractor by lazy {
        RapidCloudExtractor(mediaClient, headers, preferences)
    }

    fun providerDisplayName(key: String): String = resolveDisplayName(key)

    fun decryptResponse(response: Response): String {
        val obfuscated = response.header("x-obfuscated") ?: "1"
        val bodyStr = response.body?.string()?.trim() ?: ""

        if (obfuscated != "2") {
            Log.d(TAG, "decryptResponse: not obfuscated (header=$obfuscated), ${bodyStr.length} chars")
            return bodyStr
        }

        if (bodyStr.isEmpty()) {
            Log.e(TAG, "Empty response body from server")
            return ""
        }

        return try {
            val decoded = Base64.decode(bodyStr, Base64.URL_SAFE)
            val data = decoded
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor pipeKey[i % pipeKey.size].toInt()).toByte()
            }

            val result = GZIPInputStream(java.io.ByteArrayInputStream(data)).use { gzipStream ->
                gzipStream.bufferedReader(Charsets.UTF_8).readText()
            }
            Log.d(TAG, "decryptResponse: decrypted ${bodyStr.length} → ${result.length} chars")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt response from server: ${e.message}")
            ""
        }
    }

    fun parseStreamsFromResponse(
        response: Response,
        subType: String?,
        providerKey: String = "",
        episodeId: String = "",
        anilistId: String = "",
    ): List<Video> {
        val json = try {
            response.use(::decryptResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt stream response: ${e.message}")
            return emptyList()
        }

        val sourcesDto = try {
            SourcesResponseDto.parse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sources response: ${e.message}")
            return emptyList()
        }

        if (sourcesDto.streams.isEmpty()) {
            Log.w(TAG, "Empty streams array in response (subType=$subType, provider=$providerKey)")
            return emptyList()
        }

        Log.d(TAG, "parseStreamsFromResponse: ${sourcesDto.streams.size} streams, ${sourcesDto.subtitles.size} subtitles (subType=$subType, provider=$providerKey)")

        val subTypeLabel = when (subType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            "h-sub" -> "Hard Sub"
            null -> null
            else -> subType.replaceFirstChar { it.uppercase() }
        }

        val subtitles = sourcesDto.subtitles
            .filter { it.url.isNotEmpty() }
            .map { sub ->
                Track(sub.url, sub.label.ifEmpty { sub.language })
            }

        val videos = mutableListOf<Video>()

        // FNV hash seed for proxy server selection: `${episodeId}|${anilistId}`,
        // matching the frontend's `Xb(episodeId, anilistId)` logic.
        val proxySeed = "$episodeId|$anilistId"

        for (stream in sourcesDto.streams) {
            if (stream.url.isEmpty()) continue

            val qualityInt = stream.quality.toIntOrNull() ?: 0
            val width = stream.resolution?.width ?: 0
            val height = stream.resolution?.height ?: 0

            val streamTypeLabel = stream.type.uppercase()

            val qualityLabel = buildString {
                if (providerKey.isNotEmpty()) append("${providerDisplayName(providerKey)} - ")
                append("${qualityInt}p")
                if (subTypeLabel != null) append(" $subTypeLabel")
                if (width > 0 && height > 0) append(" - ${width}x$height")
                if (stream.codec.isNotEmpty()) append(" ${stream.codec}")
                if (stream.audio.isNotEmpty()) append(" ${stream.audio}")
                if (stream.fansub.isNotEmpty()) append(" ${stream.fansub}")
                append(" $streamTypeLabel")
            }

            when (stream.type.lowercase()) {
                "hls" -> {
                    val proxyReferer = stream.referer.trim().ifEmpty { KWIK_DEFAULT_REFERER }
                    val proxiedUrl = buildProxiedUrl(
                        streamUrl = stream.url,
                        referer = proxyReferer,
                        proxyKey = proxyKey,
                        proxySeed = proxySeed,
                    )
                    if (proxiedUrl != stream.url) {
                        Log.d(TAG, "HLS proxy-wrapped: ${stream.url.take(60)} → ${proxiedUrl.take(60)}")
                    }
                    val proxyHeaders = headers.newBuilder()
                        .set("Referer", "${mirrorBaseUrl.trimEnd('/')}/")
                        .set("Origin", mirrorBaseUrl.trimEnd('/'))
                        .build()
                    videos.add(
                        Video(proxiedUrl, qualityLabel, proxiedUrl, proxyHeaders, subtitleTracks = subtitles),
                    )
                }
                "embed" -> {
                    Log.d(TAG, "parseStreams: extracting embed: ${stream.url.take(80)}")
                    val embedVideos = extractPreRoutedEmbed(
                        embedUrl = stream.url,
                        qualityLabel = qualityLabel,
                        subtitles = subtitles,
                    )
                    if (embedVideos.isNotEmpty()) {
                        videos.addAll(embedVideos)
                    } else {
                        Log.w(TAG, "Failed to extract from embed: ${stream.url.take(80)}")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown stream type '${stream.type}', skipping: ${stream.url.take(80)}")
                }
            }
        }

        val proxied = m3u8Integration.processVideoList(videos)
        Log.d(TAG, "parseStreamsFromResponse: built ${videos.size} videos from ${sourcesDto.streams.size} streams, ${proxied.size} proxied via m3u8server")
        return proxied
    }

    private fun extractPreRoutedEmbed(
        embedUrl: String,
        qualityLabel: String,
        subtitles: List<Track>,
    ): List<Video> {
        val host = runCatching { embedUrl.toHttpUrlOrNull()?.host }.getOrNull()
        val lowerHost = host?.lowercase() ?: ""

        return when {
            MEGACLOUD_HOSTS.any { lowerHost.contains(it) } -> runCatching {
                megaCloudExtractor.getVideosFromUrl(
                    url = embedUrl,
                    type = "Multi",
                    name = qualityLabel,
                    withM3u8Server = false,
                )
            }.onFailure {
                Log.w(TAG, "MegaCloud extraction failed: ${it.message}")
            }.getOrDefault(emptyList())

            RAPID_CLOUD_HOSTS.any { lowerHost.contains(it) } -> runCatching {
                rapidCloudExtractor.getVideosFromUrl(embedUrl, type = "Multi", name = qualityLabel)
            }.onFailure {
                Log.w(TAG, "RapidCloud extraction failed: ${it.message}")
            }.getOrDefault(emptyList())

            else -> {
                embedExtractor.extractVideos(
                    embedUrl = embedUrl,
                    qualityLabel = qualityLabel,
                    subtitles = subtitles,
                )
            }
        }
    }
}
