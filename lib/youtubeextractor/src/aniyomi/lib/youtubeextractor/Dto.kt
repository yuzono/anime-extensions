package aniyomi.lib.youtubeextractor

import kotlinx.serialization.Serializable

@Serializable
internal class YoutubeContext(val client: Map<String, String>)

@Serializable
internal class YoutubeVisitorRequest(val context: YoutubeContext)

@Serializable
internal class YoutubePlayerRequest(
    val context: YoutubeContext,
    val videoId: String,
    val cpn: String,
    val contentCheckOk: Boolean,
    val racyCheckOk: Boolean,
)

@Serializable
internal class YoutubeVisitorResponse(val responseContext: YoutubeResponseContext)

@Serializable
internal class YoutubeResponseContext(val visitorData: String)

@Serializable
internal class YoutubePlayerResponse(
    val playabilityStatus: YoutubePlayabilityStatus,
    val streamingData: YoutubeStreamingData? = null,
)

@Serializable
internal class YoutubePlayabilityStatus(val status: String, val reason: String? = null)

@Serializable
internal class YoutubeStreamingData(
    val hlsManifestUrl: String? = null,
    val formats: List<YoutubeFormat> = emptyList(),
    val adaptiveFormats: List<YoutubeFormat> = emptyList(),
)

@Serializable
internal class YoutubeFormat(
    val url: String? = null,
    val mimeType: String,
    val qualityLabel: String? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val averageBitrate: Long? = null,
    val audioTrack: YoutubeAudioTrack? = null,
)

@Serializable
internal class YoutubeAudioTrack(
    val id: String? = null,
    val displayName: String? = null,
    val audioIsDefault: Boolean = false,
)
