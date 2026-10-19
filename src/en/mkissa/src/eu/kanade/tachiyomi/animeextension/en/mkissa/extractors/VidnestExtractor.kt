package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.post
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

// Vidnest (XFileSharing). The embed page is a click-to-play form that POSTs to /dl; the reply is
// the JW Player page whose `sources` list holds signed MP4 links.
class VidnestExtractor(private val client: OkHttpClient, private val headers: Headers) {

    suspend fun videosFromUrl(url: String, embedderUrl: String): List<Video> {
        val embedUrl = url.toHttpUrl()
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val fileCode = embedUrl.pathSegments.lastOrNull { it.isNotEmpty() }
            ?.substringAfterLast("-")
            ?.removeSuffix(".html")
            ?: return emptyList()

        val body = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", fileCode)
            .add("auto", "1")
            .add("referer", embedderUrl)
            .build()

        val postHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()

        val page = client.post("$origin/dl", postHeaders, body).bodyString()
        val sources = SOURCES_REGEX.find(page)?.groupValues?.get(1) ?: return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .build()

        return SOURCE_REGEX.findAll(sources).map { match ->
            val file = match.groupValues[1]
            val label = match.groupValues[2]
            Video(
                videoUrl = if (file.startsWith("http")) file else origin + file,
                videoTitle = "Vidnest - ${label.ifEmpty { "Video" }}",
                resolution = RESOLUTION_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull(),
                headers = videoHeaders,
            )
        }.toList()
    }

    companion object {
        private val SOURCES_REGEX = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        private val SOURCE_REGEX = Regex("""file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?""")
        private val RESOLUTION_REGEX = Regex("""\d+x(\d+)""")
    }
}
