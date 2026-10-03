package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// uns.bio player ("Uni"). The player API answers with hex-encoded AES-CBC JSON listing one HLS
// playlist per delivery network; `r` must be the host of the page embedding the player.
class UniExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, embedderHost: String): List<Video> {
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
            .addQueryParameter("r", embedderHost)
            .build()

        val payload = client.get(apiUrl, playerHeaders).bodyString().trim()
        val streams = decrypt(payload).parseAs<UniStreams>()

        return listOf(
            "Tiktok" to streams.hlsVideoTiktok,
            "Google" to streams.hlsVideoGoogle,
            "Cloudflare" to (streams.cf ?: streams.cfNative),
            "In-House" to streams.source,
        ).parallelCatchingFlatMap { (network, path) ->
            val playlistUrl = path?.takeIf(String::isNotBlank)?.let { resolve(origin, it) }
                ?: return@parallelCatchingFlatMap emptyList()
            playlistUtils.extractFromHls(
                playlistUrl,
                referer = "$origin/",
                masterHeaders = playerHeaders,
                videoHeaders = playerHeaders,
                videoNameGen = { quality -> "Uni $network - $quality" },
            )
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
    class UniStreams(
        val hlsVideoTiktok: String? = null,
        val hlsVideoGoogle: String? = null,
        val cf: String? = null,
        val cfNative: String? = null,
        val source: String? = null,
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }
}
