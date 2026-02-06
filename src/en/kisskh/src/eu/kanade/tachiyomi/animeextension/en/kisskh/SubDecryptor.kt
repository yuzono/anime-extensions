package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.listOf
import kotlin.getValue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SubDecryptor(private val client: OkHttpClient, private val headers: Headers, private val baseurl: String) {
    suspend fun getSubtitles(subUrl: String, subLang: String): Track {
        val subHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/plain, */*")
            add("Origin", baseurl)
            add("Referer", "$baseurl/")
        }.build()

        val subtitleData = client.newCall(
            GET(subUrl, subHeaders),
        ).awaitSuccess().use { it.body.string() }

        val chunks = subtitleData.split(CHUNK_REGEX)
            .filter(String::isNotBlank)
            .map(String::trim)

        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.split("\n")
            val text = parts.slice(1 until parts.size)
            val d = text.joinToString("\n") { runCatching { decrypt(it) }.getOrDefault("") }

            listOf(index + 1, parts.first(), d).joinToString("\n")
        }.joinToString("\n\n")

        val file = File.createTempFile("subs", "srt")
            .also(File::deleteOnExit)

        file.writeText(decrypted)
        val uri = Uri.fromFile(file)

        return Track(uri.toString(), subLang)
    }

    companion object {
        private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

        private const val KEY = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"

        private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    }

    private val keyIvPairs by lazy {
        listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decrypt(encryptedB64: String): String {
        if (encryptedB64.isBlank()) return ""
        val encryptedBytes = Base64.decode(encryptedB64) // Decode Base64 input

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
            } catch (ex: Exception) {
                Log.e("KissKH:SubDecryptor", "Decryption attempt failed with key/IV pair. Error: ${ex.message}", ex)
            }
        }
        throw IOException("Decryption failed: All keys/IVs failed")
    }

    private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    private fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
        forEachIndexed { index, value ->
            bytes[index * 4] = (value shr 24).toByte()
            bytes[index * 4 + 1] = (value shr 16).toByte()
            bytes[index * 4 + 2] = (value shr 8).toByte()
            bytes[index * 4 + 3] = value.toByte()
        }
    }
}
