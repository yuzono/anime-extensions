package eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.decodeHexToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class MVidooExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val body = client.newCall(GET(url)).awaitSuccess().bodyString()

        val url = Regex("""\{var\s?.*?\s?=\s?(\[.*?])""").find(body)?.groupValues?.get(1)?.let {
            Json.decodeFromString<List<String>>(it.replace("\\x", ""))
                .joinToString("") { t -> t.decodeHexToString() }.reversed()
                .substringAfter("src=\"").substringBefore("\"")
        } ?: return emptyList()

        return listOf(
            Video(url, "${prefix}MVidoo", url),
        )
    }
}
