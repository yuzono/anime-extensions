package eu.kanade.tachiyomi.animeextension.all.crunchyroll

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================== Auth =================================

@Serializable
class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

// ============================== Images ================================

// Image sets are nested one level deeper than expected: a list of lists of
// renditions, ordered smallest first.
@Serializable
class ImageDto(
    private val source: String,
    private val width: Int = 0,
) {
    val url get() = source

    companion object {
        fun List<List<ImageDto>>?.best(): String? = this?.firstOrNull()?.maxByOrNull { it.width }?.url
    }
}

@Serializable
class ImagesDto(
    @SerialName("poster_tall") val posterTall: List<List<ImageDto>>? = null,
    @SerialName("poster_wide") val posterWide: List<List<ImageDto>>? = null,
    val thumbnail: List<List<ImageDto>>? = null,
)

// ============================== Catalogue =============================

@Serializable
class SeriesMetadataDto(
    @SerialName("extended_description") private val extendedDescription: String? = null,
    @SerialName("season_count") val seasonCount: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("is_simulcast") private val isSimulcast: Boolean = false,
    @SerialName("is_subbed") val isSubbed: Boolean = false,
    @SerialName("is_dubbed") val isDubbed: Boolean = false,
    @SerialName("audio_locales") val audioLocales: List<String> = emptyList(),
    @SerialName("subtitle_locales") val subtitleLocales: List<String> = emptyList(),
    @SerialName("tenant_categories") private val tenantCategories: List<String> = emptyList(),
    @SerialName("series_launch_year") private val launchYear: Int? = null,
) {
    val genres get() = tenantCategories
    val longDescription get() = extendedDescription
    val year get() = launchYear
    val ongoing get() = isSimulcast
}

@Serializable
class ContentItemDto(
    val id: String,
    val title: String,
    private val description: String = "",
    private val images: ImagesDto? = null,
    @SerialName("series_metadata") private val seriesMetadata: SeriesMetadataDto? = null,
) {
    val seasonCount get() = seriesMetadata?.seasonCount ?: 0

    fun toSAnime(): SAnime = SAnime.create().apply {
        url = id
        title = this@ContentItemDto.title
        thumbnail_url = with(ImageDto) {
            images?.posterTall.best() ?: images?.posterWide.best()
        }
        description = buildString {
            val meta = seriesMetadata
            append(meta?.longDescription?.takeIf { it.isNotBlank() } ?: this@ContentItemDto.description)
            meta?.year?.let { append("\n\nYear: $it") }
            if (meta != null && meta.seasonCount > 0) {
                append("\nSeasons: ${meta.seasonCount} • Episodes: ${meta.episodeCount}")
            }
            val langs = buildList {
                if (meta?.isSubbed == true) add("Subbed")
                if (meta?.isDubbed == true) add("Dubbed")
            }
            if (langs.isNotEmpty()) append("\n${langs.joinToString(" / ")}")
            if (meta != null && meta.audioLocales.size > 1) {
                append("\nAudio: ${meta.audioLocales.joinToString()}")
            }
        }
        genre = seriesMetadata?.genres?.joinToString { it.replace('-', ' ') }
        status = if (seriesMetadata?.ongoing == true) SAnime.ONGOING else SAnime.UNKNOWN
        initialized = true
    }
}

