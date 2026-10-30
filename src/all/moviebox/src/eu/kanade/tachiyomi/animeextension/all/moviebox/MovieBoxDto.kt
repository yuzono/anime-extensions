package eu.kanade.tachiyomi.animeextension.all.moviebox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class ApiResponse<T>(
    val code: Int = -1,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
class PagerDto(
    val hasMore: Boolean = false,
)

@Serializable
class SubjectListDto(
    private val items: List<SubjectDto>? = null,
    private val subjectList: List<SubjectDto>? = null,
    val pager: PagerDto? = null,
) {
    val subjects: List<SubjectDto>
        get() = items ?: subjectList ?: emptyList()
}

@Serializable
class SubjectDto(
    val subjectId: String = "",
    val subjectType: Int = 0,
    val title: String = "",
    val description: String? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val cover: ImageDto? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val subtitles: String? = null,
    val detailPath: String = "",
    val corner: String? = null,
    val dubs: List<DubDto>? = null,
)

@Serializable
class ImageDto(
    val url: String? = null,
)

@Serializable
class DubDto(
    val subjectId: String = "",
    val lanName: String = "",
    val lanCode: String? = null,
    val original: Boolean = false,
    val detailPath: String = "",
)

@Serializable
class DetailDto(
    val subject: SubjectDto? = null,
    val stars: List<StarDto>? = null,
    val resource: ResourceDto? = null,
)

@Serializable
class StarDto(
    val name: String? = null,
    val character: String? = null,
)

@Serializable
class ResourceDto(
    val seasons: List<SeasonDto>? = null,
)

@Serializable
class SeasonDto(
    val se: Int = 0,
    val maxEp: Int = 0,
    val allEp: String? = null,
)

@Serializable
class PlayDto(
    val streams: List<StreamDto>? = null,
    val dash: List<StreamDto>? = null,
    val hls: List<JsonObject>? = null,
)

@Serializable
class StreamDto(
    val format: String? = null,
    val id: String = "",
    val url: String = "",
    val resolutions: String? = null,
    val codecName: String? = null,
    val signCookie: String? = null,
    val signHeaderKey: String? = null,
    val vipLocked: Boolean = false,
)

@Serializable
class DownloadDto(
    val downloads: List<DownloadItemDto>? = null,
    val captions: List<CaptionDto>? = null,
)

@Serializable
class DownloadItemDto(
    val id: String = "",
    val url: String = "",
    val resolution: Int = 0,
    val codecName: String? = null,
    val vipLocked: Boolean = false,
)

@Serializable
class CaptionListDto(
    val captions: List<CaptionDto>? = null,
)

@Serializable
class CaptionDto(
    val lan: String? = null,
    val lanName: String? = null,
    val url: String = "",
)

@Serializable
class UserDto(
    val token: String? = null,
)

/** Everything needed to sign a stream again, stored in a [eu.kanade.tachiyomi.animesource.model.Hoster]. */
@Serializable
class DubRef(
    val subjectId: String,
    val detailPath: String,
    val se: Int,
    val ep: Int,
    val name: String,
)

@Serializable
class StreamRef(
    val host: String,
    val subjectId: String,
    val detailPath: String,
    val se: Int,
    val ep: Int,
    val kind: String,
    val resolution: Int,
) {
    val key: String
        get() = "${subjectId}_${se}_${ep}_${kind}_$resolution".replace(NON_KEY, "")

    companion object {
        private val NON_KEY = Regex("[^A-Za-z0-9_]")
    }
}
