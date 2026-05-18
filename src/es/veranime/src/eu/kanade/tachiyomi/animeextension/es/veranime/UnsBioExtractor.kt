package eu.kanade.tachiyomi.animeextension.es.veranime

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class UnsBioExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private val jsPathRegex by lazy { Regex("""src="(/assets/index-[A-Za-z0-9_-]+\.js)"""") }
        private val arrRegex by lazy { Regex("""function\s+kl\s*\(\s*\)\s*\{\s*const\s+n\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL) }
        private val strRegex by lazy { Regex(""""((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|`((?:[^`\\]|\\.)*)`""") }
        private val unicodeRegex by lazy { Regex("""\\u([0-9a-fA-F]{4})""") }
        private val integerRegex by lazy { Regex("""^\s*[-+]?\d+""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val swarmId = url.substringAfter("#")
        val iframeUrl = "https://animeav1.uns.bio/#$swarmId"

        val iframeBody = client.newCall(
            Request.Builder()
                .url(iframeUrl)
                .headers(headers)
                .header("Referer", "https://animeav1.com/")
                .build(),
        ).awaitSuccess().bodyString()

        val jsPath = jsPathRegex.find(iframeBody)?.groupValues?.getOrNull(1) ?: return emptyList()
        val jsUrl = "https://animeav1.uns.bio$jsPath"

        val jsBody = client.newCall(
            Request.Builder()
                .url(jsUrl)
                .headers(headers)
                .build(),
        ).awaitSuccess().bodyString()

        // Extract array in kl()
        val arrContent = arrRegex.find(jsBody)?.groupValues?.getOrNull(1) ?: return emptyList()

        // Parse strings (handles single/double/backticks)
        val strings = strRegex.findAll(arrContent).map { match ->
            val raw = match.groupValues.firstOrNull { it.isNotEmpty() }?.drop(1)?.dropLast(1) ?: ""
            // Simple unicode unescape
            raw.replace(unicodeRegex) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
        }.toList()

        if (strings.size < 500) return emptyList()

        // Parse leading integers once and simulate left-rotation using modular indexing.
        val parsedInts = strings.map { s ->
            integerRegex.find(s)?.value?.trim()?.toIntOrNull()
        }

        val size = parsedInts.size
        var foundValidRotation = false

        loop@ for (step in 0 until size) {
            fun valueAt(index: Int): Int? = parsedInts[(index - 136 + step) % size]

            val p189 = valueAt(189)
            val p635 = valueAt(635)
            val p236 = valueAt(236)
            val p233 = valueAt(233)
            val p325 = valueAt(325)
            val p370 = valueAt(370)
            val p446 = valueAt(446)
            val p489 = valueAt(489)
            val p349 = valueAt(349)
            val p166 = valueAt(166)
            val p313 = valueAt(313)

            if (p189 != null && p635 != null && p236 != null && p233 != null && p325 != null && p370 != null && p446 != null && p489 != null && p349 != null && p166 != null && p313 != null) {
                val value = (p189.toDouble() / 1.0) * (-p635.toDouble() / 2.0) +
                    (p236.toDouble() / 3.0) +
                    (p233.toDouble() / 4.0) * (-p325.toDouble() / 5.0) +
                    (p370.toDouble() / 6.0) +
                    (-p446.toDouble() / 7.0) +
                    (-p489.toDouble() / 8.0) * (p349.toDouble() / 9.0) +
                    (p166.toDouble() / 10.0) * (p313.toDouble() / 11.0)

                if (abs(value - 995855.0) < 0.001) {
                    foundValidRotation = true
                    break@loop
                }
            }
        }

        if (!foundValidRotation) return emptyList()

        val keyBytes = "kiemtienmua911ca".toByteArray()
        val ivBytes = "1234567890oiuytr".toByteArray()

        // Fetch video payload
        val apiUrl = "https://animeav1.uns.bio/api/v1/video?id=$swarmId"
        val apiBody = client.newCall(
            Request.Builder()
                .url(apiUrl)
                .headers(headers)
                .header("Referer", "https://animeav1.com/")
                .build(),
        ).awaitSuccess().bodyString()

        // Decrypt video payload
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val ciphertext = ByteArray(apiBody.length / 2) { i ->
            apiBody.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val decryptedBytes = cipher.doFinal(ciphertext)
        val decrypted = String(decryptedBytes, Charsets.UTF_8)

        val json = JSONObject(decrypted)
        val streams = mutableListOf<Video>()

        // Try to extract Tiktok
        if (json.has("hlsVideoTiktok") && !json.isNull("hlsVideoTiktok")) {
            val ttUrl = json.getString("hlsVideoTiktok")
            if (ttUrl.isNotEmpty()) {
                val vParam = "1766826492"
                val absoluteUrl = "https://animeav1.uns.bio$ttUrl?v=$vParam"
                streams.add(Video(absoluteUrl, "$prefix Tiktok HLS", absoluteUrl, headers))
            }
        }

        // Try to extract Cloudflare
        if (json.has("cf") && !json.isNull("cf")) {
            val cfUrl = json.getString("cf")
            if (cfUrl.isNotEmpty()) {
                streams.add(Video(cfUrl, "$prefix Cloudflare HLS", cfUrl, headers))
            }
        }

        // Try to extract In-House
        if (json.has("source") && !json.isNull("source")) {
            val sourceUrl = json.getString("source")
            if (sourceUrl.isNotEmpty()) {
                streams.add(Video(sourceUrl, "$prefix In-House HLS", sourceUrl, headers))
            }
        }

        return streams
    }

    /**
     * keyBytes is derived from "kiemtienmua911ca" (the last 16 chars of the shuffled array).
     *
     * Keep this for reference.
     */
    @Suppress("unused")
    private fun keyBytes(): ByteArray {
        // Derive Key
        var nKey = ""
        val bKey = "7519".toList() // ord("ᵟ") = 7519
        for (char in bKey) {
            nKey += ("10$char").toInt().toChar()
        }
        nKey += 't' // ord("https:"[1]) = 't'
        nKey += nKey.substring(1, 3)
        nKey += 'n'
        nKey += 'm'
        nKey += 'u'

        nKey += 97.toChar() // "a" (97)
        nKey += 57.toChar() // "9" (57)

        nKey += 49.toChar() // "1" (49)
        nKey += 49.toChar() // "1" (49)

        nKey += 99.toChar() // "c" (99)
        nKey += 97.toChar() // "a" (97)
        return nKey.toByteArray(Charsets.UTF_8)
    }

    /**
     * ivBytes is derived from a complex combination of the shuffled array and some hardcoded values.
     *
     * Keep this for reference.
     */
    @Suppress("unused")
    private fun ivBytes(): ByteArray {
        // Derive IV
        val vIv = "https:"
        val pIv = "$vIv//"
        val oIv = "#"

        val gIv = vIv.length * pIv.length // 6 * 8 = 48
        var bIv = ""
        for (iE in 1..9) {
            bIv += (iE + gIv).toChar()
        }
        val oeIv = "111"
        val yeIv = oeIv.length * oIv[0].code // 3 * 35 = 105
        val heIv = oeIv.toInt() * 1 + vIv.length // 117
        val kIv = heIv + 4 // 121
        val seIv = vIv[1].code // 116
        val peIv = seIv * 1 - 2 // 114

        bIv += gIv.toChar()
        bIv += oeIv.toInt().toChar()
        bIv += yeIv.toChar()
        bIv += heIv.toChar()
        bIv += kIv.toChar()
        bIv += seIv.toChar()
        bIv += peIv.toChar()

        val ivBytes = ByteArray(16)
        for (i in 0 until 16) {
            ivBytes[i] = (bIv[i].code and 0xFF).toByte()
        }
        return ivBytes
    }
}
