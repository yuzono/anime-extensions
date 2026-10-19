package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class YummyResponse<T>(
    val response: T? = null,
)

@Serializable
class YummyCatalogDto(
    val data: List<YummyAnimeDto>? = null,
)

@Serializable
class YummyAnimeDto(
    private val title: String? = null,
    @SerialName("anime_url") private val animeUrl: String? = null,
    private val poster: YummyPosterDto? = null,
) {
    fun toSAnime() = SAnime.create().apply {
        title = this@YummyAnimeDto.title ?: ""
        url = animeUrl ?: ""
        thumbnail_url = poster?.big?.let { if (it.startsWith("//")) "https:$it" else it }
    }
}

@Serializable
class YummyPosterDto(
    val big: String? = null,
    val huge: String? = null,
)

@Serializable
class YummyDetailsDto(
    val title: String? = null,
    val description: String? = null,
    val genres: List<YummyNamedDto>? = null,
    @SerialName("anime_status") val status: YummyStatusDto? = null,
    val studios: List<YummyNamedDto>? = null,
    val poster: YummyPosterDto? = null,
    val type: YummyNamedDto? = null,
    val videos: List<YummyVideoDto>? = null,
)

@Serializable
class YummyNamedDto(
    val title: String? = null,
    val alias: String? = null,
)

@Serializable
class YummyStatusDto(
    val value: JsonPrimitive? = null,
)

@Serializable
class YummyVideoDto(
    val number: JsonPrimitive? = null,
    val data: YummyVideoDataDto? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
)

@Serializable
class YummyVideoDataDto(
    val dubbing: String? = null,
    val player: String? = null,
)

@Serializable
class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(val src: String)

@Serializable
class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
)

@Serializable
class KodikData(val links: KodikVideoQuality)

/**
 * Aksor serves its playlists from a plain JSON call: the id is the last path segment of the
 * player page and `qualities` maps "q1080"/"q720"/… to the stream url. No token, no session
 * and nothing for a WebView to keep alive, which is why these links keep working after
 * playback starts.
 */
@Serializable
class AksorResponse(
    val qualities: Map<String, String?> = emptyMap(),
)

/**
 * CVH (cdnvideohub) lists every video of a title in one playlist. Only the voice type is
 * guaranteed; the studio name is filled in for some titles only.
 */
@Serializable
class CvhPlaylist(
    val items: List<CvhItem> = emptyList(),
)

@Serializable
class CvhItem(
    val vkId: String,
    val voiceType: String? = null,
    val voiceStudio: String? = null,
    val episode: Int? = null,
)

@Serializable
class CvhVideo(
    val sources: CvhSources,
)

@Serializable
class CvhSources(
    val mpeg4kUrl: String? = null,
    val mpeg2kUrl: String? = null,
    val mpegQhdUrl: String? = null,
    val mpegFullHdUrl: String? = null,
    val mpegHighUrl: String? = null,
    val mpegMediumUrl: String? = null,
    val mpegLowUrl: String? = null,
    val mpegLowestUrl: String? = null,
    val mpegTinyUrl: String? = null,
) {
    /** Progressive mp4 renditions, best first, keyed by height. */
    fun renditions(): List<Pair<String, String>> = listOf(
        "2160" to mpeg4kUrl,
        "1440" to mpegQhdUrl,
        "1440" to mpeg2kUrl,
        "1080" to mpegFullHdUrl,
        "720" to mpegHighUrl,
        "480" to mpegMediumUrl,
        "360" to mpegLowUrl,
        "240" to mpegLowestUrl,
        "144" to mpegTinyUrl,
    ).mapNotNull { (quality, url) -> url?.takeIf { it.isNotBlank() }?.let { quality to it } }
        .distinctBy { it.first }
}