@Serializable
class BrowseResponseDto(
    val data: List<ContentItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
class SearchGroupDto(
    val type: String = "",
    val items: List<ContentItemDto> = emptyList(),
)

@Serializable
class SearchResponseDto(
    val data: List<SearchGroupDto> = emptyList(),
)

// ============================ Seasons / Episodes ======================

@Serializable
class SeasonDto(
    val id: String,
    val title: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("season_display_number") val seasonDisplayNumber: String = "",
    @SerialName("audio_locale") val audioLocale: String = "",
    @SerialName("number_of_episodes") val episodeCount: Int = 0,
    private val images: ImagesDto? = null,
    val versions: List<VersionDto> = emptyList(),
) {
    // Season titles carry the dub in a trailing parenthesis - "Hanako-kun 2 (English
    // Dub)" - which stops them lining up with the tracker's own season titles.
    val cleanTitle: String get() = title.replace(DUB_SUFFIX, "").trim()

    val thumbnail: String? get() = with(ImageDto) {
        images?.posterTall.best() ?: images?.posterWide.best()
    }

    // Every audio version of a season is a sibling with its own id, and which one
    // the listing returns depends on the account. The original version's id is the
    // same whichever sibling is returned, so it is what gets stored in URLs.
    val stableId: String get() = versions.firstOrNull { it.original }?.guid ?: id

    // A null locale means original audio.
    fun guidFor(audioLocale: String?): String = versions.firstOrNull { it.audioLocale == audioLocale }?.guid
        ?: versions.firstOrNull { it.original }?.guid
        ?: id
}

private val DUB_SUFFIX = Regex("""\s*\([^)]*\bdub\b[^)]*\)""", RegexOption.IGNORE_CASE)

@Serializable
class SeasonsResponseDto(val data: List<SeasonDto> = emptyList())

// guid, not media_guid, is the id the playback service accepts.
@Serializable
class VersionDto(
    val guid: String,
    @SerialName("audio_locale") val audioLocale: String = "",
    @SerialName("is_premium_only") val isPremiumOnly: Boolean = false,
    val original: Boolean = false,
)

@Serializable
class EpisodeDto(
    val id: String,
    val title: String = "",
    private val episode: String = "",
    @SerialName("episode_number") private val episodeNumber: Float? = null,
    @SerialName("sequence_number") private val sequenceNumber: Float = 0f,
    @SerialName("season_number") private val seasonNumber: Int = 0,
    @SerialName("availability_status") val availabilityStatus: String = "",
    @SerialName("is_premium_only") val isPremiumOnly: Boolean = false,
    @SerialName("audio_locale") val audioLocale: String = "",
    @SerialName("duration_ms") private val durationMs: Long = 0,
    @SerialName("upload_date") private val uploadDate: String? = null,
    @SerialName("episode_air_date") private val airDate: String? = null,
    val versions: List<VersionDto> = emptyList(),
) {
    // The episodes endpoint returns the account's preferred audio whichever season
    // is asked, so id alone changes with the settings. The original version's id
    // does not, so it is what gets stored in the episode URL.
    val stableId: String get() = versions.firstOrNull { it.original }?.guid ?: id

    val date: String? get() = uploadDate ?: airDate

    fun toSEpisode(
        seasonLabel: String,
        dateMillis: Long,
        numberOffset: Float = 0f,
    ): SEpisode = SEpisode.create().apply {
        url = stableId
        episode_number = (episodeNumber ?: sequenceNumber) + numberOffset
        date_upload = dateMillis
        name = buildString {
            if (seasonLabel.isNotEmpty()) append("$seasonLabel ")
            val own = episodeNumber ?: sequenceNumber
            append(if (episode.isNotEmpty()) "Ep. $episode" else "Ep. ${own.toString().removeSuffix(".0")}")
            if (title.isNotEmpty()) append(" - $title")
            if (availabilityStatus == "premium_only") append(" 🔒")
        }
    }
}

@Serializable
class EpisodesResponseDto(val data: List<EpisodeDto> = emptyList())

// ============================== Objects ===============================

// Used to look up the audio versions of one episode from its stable id. Versions
// are read from either place so the lookup does not depend on which shape the
// objects endpoint returns.
@Serializable
class ObjectMetadataDto(
    val versions: List<VersionDto> = emptyList(),
)

@Serializable
class ObjectDto(
    val id: String,
    val versions: List<VersionDto> = emptyList(),
    @SerialName("episode_metadata") val episodeMetadata: ObjectMetadataDto? = null,
) {
    // A null locale means original audio.
    fun guidFor(audioLocale: String?): String {
        val all = versions.ifEmpty { episodeMetadata?.versions.orEmpty() }
        return all.firstOrNull { it.audioLocale == audioLocale }?.guid
            ?: all.firstOrNull { it.original }?.guid
            ?: id
    }
}

@Serializable
class ObjectsResponseDto(val data: List<ObjectDto> = emptyList())

// ============================== Playback ==============================

@Serializable
class DrmDto(
    val name: String = "widevine",
    val drmUrl: String,
)

@Serializable
class SubtitleDto(
    val url: String,
    val language: String = "",
    val format: String = "",
)

@Serializable
class SessionDto(
    // Seconds between stream-token renewals; the stream drops if ignored.
    val renewSeconds: Int = 270,
    val usesStreamLimits: Boolean = false,
)

@Serializable
class PlayResponseDto(
    val url: String,
    val token: String? = null,
    val drm: DrmDto? = null,
    val session: SessionDto? = null,
    val audioLocale: String = "",
    val subtitles: Map<String, SubtitleDto> = emptyMap(),
    val captions: Map<String, SubtitleDto> = emptyMap(),
    val hardSubs: Map<String, SubtitleDto> = emptyMap(),
)

@Serializable
class ErrorDto(
    val error: String? = null,
    val reason: String? = null,
)
