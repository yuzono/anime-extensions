package aniyomi.lib.megaextractor

import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import java.io.IOException

/** Resolves public MEGA file/embed links to a decrypted, seekable local stream. */
class MegaExtractor(private val client: OkHttpClient, headers: Headers) {

    private val headers = headers.newBuilder()
        .removeAll("Cookie")
        .removeAll("Authorization")
        .removeAll("Host")
        .removeAll("Range")
        .build()

    /** Fetches only file metadata; media is fetched and decrypted as the player reads it. */
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val link = url.toHttpUrl()
        require(link.host == "mega.nz" || link.host == "mega.co.nz") { "Invalid MEGA host" }
        val fragment = link.fragment.orEmpty()
        val parts = if (fragment.startsWith('!')) {
            fragment.drop(1).split('!')
        } else {
            require(link.pathSegments.firstOrNull() in listOf("file", "embed")) { "Not a MEGA file link" }
            listOf(link.pathSegments.last(), fragment)
        }
        require(parts.size == 2 && FILE_ID.matches(parts[0])) { "Invalid MEGA file link" }
        val key = parts[1].decodeBase64()?.toByteArray()
        require(key?.size == 32) { "Invalid MEGA file key" }

        val file = try {
            client.post(
                "https://g.api.mega.co.nz/cs",
                headers,
                listOf(FileRequest("g", 1, parts[0])).toJsonRequestBody(),
            ).parseAs<List<FileResponse>>().singleOrNull()
        } catch (e: SerializationException) {
            throw IOException("MEGA file is unavailable", e)
        } ?: throw IOException("MEGA file is unavailable")
        if (file.size <= 0) throw IOException("MEGA file is empty")

        val downloadUrl = file.downloadUrl.toHttpUrl().newBuilder().scheme("https").build()
        require(downloadUrl.host.endsWith(".mega.co.nz")) { "Invalid MEGA download host" }
        val localUrl = MegaStreamServer.register(client, downloadUrl, file.size, key, headers)
        return listOf(Video(videoUrl = localUrl, videoTitle = "${prefix}Mega"))
    }

    @Serializable
    private class FileRequest(
        @SerialName("a") val action: String,
        @SerialName("g") val requestDownloadUrl: Int,
        @SerialName("p") val fileId: String,
    )

    @Serializable
    private class FileResponse(
        @SerialName("g") val downloadUrl: String,
        @SerialName("s") val size: Long,
    )

    private companion object {
        val FILE_ID = Regex("[A-Za-z0-9_-]{8}")
    }
}
