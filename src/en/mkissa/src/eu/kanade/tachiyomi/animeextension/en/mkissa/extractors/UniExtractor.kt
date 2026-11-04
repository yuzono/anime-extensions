package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import aniyomi.lib.playlistutils.parseHlsMasterPlaylist
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// uns.bio player ("Uni"). The player API answers with hex-encoded AES-CBC JSON listing one HLS
// playlist per delivery network; `r` must be the host of the page embedding the player.
class UniExtractor(private val client: OkHttpClient, private val headers: Headers) {

    suspend fun videosFromUrl(url: String, embedderHost: String): List<Video> {
        val playerUrl = url.toHttpUrl()
        val origin = playerUrl.resolve("/")!!
        val videoId = playerUrl.fragment?.substringBefore("&")?.takeIf(String::isNotEmpty) ?: return emptyList()

        val playerHeaders = headers.newBuilder()
            .set("Referer", origin.toString())
            .build()

        val apiUrl = origin.resolve("/api/v1/video")!!.newBuilder()
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .addQueryParameter("r", embedderHost)
            .build()

        val payload = client.get(apiUrl, playerHeaders, cacheControl = CacheControl.FORCE_NETWORK).bodyString().trim()
        val streams = decrypt(payload).parseAs<UniStreams>()

        val config = parseStreamingConfig(streams.streamingConfig)
        val paths = mapOf(
            "Google" to streams.hlsVideoGoogle,
            // `cf` requires the browser player's signing loader; only cfNative is directly playable.
            "Cloudflare" to streams.cfNative,
            "In-House" to streams.source,
            "Tiktok" to streams.hlsVideoTiktok,
        )

        val configuredOrder = config.order.map(::normalizeNetwork).filter { it in paths }
        val fallbackOrder = listOf("Google", "Cloudflare", "In-House", "Tiktok")
        val networkOrder = (configuredOrder + fallbackOrder).distinct().filterNot { it == "Tiktok" } + "Tiktok"

        for (network in networkOrder) {
            val path = paths[network]?.takeIf(String::isNotBlank) ?: continue
            val adjustment = config.adjust[network]
                ?: config.adjust.entries.firstOrNull { normalizeNetwork(it.key) == network }?.value
            if (adjustment?.disabled == true) continue

            val videos = try {
                val resolvedUrl = origin.resolve(path) ?: continue
                val playlistUrl = resolvedUrl.newBuilder().apply {
                    adjustment?.params?.forEach { (key, value) ->
                        value.contentOrNull?.let { setQueryParameter(key, it) }
                    }
                    adjustment?.domain?.takeIf(String::isNotBlank)?.let { domain ->
                        encodedPath(resolvedUrl.encodedPath.replace("/hls/", "/hlsmod/$domain/"))
                    }
                }.build()

                val extracted = videosFromPlaylist(playlistUrl, playerHeaders)
                if (network == "Tiktok") extracted.map(::forceMpegTs) else extracted
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }

            if (videos.isNotEmpty()) {
                return videos
            }
        }

        return emptyList()
    }

    private suspend fun videosFromPlaylist(url: HttpUrl, headers: Headers): List<Video> = client.get(url, headers).use { response ->
        val playlist = response.body.string().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (playlist.lineSequence().firstOrNull()?.trimEnd() != "#EXTM3U") {
            throw IOException("Uni returned an invalid HLS playlist")
        }
        val playlistUrl = response.request.url.toString()
        val variants = parseHlsMasterPlaylist(playlistUrl, playlist)
        if (variants == null) {
            // An error or empty manifest must not prevent trying the next CDN.
            if (!playlist.contains("#EXTINF:") || playlist.lineSequence().none { it.isNotBlank() && !it.startsWith('#') }) {
                return@use emptyList()
            }
            return@use listOf(Video(videoUrl = playlistUrl, videoTitle = "Original", headers = headers))
        }
        variants.map { variant ->
            val height = variant.attributes["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
            Video(
                videoUrl = variant.url,
                videoTitle = height?.let { "${it}p" } ?: "Original",
                resolution = height,
                headers = headers,
                subtitleTracks = variant.subtitleTracks,
                audioTracks = variant.audioTracks,
            )
        }
    }

    private fun normalizeNetwork(name: String): String = when (name.lowercase()) {
        "google", "hlsvideogoogle" -> "Google"
        "cloudflare", "cf", "cfnative" -> "Cloudflare"
        "in-house", "source" -> "In-House"
        "tiktok", "hlsvideotiktok" -> "Tiktok"
        else -> name
    }

    private fun parseStreamingConfig(element: JsonElement?): StreamingConfig {
        if (element == null) return StreamingConfig()
        return runCatching {
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.parseAs<StreamingConfig>()
                is JsonObject -> element.parseAs<StreamingConfig>()
                else -> null
            }
        }.getOrNull() ?: StreamingConfig()
    }

    // Segments from the TikTok network only play with the container forced to MPEG-TS.
    private fun forceMpegTs(video: Video): Video = video.copy(
        mpvArgs = video.mpvArgs.filterNot { it.first == "demuxer-lavf-o" } +
            ("demuxer-lavf-o" to "force_mpegts=1"),
        ffmpegStreamArgs = video.ffmpegStreamArgs.filterNot { it.first == "force_mpegts" } +
            ("force_mpegts" to "1"),
    )

    private fun decrypt(hex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }

    @Serializable
    class UniStreams(
        val hlsVideoTiktok: String? = null,
        val hlsVideoGoogle: String? = null,
        val cfNative: String? = null,
        val source: String? = null,
        val streamingConfig: JsonElement? = null,
    )

    @Serializable
    private class StreamingConfig(
        val order: List<String> = listOf("Google", "Cloudflare", "In-House"),
        val adjust: Map<String, NetworkAdjustment> = emptyMap(),
    )

    @Serializable
    private class NetworkAdjustment(
        val disabled: Boolean = false,
        val domain: String? = null,
        val params: Map<String, JsonPrimitive>? = null,
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }
}
