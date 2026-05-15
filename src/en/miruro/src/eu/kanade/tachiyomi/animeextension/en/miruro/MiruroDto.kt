package eu.kanade.tachiyomi.animeextension.en.miruro

import kotlinx.serialization.Serializable

@Serializable
data class BrowseResponse(
    val pageInfo: PageInfoDto? = null,
    val media: List<AnimeDto> = emptyList(),
)

@Serializable
data class PageInfoDto(
    val total: Int? = null,
    val perPage: Int? = null,
    val currentPage: Int? = null,
    val lastPage: Int? = null,
    val hasNextPage: Boolean = false,
)

@Serializable
data class AnimeDto(
    val id: Int,
    val idMal: Int? = null,
    val title: TitleDto? = null,
    val coverImage: String? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val averageScore: Int? = null,
    val meanScore: Int? = null,
    val popularity: Int? = null,
    val startDate: StartDateDto? = null,
    val seasonYear: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class TitleDto(
    val native: String? = null,
    val romaji: String? = null,
    val english: String? = null,
    val userPreferred: String? = null,
)

@Serializable
data class StartDateDto(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
data class InfoResponse(
    val media: AnimeDto? = null,
    val tvdb: Int? = null,
    val tmdb: Int? = null,
    val schedule: String? = null,
    val mappings: Map<String, String>? = null,
)

@Serializable
data class EpisodesResponse(
    val mappings: List<String> = emptyList(),
    val providers: Map<String, ProviderDto> = emptyMap(),
)

@Serializable
data class ProviderDto(
    val episodes: EpisodesListDto? = null,
)

@Serializable
data class EpisodesListDto(
    val ssub: List<EpisodeDto> = emptyList(),
    val sub: List<EpisodeDto> = emptyList(),
    val dub: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: String,
    val number: Float = 0f,
    val title: String? = null,
)

@Serializable
data class SourcesResponse(
    val streams: List<StreamDto> = emptyList(),
    val download: String? = null,
)

@Serializable
data class StreamDto(
    val url: String,
    val type: String = "",
    val quality: String? = null,
    val resolution: ResolutionDto? = null,
    val codec: String? = null,
    val audio: String? = null,
    val fansub: String? = null,
    val isActive: Boolean = true,
    val referer: String? = null,
)

@Serializable
data class ResolutionDto(
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class EpisodePayload(
    val episodeId: String,
    val provider: String,
    val subType: String,
)
