package eu.kanade.tachiyomi.animeextension.en.animekhor.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidaraExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val filecode = FILECODE_REGEX.find(url)?.groupValues?.get(1) ?: return emptyList()

        val defaultOrigin = runCatching {
            val httpUrl = url.toHttpUrl()
            "${httpUrl.scheme}://${httpUrl.host}"
        }.getOrDefault(if (url.contains("vidara.to")) "https://vidara.to" else "https://vidvara.fit")

        val origin = runCatching {
            val doc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
            val script = doc.selectFirst("script:containsData(MIRROR_ORIGIN)")?.data().orEmpty()
            ORIGIN_REGEX.find(script)?.groupValues?.get(1)
        }.getOrNull() ?: defaultOrigin

        val apiUrl = "$origin/api/stream"
        val requestBody = VidaraRequest(filecode, "web").toJsonRequestBody()
        val requestHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()

        val response = client.newCall(POST(apiUrl, requestHeaders, requestBody)).awaitSuccess()
            .parseAs<VidaraResponse>()

        val streamingUrl = response.streamingUrl ?: return emptyList()
        val subtitles = response.subtitles?.mapNotNull {
            if (!it.filePath.isNullOrBlank() && !it.language.isNullOrBlank()) {
                Track(it.filePath, it.language)
            } else {
                null
            }
        } ?: emptyList()

        return playlistUtils.extractFromHls(
            streamingUrl,
            referer = "$origin/",
            subtitleList = subtitles,
            videoNameGen = { "$prefix$it" },
        )
    }

    @Serializable
    private data class VidaraRequest(
        val filecode: String,
        val device: String,
    )

    @Serializable
    private data class VidaraResponse(
        @SerialName("streaming_url") val streamingUrl: String? = null,
        val subtitles: List<VidaraSubtitle>? = null,
    )

    @Serializable
    private data class VidaraSubtitle(
        @SerialName("file_path") val filePath: String? = null,
        val language: String? = null,
    )

    companion object {
        private val FILECODE_REGEX by lazy { Regex("""/[ev]/([a-zA-Z0-9]+)""") }
        private val ORIGIN_REGEX by lazy { Regex("""MIRROR_ORIGIN\s*=\s*['"]([^'"]+)""") }
    }
}
