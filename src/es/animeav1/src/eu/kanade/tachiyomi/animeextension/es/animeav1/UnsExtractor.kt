package eu.kanade.tachiyomi.animeextension.es.animeav1

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// uns.bio player ("UPNShare"). The player API answers with hex-encoded AES-CBC JSON listing one HLS
// playlist per delivery network; `r` must be the host of the page embedding the player.
class UnsExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val playerUrl = url.toHttpUrl()
        val origin = "${playerUrl.scheme}://${playerUrl.host}"
        val videoId = playerUrl.fragment?.substringBefore("&")?.takeIf(String::isNotEmpty) ?: return emptyList()

        val playerHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .build()

        val apiUrl = "$origin/api/v1/video".toHttpUrl().newBuilder()
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .addQueryParameter("r", "animeav1.com")
            .build()

        val payload = client.get(apiUrl, playerHeaders, cacheControl = CacheControl.FORCE_NETWORK).bodyString().trim()
        val streams = decrypt(payload).parseAs<UnsStreams>()

        val config = runCatching { streams.streamingConfig?.parseAs<StreamingConfig>() }.getOrNull() ?: StreamingConfig()
        val cloudflarePath = streams.cf?.takeIf(String::isNotBlank) ?: streams.cfNative
        val paths = mapOf(
            "Cloudflare" to cloudflarePath,
            "Google" to streams.hlsVideoGoogle,
            "In-House" to streams.source,
        )

        return config.order.flatMap { network ->
            val path = paths[network]?.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
            val adjustment = config.adjust[network]
            if (adjustment?.disabled == true) return@flatMap emptyList()
            runCatching {
                val resolvedUrl = resolve(origin, path).toHttpUrl()
                val playlistUrl = resolvedUrl.newBuilder().apply {
                    (adjustment?.params as? JsonObject)?.forEach { (key, value) ->
                        (value as? JsonPrimitive)?.contentOrNull?.let { setQueryParameter(key, it) }
                    }
                    adjustment?.domain?.takeIf(String::isNotBlank)?.let { domain ->
                        encodedPath(resolvedUrl.encodedPath.replace("/hls/", "/hlsmod/$domain/"))
                    }
                }.build().toString()
                playlistUtils.extractFromHls(
                    playlistUrl,
                    referer = "$origin/",
                    masterHeaders = playerHeaders,
                    videoHeaders = playerHeaders,
                    videoNameGen = { quality -> "${prefix}UPNShare $network - $quality" },
                )
            }.getOrDefault(emptyList())
        }
    }

    private fun resolve(origin: String, path: String): String = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("http") -> path
        else -> origin + "/" + path.removePrefix("/")
    }

    private fun decrypt(hex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }

    @Serializable
    class UnsStreams(
        val hlsVideoGoogle: String? = null,
        val cf: String? = null,
        val cfNative: String? = null,
        val source: String? = null,
        val streamingConfig: String? = null,
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
        val params: JsonElement? = null,
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }
}
