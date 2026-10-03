package eu.kanade.tachiyomi.animeextension.en.reanime

import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.flixCloudUrl
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.get
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object FlixCloudDdl {

    class DdlData(
        val base: String,
        val fileId: String,
        val token: String,
        val resolution: String?,
    )

    private val PROGRESS_STATUS_REGEX = Regex(""""status":\s*"(\w+)"""")
    private val FILE_ID_REGEX = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    private val JWT_REGEX = Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""")
    private val FETCH_BASE_REGEX = Regex("""https://fetch\d*\.flixcloud\.cc""")
    private val RESOLUTION_REGEX = Regex("""(\d{3,4}p)""")
    private const val PROGRESS_TIMEOUT_SECONDS = 5L

    /**
     * Quickly fetches the download metadata so we can display the accurate
     * resolution (e.g. "1080p MKV") in the video list instantly without blocking.
     */
    suspend fun fetchMetadata(client: OkHttpClient, headers: Headers, aid: String): DdlData? {
        return try {
            val dataBody = client.get("$flixCloudUrl/d/$aid/__data.json", headers).use { res ->
                if (!res.isSuccessful) return null
                res.body.string()
            }

            val fileId = FILE_ID_REGEX.find(dataBody)?.value ?: return null
            val token = JWT_REGEX.find(dataBody)?.value ?: return null
            val base = FETCH_BASE_REGEX.find(dataBody)?.value ?: flixCloudUrl
            val resolution = RESOLUTION_REGEX.find(dataBody)?.groupValues?.get(1)

            DdlData(base, fileId, token, resolution)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Polls the SSE progress endpoint to wait for the file to be ready,
     * then returns the final direct download URL.
     */
    suspend fun resolveUrl(client: OkHttpClient, headers: Headers, data: DdlData): String? {
        val progressClient = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(PROGRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout((PROGRESS_TIMEOUT_SECONDS + 2L), TimeUnit.SECONDS)
            .build()

        var ready = false
        var attempts = 0
        while (!ready && attempts < 2) {
            ready = pollDownloadReady(progressClient, headers, data.base, data.fileId, data.token)
            attempts++
        }

        return if (ready) "${data.base}/download/${data.fileId}?token=${data.token}" else null
    }

    private suspend fun pollDownloadReady(client: OkHttpClient, headers: Headers, base: String, fileId: String, token: String): Boolean {
        return try {
            client.newCall(
                Request.Builder().url("$base/download/$fileId/progress?token=$token").headers(headers).build(),
            ).awaitSuccess().use { res ->
                if (!res.isSuccessful) return false

                // The progress endpoint is an SSE stream that stays open after
                // sending an update; read line-by-line instead of buffering it all.
                val source = res.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when (PROGRESS_STATUS_REGEX.find(line)?.groupValues?.get(1)) {
                        "ready" -> return true
                        "failed" -> return false
                    }
                }
                false
            }
        } catch (_: Exception) {
            // Timeout or connection failure while the file was still building
            false
        }
    }
}
