package eu.kanade.tachiyomi.animeextension.en.animeverse

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun extractMegaplayId(client: OkHttpClient, pageUrl: String, baseUrl: String): String? {
    val pageHeaders = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "iframe")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "cross-site")
        .build()

    val pageBody = client.newCall(GET(pageUrl, pageHeaders)).execute().use { it.bodyString() }

    return Regex("""data-id\s*=\s*["']([^"']+)["']""").find(pageBody)?.groupValues?.get(1)
}

private fun extractM3u8FromSources(sources: JsonElement): String? = when (sources) {
    is JsonObject -> sources["file"]?.jsonPrimitive?.contentOrNull
    is JsonArray -> sources.firstOrNull()?.let {
        when (it) {
            is JsonObject -> it["file"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> it.contentOrNull
            else -> null
        }
    }
    is JsonPrimitive -> sources.contentOrNull
}

fun fetchMegaplayVideos(
    client: OkHttpClient,
    playlistUtils: PlaylistUtils,
    id: String,
    referer: String,
    kind: String,
    serverName: String,
): List<Video> {
    val videos = mutableListOf<Video>()
    try {
        val base = extractBaseUrl(referer)
        val sourcesUrl = "$base/stream/getSources?id=$id&id=$id"

        val headers = Headers.Builder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", referer)
            .add("Origin", base)
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-origin")
            .build()

        val obj = client.newCall(GET(sourcesUrl, headers)).execute().use { res ->
            if (!res.isSuccessful) return videos
            res.bodyString().parseAs<JsonElement>(json) as? JsonObject
        } ?: return videos

        val masterUrl = extractM3u8FromSources(obj["sources"] ?: return videos)
            ?.takeIf { it.startsWith("http") }
            ?: return videos

        val subtitleTracks = (obj["tracks"] as? JsonArray)
            ?.mapNotNull { trackEl ->
                val trackObj = trackEl as? JsonObject ?: return@mapNotNull null
                val file = trackObj.string("file") ?: return@mapNotNull null
                val label = trackObj.string("label") ?: ""
                val trackKind = trackObj.string("kind") ?: ""
                if (trackKind.equals("captions", true)) Track(file, label) else null
            } ?: emptyList()

        videos.addAll(
            playlistUtils.extractFromHls(
                masterUrl,
                videoNameGen = { q -> "$kind - $serverName - ${cleanQuality(q)}" },
                subtitleList = subtitleTracks,
                referer = "$base/",
            ),
        )
    } catch (_: Exception) {
        // Network error, skip this source
    }
    return videos
}
