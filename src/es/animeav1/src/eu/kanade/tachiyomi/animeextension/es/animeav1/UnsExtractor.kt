package eu.kanade.tachiyomi.animeextension.es.animeav1

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
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

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
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

        val payload = client.newCall(GET(apiUrl, playerHeaders)).execute().bodyString().trim()
        val streams = decrypt(payload).parseAs<UnsStreams>()

        val tiktokVersion = streams.streamingConfig?.let { TIKTOK_VERSION_REGEX.find(it)?.groupValues?.get(1) }
        val tiktokUrl = streams.hlsVideoTiktok?.takeIf(String::isNotBlank)?.let {
            val url = resolve(origin, it)
            tiktokVersion?.let { version ->
                url.toHttpUrl().newBuilder().addQueryParameter("v", version).build().toString()
            } ?: url
        }
        val cloudflarePath = streams.cf?.takeIf(String::isNotBlank) ?: streams.cfNative
        val cloudflareUrl = cloudflarePath?.takeIf(String::isNotBlank)?.let { resolve(origin, it) }
        val sourceUrl = streams.source?.takeIf(String::isNotBlank)?.let { resolve(origin, it) }

        return listOf(
            "Cloudflare" to cloudflareUrl,
            "Tiktok" to tiktokUrl,
            "In-House" to sourceUrl,
        ).flatMap { (network, playlistUrl) ->
            if (playlistUrl == null) {
                emptyList()
            } else {
                runCatching {
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
        val hlsVideoTiktok: String? = null,
        val cf: String? = null,
        val cfNative: String? = null,
        val source: String? = null,
        val streamingConfig: String? = null,
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
        private val TIKTOK_VERSION_REGEX = Regex(""""Tiktok"[^}]*?"v":"(\d+)"""")
    }
}
