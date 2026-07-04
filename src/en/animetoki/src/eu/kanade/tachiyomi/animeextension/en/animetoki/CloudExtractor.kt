package eu.kanade.tachiyomi.animeextension.en.animetoki

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class CloudExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getEpisodesFromCloudUrl(cloudUrl: String): List<SEpisode> {
        val (baseUrl, segments) = splitUrl(cloudUrl)
        val initialUrl = urlToBase64(baseUrl, segments)
        val episodes = mutableListOf<SEpisode>()
        traverseFolder(baseUrl, initialUrl, episodes, floatArrayOf(1f))
        return episodes
    }

    private fun encode2Base64(s: String): String = Base64.encodeToString(URLDecoder.decode(s, "UTF-8").toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)

    private fun splitUrl(url: String): Pair<String, List<String>> {
        val parts = url.split("://")
        if (parts.size < 2) return Pair(url, emptyList())
        val protocol = parts[0]
        val rest = parts[1]

        val segments = rest.split("/").filter { it.isNotEmpty() }
        val baseUrl = "$protocol://${segments.firstOrNull() ?: ""}"
        val pathSegments = if (segments.size > 1) segments.drop(1) else emptyList()

        return Pair(baseUrl, pathSegments)
    }

    private fun urlToBase64(baseUrl: String, segments: List<String>): String {
        val encodedSegments = segments.joinToString("/") { encode2Base64(it) }
        return if (encodedSegments.isEmpty()) {
            "$baseUrl/"
        } else {
            "$baseUrl/$encodedSegments/"
        }
    }

    private fun traverseFolder(baseUrl: String, folderUrl: String, episodes: MutableList<SEpisode>, epCounter: FloatArray) {
        var responseBody: String? = null
        try {
            for (i in 1..3) {
                try {
                    val response = client.newCall(POST(folderUrl, headers)).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        break
                    }
                } catch (e: Exception) {
                    if (i == 3) throw e
                    Thread.sleep(1000)
                }
            }
            if (responseBody.isNullOrEmpty()) {
                Log.e("AnimeToki", "Failed to fetch cloud folder after 3 retries: $folderUrl")
                return
            }

            val responseObj = json.decodeFromString<CloudFileResponse>(responseBody)
            val nodeIndex = responseObj.nodeIndex?.jsonPrimitive?.content ?: ""

            val sortedFiles = responseObj.files.sortedWith(
                Comparator { a, b ->
                    naturalCompare(a.name, b.name)
                },
            )

            for (file in sortedFiles) {
                if (file.actualMimeType.contains("video", ignoreCase = true)) {
                    val downloadUrl = "$baseUrl/?a=download&id=${file.id}&name=${encode2Base64(file.name)}&n=$nodeIndex"
                    val episode = SEpisode.create().apply {
                        this.name = file.name
                        this.url = downloadUrl
                        this.episode_number = epCounter[0]++
                    }
                    episodes.add(episode)
                } else if (file.actualMimeType.contains("folder", ignoreCase = true)) {
                    val nextUrl = if (folderUrl.endsWith("/")) {
                        folderUrl + encode2Base64(file.name) + "/"
                    } else {
                        folderUrl + "/" + encode2Base64(file.name) + "/"
                    }
                    traverseFolder(baseUrl, nextUrl, episodes, epCounter)
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeToki", "Error parsing cloud folder: $folderUrl", e)
            Log.e("AnimeToki", "Response Body: $responseBody")
        }
    }

    private fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val charA = a[ia]
            val charB = b[ib]
            if (charA.isDigit() && charB.isDigit()) {
                val startA = ia
                while (ia < a.length && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < b.length && b[ib].isDigit()) ib++

                val numA = a.substring(startA, ia).toLongOrNull() ?: 0L
                val numB = b.substring(startB, ib).toLongOrNull() ?: 0L
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp

                val lenCmp = (ia - startA).compareTo(ib - startB)
                if (lenCmp != 0) return lenCmp
            } else {
                val cmp = charA.lowercaseChar().compareTo(charB.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }
        return a.length.compareTo(b.length)
    }
}
