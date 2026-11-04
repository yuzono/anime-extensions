package eu.kanade.tachiyomi.animeextension.en.gotaku

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ======================== Filters (/api/v1/filters) =======================
@Serializable
class FiltersResponseDto(val data: FiltersDataDto = FiltersDataDto())

@Serializable
class FiltersDataDto(
    val genres: List<IdNameDto> = emptyList(),
    val themes: List<IdNameDto> = emptyList(),
    val types: List<ValueLabelDto> = emptyList(),
    val statuses: List<ValueLabelDto> = emptyList(),
    val seasons: List<ValueLabelDto> = emptyList(),
    val ratings: List<ValueLabelDto> = emptyList(),
    val years: List<ValueLabelDto> = emptyList(),
)

@Serializable
class IdNameDto(val id: Int = 0, val name: String = "")

@Serializable
class ValueLabelDto(val value: String = "", val label: String = "")

// ========================== Browse (/api/v1/titles) =======================
@Serializable
class TitlesResponseDto(
    val data: List<TitleDto> = emptyList(),
    val meta: MetaDto? = null,
)

@Serializable
class MetaDto(
    val page: Int = 1,
    val limit: Int = 28,
    @SerialName("has_more") val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
class TitleDto(
    val id: String = "",
    val name: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    val format: String? = null,
    @SerialName("is_adult") val isAdult: Boolean? = null,
    val episodes: EpisodesInfoDto? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = this@TitleDto.id
        title = this@TitleDto.name
        thumbnail_url = posterUrl?.takeIf { it.isNotBlank() }
    }
}

@Serializable
class EpisodesInfoDto(
    @SerialName("latest_sub") val latestSub: Int? = null,
    @SerialName("latest_dub") val latestDub: Int? = null,
    val total: Int? = null,
)

// ===================== Details (/api/v1/titles/{id}) ======================
@Serializable
class DetailsResponseDto(val data: DetailsDataDto = DetailsDataDto())

@Serializable
class SeasonDto(
    val id: String,
    val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
)

@Serializable
class DetailsDataDto(
    val title: TitleDetailDto = TitleDetailDto(),
    val relations: List<RelatedTitleDto> = emptyList(),
    val recommendations: List<RelatedTitleDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
class TitleDetailDto(
    val id: String = "",
    val name: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val synopsis: String? = null,
    val format: String? = null,
    @SerialName("age_rating") val ageRating: String? = null,
    val year: Int? = null,
    @SerialName("mal_score") val malScore: Double? = null,
    @SerialName("is_adult") val isAdult: Boolean? = null,
    val episodes: EpisodesInfoDto? = null,
    @SerialName("alt_names") val altNames: List<String> = emptyList(),
    val status: String? = null,
    val season: String? = null,
    val aired: String? = null, // pre-formatted: "Oct 4, 2026"
    val ended: String? = null,
    val source: String? = null,
    val country: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("ratings_avg") val ratingsAvg: Double? = null,
    val links: Map<String, String?> = emptyMap(), // short code -> full URL, e.g. "al" -> anilist
    val genres: List<IdNameDto> = emptyList(),
    val themes: List<IdNameDto> = emptyList(),
    val studios: List<IdNameDto> = emptyList(),
    val producers: List<IdNameDto> = emptyList(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = id
        title = name
        thumbnail_url = posterUrl?.takeIf { it.isNotBlank() }
        status = parseStatus(this@TitleDetailDto.status)
        background_url = this@TitleDetailDto.backdropUrl?.takeIf { it.isNotBlank() }
        genre = (genres + themes).map { it.name }.filter { it.isNotBlank() }
            .joinToString(", ").takeIf { it.isNotBlank() }
        author = studios.mapNotNull { it.name.takeIf(String::isNotBlank) }
            .joinToString(", ").takeIf { it.isNotBlank() }
    }
}

@Serializable
class RelatedTitleDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val format: String? = null,
    val relation: String? = null,
)

