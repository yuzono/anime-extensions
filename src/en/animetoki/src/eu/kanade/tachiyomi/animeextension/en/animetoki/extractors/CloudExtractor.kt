package eu.kanade.tachiyomi.animeextension.en.animetoki.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animeextension.en.animetoki.CloudFileResponse
import eu.kanade.tachiyomi.animeextension.en.animetoki.naturalCompare
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class CloudExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val initializedHosts = ConcurrentHashMap.newKeySet<String>()

    // Single drive folder listings can take ~1min server-side; fetch subfolders
    // concurrently instead of one POST at a time.
    private val fetchSemaphore = Semaphore(5)

    suspend fun getEpisodesFromCloudUrl(cloudUrl: String, prefix: String = ""): List<SEpisode> {
        val (baseUrl, segments) = splitUrl(cloudUrl)
        val initialUrl = urlToBase64(baseUrl, segments)

        // drive.animetoki.com requires a session cookie which is set on GET request to root domain
        if (baseUrl.contains("drive.animetoki.com") && initializedHosts.add(baseUrl)) {
            try {
                client.get("$baseUrl/", headers).close()
            } catch (e: Exception) {
                Log.e("AnimeToki", "Failed preliminary GET for session cookie: $baseUrl/", e)
            }
        }

        return traverseFolder(baseUrl, initialUrl, prefix)
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .mapIndexed { index, episode -> episode.apply { episode_number = (index + 1).toFloat() } }
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

    private fun encodeSegment(s: String): String {
        val decoded = URLDecoder.decode(s, "UTF-8")
        return try {
            val bytes = Base64.decode(decoded, Base64.DEFAULT)
            val reencoded = Base64.encodeToString(bytes, Base64.DEFAULT or Base64.NO_WRAP)
            if (reencoded.trimEnd('=') == decoded.trimEnd('=')) {
                decoded
            } else {
                Base64.encodeToString(decoded.toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Base64.encodeToString(decoded.toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
        }
    }

    private fun urlToBase64(baseUrl: String, segments: List<String>): String {
        val encodedSegments = segments.joinToString("/") { encodeSegment(it) }
        return if (encodedSegments.isEmpty()) {
            "$baseUrl/"
        } else {
            "$baseUrl/$encodedSegments/"
        }
    }

    private suspend fun traverseFolder(baseUrl: String, folderUrl: String, prefix: String = ""): List<SEpisode> = coroutineScope {
        val responseBody = fetchFolderWithRetry(baseUrl, folderUrl) ?: return@coroutineScope emptyList()

        val responseObj = try {
            responseBody.parseAs<CloudFileResponse>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("AnimeToki", "Error parsing cloud folder: $folderUrl", e)
            return@coroutineScope emptyList()
        }
        val nodeIndex = responseObj.nodeIndex?.jsonPrimitive?.content ?: ""

        val sortedFiles = responseObj.files.sortedWith { a, b -> naturalCompare(a.name, b.name) }

        val videos = sortedFiles
            .filter { it.actualMimeType.contains("video", ignoreCase = true) }
            .map { file ->
                SEpisode.create().apply {
                    val cleanName = file.name.replace("[AnimeToki] ", "", ignoreCase = true)
                        .replace("[AnimeSakura] ", "", ignoreCase = true).trim()
                    this.name = cleanName
                    if (prefix.isNotBlank()) {
                        this.scanlator = prefix
                    }
                    this.url = "$baseUrl/?a=download&id=${file.id}&name=${encode2Base64(file.name)}&n=$nodeIndex"
                }
            }

        val subfolders = sortedFiles
            .filter { it.actualMimeType.contains("folder", ignoreCase = true) }
            .map { file ->
                async {
                    try {
                        val nextUrl = if (folderUrl.endsWith("/")) {
                            folderUrl + encode2Base64(file.name) + "/"
                        } else {
                            folderUrl + "/" + encode2Base64(file.name) + "/"
                        }
                        val newPrefix = if (prefix.isNotBlank()) "$prefix / ${file.name}" else file.name
                        traverseFolder(baseUrl, nextUrl, newPrefix)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("AnimeToki", "Error traversing cloud folder: ${file.name}", e)
                        emptyList()
                    }
                }
            }
            .awaitAll()
            .flatten()

        videos + subfolders
    }

    private suspend fun fetchFolderWithRetry(baseUrl: String, folderUrl: String): String? {
        repeat(3) { attempt ->
            try {
                val body = fetchSemaphore.withPermit {
                    client.post(folderUrl, headers).use { response ->
                        if (!response.isSuccessful) return@use null
                        val responseBody = response.body.string()
                        if (responseBody.trimStart().startsWith("{")) return@use responseBody
                        if (attempt < 2 && baseUrl.contains("drive.animetoki.com")) {
                            client.get("$baseUrl/", headers).close()
                        }
                        null
                    }
                }
                if (body != null) return body
            } catch (e: Exception) {
                if (e is CancellationException || attempt == 2) throw e
                delay(1000.milliseconds)
            }
        }
        Log.e("AnimeToki", "Failed to fetch cloud folder after 3 retries: $folderUrl")
        return null
    }
}
