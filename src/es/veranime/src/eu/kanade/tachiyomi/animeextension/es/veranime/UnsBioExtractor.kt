package eu.kanade.tachiyomi.animeextension.es.veranime

import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.decodeHex
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class UnsBioExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return try {
            val swarmId = url.substringAfter("#")
            val iframeUrl = "https://animeav1.uns.bio/#$swarmId"

            val iframeResponse = client.newCall(
                Request.Builder()
                    .url(iframeUrl)
                    .headers(headers)
                    .header("Referer", "https://animeav1.com/")
                    .build(),
            ).execute().use { it.body.string() }

            val jsPathRegex = """src="(/assets/index-[A-Za-z0-9_-]+\.js)"""".toRegex()
            val jsPath = jsPathRegex.find(iframeResponse)?.groupValues?.get(1) ?: return emptyList()
            val jsUrl = "https://animeav1.uns.bio$jsPath"

            val jsResponse = client.newCall(
                Request.Builder()
                    .url(jsUrl)
                    .headers(headers)
                    .build(),
            ).execute().use { it.body.string() }

            // Extract array in kl()
            val arrRegex = """function\s+kl\s*\(\s*\)\s*\{\s*const\s+n\s*=\s*\[(.*?)\]""".toRegex()
            val arrContent = arrRegex.find(jsResponse)?.groupValues?.get(1) ?: return emptyList()

            // Parse strings (handles single/double/backticks)
            val strRegex = """"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|`((?:[^`\\]|\\.)*)`""".toRegex()
            val strings = strRegex.findAll(arrContent).map { match ->
                val raw = match.groupValues.firstOrNull { it.isNotEmpty() }?.drop(1)?.dropLast(1) ?: ""
                // Simple unicode unescape
                raw.replace("""\\u([0-9a-fA-F]{4})""".toRegex()) {
                    it.groupValues[1].toInt(16).toChar().toString()
                }
            }.toList()

            if (strings.size < 500) return emptyList()

            // Unshuffle the array dynamically
            val arr = ArrayList(strings)
            var unshuffled: List<String>? = null

            fun parseInt(s: String): Int? {
                val match = """^\s*[-+]?\d+""".toRegex().find(s)
                return match?.value?.trim()?.toIntOrNull()
            }

            for (step in 0 until arr.size) {
                try {
                    val p189 = parseInt(arr[189 - 136])
                    val p635 = parseInt(arr[635 - 136])
                    val p236 = parseInt(arr[236 - 136])
                    val p233 = parseInt(arr[233 - 136])
                    val p325 = parseInt(arr[325 - 136])
                    val p370 = parseInt(arr[370 - 136])
                    val p446 = parseInt(arr[446 - 136])
                    val p489 = parseInt(arr[489 - 136])
                    val p349 = parseInt(arr[349 - 136])
                    val p166 = parseInt(arr[166 - 136])
                    val p313 = parseInt(arr[313 - 136])

                    if (p189 != null && p635 != null && p236 != null && p233 != null && p325 != null && p370 != null && p446 != null && p489 != null && p349 != null && p166 != null && p313 != null) {
                        val value = (p189.toDouble() / 1.0) * (-p635.toDouble() / 2.0) +
                            (p236.toDouble() / 3.0) +
                            (p233.toDouble() / 4.0) * (-p325.toDouble() / 5.0) +
                            (p370.toDouble() / 6.0) +
                            (-p446.toDouble() / 7.0) +
                            (-p489.toDouble() / 8.0) * (p349.toDouble() / 9.0) +
                            (p166.toDouble() / 10.0) * (p313.toDouble() / 11.0)

                        if (Math.abs(value - 995855.0) < 0.001) {
                            unshuffled = ArrayList(arr)
                            break
                        }
                    }
                } catch (e: Exception) {}

                // Shift array left
                val first = arr.removeAt(0)
                arr.add(first)
            }

            if (unshuffled == null) return emptyList()

            // Derive Key
            var nKey = ""
            val bKey = "7519".toList() // ord("ᵟ") = 7519
            for (char in bKey) {
                nKey += ("10" + char).toInt().toChar()
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

            val keyBytes = nKey.toByteArray(Charsets.UTF_8)

            // Derive IV
            val vIv = "https:"
            val pIv = vIv + "//"
            val oIv = "#$swarmId"

            val gIv = vIv.length * pIv.length // 6 * 8 = 48
            var bIv = ""
            for (Ie in 1..9) {
                bIv += (Ie + gIv).toChar()
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

            // Fetch video payload
            val apiUrl = "https://animeav1.uns.bio/api/v1/video?id=$swarmId"
            val apiResponse = client.newCall(
                Request.Builder()
                    .url(apiUrl)
                    .headers(headers)
                    .header("Referer", "https://animeav1.com/")
                    .build(),
            ).execute().use { it.body.string() }

            // Decrypt video payload
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val ciphertext = apiResponse.decodeHex()
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

            streams
        } catch (_: Exception) {
            emptyList()
        }
    }
}
