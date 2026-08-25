package eu.kanade.tachiyomi.animeextension.en.reanime

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

private val PROGRESS_STATUS_REGEX = Regex(""""status":\s*"(\w+)"""")

/**
 * Reads a FlixCloud download-progress SSE stream until a terminal status
 * arrives, the stream ends, or the request's timeouts fire. Never throws
 * and never blocks indefinitely: the caller is expected to pass a client
 * with hard read/call timeouts, since non-terminal streams stay open.
 */
internal fun flixcloudProgressIsReady(
    client: OkHttpClient,
    progressUrl: String,
    headers: Headers,
): Boolean {
    return try {
        client.newCall(Request.Builder().url(progressUrl).headers(headers).build())
            .execute()
            .use { res ->
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
