package eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors

import android.util.Log
import aniyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SmartAnimesExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val gdriveExtractor by lazy { GoogleDrivePlayerExtractor(client, headers) }
    private val sendNowExtractor by lazy { SendNowExtractor(client, headers) }

    suspend fun videosFromUrl(url: String, name: String): List<Video> {
        Log.d(tag, "videosFromUrl: ${url.toHttpUrlOrNull()?.host ?: "unknown"}")
        val content = client.newCall(GET(url, headers)).awaitSuccess().bodyString()

        val item = content.substringAfter("var item = ", "")
            .substringBefore(";")
            .parseAs<ItemDto>()
        Log.d(tag, "Parsed item (id=${item.id}, post=${item.post})")

        val options = content.substringAfter("var options = ", "")
            .substringBefore(";")
            .parseAs<OptionsDto>()
        Log.d(tag, "Parsed options (ajaxurl=${options.soralink_ajaxurl})")

        val newHeaders = headers.newBuilder()
            .set("Referer", item.post)
            .build()

        val formBody = FormBody.Builder()
            .add("token", item.token)
            .add("id", item.id.toString())
            .add("time", item.time.toString())
            .add("post", item.post)
            .add("redirect", item.redirect)
            .add("cacha", item.cacha)
            .add("new", "false")
            .add("link", item.link)
            .add("action", options.soralink_z)
            .build()

        val sourceUrl =
            noRedirectClient.newCall(POST(options.soralink_ajaxurl, newHeaders, formBody))
                .await().use { it.header("location") }
                ?: run {
                    Log.e(tag, "No redirect location from soralink POST")
                    return emptyList()
                }
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
    data class ItemDto(
        val token: String,
        val id: Int,
        val time: Int,
        val post: String,
        val redirect: String,
        val cacha: String,
        val new: Boolean,
        val link: String,
    )

    @Serializable
    data class OptionsDto(
        val soralink_z: String,
        val soralink_ajaxurl: String,
    )
}
