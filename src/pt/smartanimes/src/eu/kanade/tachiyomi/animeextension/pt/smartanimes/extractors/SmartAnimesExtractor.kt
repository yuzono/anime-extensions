package eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors

import android.util.Log
import aniyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

class SmartAnimesExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val gdriveExtractor by lazy { GoogleDrivePlayerExtractor(client, headers) }
    private val sendNowExtractor by lazy { SendNowExtractor(client, headers) }

    suspend fun videosFromUrl(url: String, name: String): List<Video> {
        val httpUrl = url.toHttpUrlOrNull()
        Log.d(tag, "videosFromUrl: ${httpUrl?.host ?: "unknown"}")

        val response = client.newCall(GET(url, headers)).execute()
        val newUrl = response.asJsoup()
            .selectFirst(".btn--principal")
            ?.attr("href")
            ?: return emptyList()

        val token = noRedirectClient.newCall(GET(newUrl, headers)).execute()
            .headers["location"]
            ?.toHttpUrlOrNull()
            ?.queryParameter("utm_content")
            ?: return emptyList()

        val apiHost = response.request.url.host

        val json = client.newCall(GET("https://$apiHost/api/end?code=$token", headers)).execute()
            .parseAs<ApiResponse>()

        val rateLimitClient = client.newBuilder().rateLimit(1, interval = 5.seconds).build()

        val finalUrl = rateLimitClient.newCall(GET(json.url, headers)).execute().asJsoup()
            .selectFirst(".btn--principal")
            ?.attr("href")
            ?: return emptyList()

        val sourceUrl = rateLimitClient.newCall(GET(finalUrl, headers)).execute()
            .bodyString()
            .substringAfter("location.href=\"")
            .substringBefore("\"")
            .replace("\\", "")

        Log.d(tag, "Resolved source host: ${sourceUrl.toHttpUrlOrNull()?.host ?: "unknown"}")

        return when {
            "drive.google.com" in sourceUrl -> {
                Log.d(tag, "Delegating to GoogleDrivePlayerExtractor")
                gdriveExtractor.videosFromUrl(sourceUrl)
            }
            "send.now" in sourceUrl -> {
                Log.d(tag, "Delegating to SendNowExtractor")
                sendNowExtractor.videosFromUrl(sourceUrl, name)
            }

            else -> {
                Log.e(tag, "Unknown source host: $sourceUrl")
                emptyList()
            }
        }
    }

    @Serializable
    class ApiResponse(
        val url: String,
    )
}
