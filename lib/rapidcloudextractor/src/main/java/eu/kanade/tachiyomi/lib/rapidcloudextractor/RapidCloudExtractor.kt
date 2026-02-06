package eu.kanade.tachiyomi.lib.rapidcloudextractor

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RapidCloudExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {
    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val webViewResolver by lazy { WebViewResolver(headers) }

    private val cacheControl = CacheControl.Builder().noStore().build()
    private val noCacheClient = client.newBuilder()
        .cache(null)
        .build()

    companion object {
        private val SERVER_URL = arrayOf("https://rapid-cloud.co", "some-clouds-which-still-using-old-v6")
        private val SOURCES_URL = arrayOf("/embed-2/v2/e-1/getSources?id=", "/ajax/embed-6-v2/getSources?id=")
        private val SOURCES_SPLITTER = arrayOf("/e-1/", "/embed-6-v2/")
        private val SOURCES_KEY = arrayOf("1", "6")
        private const val E1_SCRIPT_URL = "/js/player/a/v2/pro/embed-1.min.js"
        private const val E6_SCRIPT_URL = "/js/player/e6-player-v2.min.js"
        private val MUTEX = Mutex()
        private var shouldUpdateKey = false
        private const val PREF_KEY_KEY = "megacloud_key_"
        private const val PREF_KEY_DEFAULT = "[[0, 0]]"

        private inline fun <reified R> runLocked(crossinline block: () -> R) = runBlocking(Dispatchers.IO) {
            MUTEX.withLock { block() }
        }
    }

    // Stolen from TurkAnime
    private fun getKey(type: String): List<List<Int>> = runLocked {
        if (shouldUpdateKey) {
            updateKey(type)
            shouldUpdateKey = false
        }
        json.decodeFromString<List<List<Int>>>(
            preferences.getString(PREF_KEY_KEY + type, PREF_KEY_DEFAULT)!!,
        )
    }

    private fun updateKey(type: String) {
        val scriptUrl = when (type) {
            "1" -> "${SERVER_URL[0]}$E1_SCRIPT_URL"
            "6" -> "${SERVER_URL[1]}$E6_SCRIPT_URL"
            else -> throw Exception("Unknown key type")
        }
        val script = noCacheClient.newCall(GET(scriptUrl, cache = cacheControl))
            .execute()
            .body.string()
        val regex =
            Regex("case\\s*0x[0-9a-f]+:(?![^;]*=partKey)\\s*\\w+\\s*=\\s*(\\w+)\\s*,\\s*\\w+\\s*=\\s*(\\w+);")
        val matches = regex.findAll(script).toList()
        val indexPairs = matches.map { match ->
            val var1 = match.groupValues[1]
            val var2 = match.groupValues[2]

            val regexVar1 = Regex(",$var1=((?:0x)?([0-9a-fA-F]+))")
            val regexVar2 = Regex(",$var2=((?:0x)?([0-9a-fA-F]+))")

            val matchVar1 = regexVar1.find(script)?.groupValues?.get(1)?.removePrefix("0x")
            val matchVar2 = regexVar2.find(script)?.groupValues?.get(1)?.removePrefix("0x")

            if (matchVar1 != null && matchVar2 != null) {
                try {
                    listOf(matchVar1.toInt(16), matchVar2.toInt(16))
                } catch (_: NumberFormatException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }.filter { it.isNotEmpty() }
        val encoded = json.encodeToString(indexPairs)
        preferences.edit().putString(PREF_KEY_KEY + type, encoded).apply()
    }

    private fun cipherTextCleaner(data: String, type: String): Pair<String, String> {
        val indexPairs = getKey(type)
        val (password, ciphertext, _) = indexPairs.fold(Triple("", data, 0)) { previous, item ->
            val start = item.first() + previous.third
            val end = start + item.last()
            val passSubstr = data.substring(start, end)
            val passPart = previous.first + passSubstr
            val cipherPart = previous.second.replace(passSubstr, "")
            Triple(passPart, cipherPart, previous.third + item.last())
        }

        return Pair(ciphertext, password)
    }

    private fun tryDecrypting(ciphered: String, type: String, attempts: Int = 0): String {
        if (attempts > 2) throw Exception("Maximum decryption attempts exceeded")
        val (ciphertext, password) = cipherTextCleaner(ciphered, type)
        return CryptoAES.decrypt(ciphertext, password).ifEmpty {
            // Update index pairs
            shouldUpdateKey = true
            tryDecrypting(ciphered, type, attempts + 1)
        }
    }

    fun getVideosFromUrl(url: String, type: String, name: String): List<Video> {
        val videos = getVideoDto(url)
        if (videos.isEmpty()) return emptyList()

        val subtitles = videos.first().tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()
            .let(playlistUtils::fixSubtitles)

        return videos.flatMap { video ->
            playlistUtils.extractFromHls(
                video.m3u8,
                videoNameGen = { "$name - $it - $type" },
                subtitleList = subtitles,
                referer = "https://${url.toHttpUrl().host}/",
            )
        }
    }

    private fun getVideoDto(url: String): List<VideoDto> {
        val type = if (url.contains("rapid-cloud")) 0 else 1

        val keyType = SOURCES_KEY[type]

        val id = url.substringAfter(SOURCES_SPLITTER[type], "")
            .substringBefore("?", "")
            .ifEmpty { throw Exception("Failed to extract ID from URL") }

        // Previous method using WebViewResolver to get key
        // if (type == 0) {
        //     return webViewResolver.getSources(id)!!
        // }

        val srcRes = client.newCall(GET(SERVER_URL[type] + SOURCES_URL[type] + id))
            .execute()
            .body.string()

        val data = json.decodeFromString<SourceResponseDto>(srcRes)

        return data.sources.map { source ->
            val encoded = source.file

            val m3u8: String = if (!data.encrypted || ".m3u8" in encoded) {
                encoded
            } else {
                // tryDecrypting(ciphered, keyType)
                decryptWithNewKey(encoded)
            }

            VideoDto(m3u8, data.tracks)
        }
    }

    private fun decryptWithNewKey(ciphered: String): String {
        val newKey = requestNewKey()
        return decryptOpenSSL(ciphered, newKey).also {
            Log.i("RapidCloudExtractor", "Decrypted URL with new key: $it")
        }
    }

    private fun requestNewKey(): String =
        client.newCall(GET("https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json"))
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Failed to fetch keys.json")
                val jsonStr = response.body.string()
                if (jsonStr.isEmpty()) throw IllegalStateException("keys.json is empty")
                val key = json.decodeFromString<Map<String, String>>(jsonStr)["mega"]
                    ?: throw IllegalStateException("Rapid key not found in keys.json")
                Log.i("RapidCloudExtractor", "Using Rapid Key: $key")
                key
            }

    private fun decryptOpenSSL(encBase64: String, password: String): String {
        try {
            val data = Base64.decode(encBase64, Base64.NO_WRAP) // Base64.DEFAULT or Base64.NO_WRAP
            require(data.copyOfRange(0, 8).contentEquals("Salted__".toByteArray()))
            val salt = data.copyOfRange(8, 16)
            val (key, iv) = opensslKeyIv(password.toByteArray(), salt)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decrypted = cipher.doFinal(data.copyOfRange(16, data.size))
            return String(decrypted)
        } catch (e: Exception) {
            Log.e("DecryptOpenSSL", "Decryption failed: ${e.message}")
            throw RuntimeException("Decryption failed: ${e.message}", e)
        }
    }

    private fun opensslKeyIv(password: ByteArray, salt: ByteArray, keyLen: Int = 32, ivLen: Int = 16): Pair<ByteArray, ByteArray> {
        var d = ByteArray(0)
        var d_i = ByteArray(0)
        while (d.size < keyLen + ivLen) {
            val md = MessageDigest.getInstance("MD5")
            d_i = md.digest(d_i + password + salt)
            d += d_i
        }
        return Pair(d.copyOfRange(0, keyLen), d.copyOfRange(keyLen, keyLen + ivLen))
    }

    @Serializable
    data class VideoDto(
        val m3u8: String = "",
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class SourceResponseDto(
        val sources: List<SourceDto>,
        val encrypted: Boolean = true,
        val tracks: List<TrackDto>? = null,
    )

    @Serializable
    data class SourceDto(
        val file: String,
        val type: String,   // 'hls'
    )

    @Serializable
    data class TrackDto(val file: String, val kind: String, val label: String = "")
}