// ======================= Decrypted (?k=N) payloads ========================
// INFO decrypts are wrapped in a "data" envelope. The MANIFEST decrypt
// (different KDF, no envelope) is modeled below.
@Serializable
class GotakuEnvelope<T>(val data: T? = null)

@Serializable
class AudioFlagsDto(
    @SerialName("hard_sub") val hardSub: Boolean = false,
    @SerialName("soft_sub") val softSub: Boolean = false,
    val dub: Boolean = false,
)

@Serializable
class EmbedDto(
    val url: String? = null,
    val skip: SkipDto? = null,
)

@Serializable
class SkipDto(
    val intro: List<Double>? = null, // [start, end] in seconds
    val outro: List<Double>? = null,
)

@Serializable
class EpisodeDto(
    val id: String = "",
    val number: Double? = null, // Double: fractional-episode safety
    val label: String? = null,
    val name: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("aired_at") val airedAt: String? = null, // "2022-09-13T07:00:00Z"
    @SerialName("is_filler") val isFiller: Boolean = false,
    val sub: Boolean = false,
    val dub: Boolean = false,
)

// =============== Manifest (decrypted via ManifestHandshake) ===============

@Serializable
class ManifestDto(
    val source: String? = null, // m3u8 URL on the CDN
    @SerialName("keySeed") val keySeed: String? = null,
    val token: String? = null,
    @SerialName("expiresAt") val expiresAt: Long? = null,
    val obf: ObfDto? = null,
    val subtitles: List<ManifestSubtitleDto>? = null,
)

@Serializable
class ObfDto(
    @SerialName("segmentBytes") val segmentBytes: Int = 0, // 0 => clean passthrough
    val version: Int = 1,
)

@Serializable
class ManifestSubtitleDto(
    val url: String? = null,
    val language: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val name: String? = null,
)

// ========================= WebHook Resolution DTO =========================

@Serializable
class WebHookData(
    val reason: String? = null,
    val m3u8Text: String? = null,
    val m3u8Url: String? = null,
    val manifestJson: String? = null,
    val streamUrl: String? = null,
    val crypto: List<CryptoObsDto> = emptyList(),
    val urls: List<UrlObsDto> = emptyList(),
    val levels: List<WebLevelDto> = emptyList(),
    val manifest: WebManifestDto? = null,
    val frag: WebFragDto? = null,
)

@Serializable
class CryptoObsDto(
    val op: String? = null,
    val alg: String? = null,
    val key: String? = null, // hex - the ACTUAL key bytes, captured at import
    val iv: String? = null,
    val counter: String? = null, // hex - the actual CTR counter block
    val bits: Int? = null,
    val bytes: Int = 0,
)

@Serializable
class UrlObsDto(val how: String? = null, val url: String = "")

@Serializable
class WebManifestDto(val levels: List<WebLevelMetaDto> = emptyList())

@Serializable
class WebLevelMetaDto(
    val url: String? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val resolution: String? = null,
)

@Serializable
class WebLevelDto(
    val url: String? = null,
    val live: Boolean = false,
    val targetduration: Double? = null,
    val levelkeys: List<WebKeyDto> = emptyList(),
    val fragments: List<WebFragDto> = emptyList(),
)

@Serializable
class WebKeyDto(val method: String? = null, val uri: String? = null, val iv: String? = null, val format: String? = null)

@Serializable
class WebFragDto(val url: String = "", val dur: Double = 0.0, val head: String? = null)

internal fun parseStatus(status: String?): Int = when (status?.uppercase()) {
    "RELEASING", "ONGOING" -> SAnime.ONGOING
    "FINISHED", "COMPLETED" -> SAnime.COMPLETED
    "CANCELLED" -> SAnime.CANCELLED
    "HIATUS" -> SAnime.ON_HIATUS
    else -> SAnime.UNKNOWN
}
