package eu.kanade.tachiyomi.animeextension.en.animecube

import kotlinx.serialization.Serializable

@Serializable
class EncryptedPayloadDto(
    val d: String? = null,
    val sources: List<SourceItem>? = emptyList(),
)

@Serializable
class SourcesVersionsDto(
    val bySeason: Map<String, Map<String, Map<String, String>>>? = emptyMap(),
)

@Serializable
class EpisodeSourcesResponse(
    val success: Boolean? = null,
    val sources: List<SourceItem>? = emptyList(),
)

@Serializable
class SourceItem(
    val platform: String? = null,
    val videoId: String? = null,
    val privateId: String? = null,
    val quality: String? = null,
    val goodSub: Boolean? = null,
)

@Serializable
class ReleasePreviewDto(
    val studio: String? = null,
)

@Serializable
class AnimeDetailDto(
    val title: String? = null,
    val description: String? = null,
    val coverImage: String? = null,
    val genres: List<String>? = emptyList(),
    val status: String? = null,
    val year: Int? = null,
    val slug: String? = null,
    val studio: String? = null,
    val studios: List<String>? = emptyList(),
    val releasePreview: ReleasePreviewDto? = null,
    val primaryTabs: List<PrimaryTabDto>? = emptyList(),
)

@Serializable
class PrimaryTabDto(
    val id: String? = null,
    val seasons: List<SeasonDto>? = emptyList(),
)

@Serializable
class SeasonDto(
    val id: String? = null,
    val episodes: List<EpisodeDto>? = emptyList(),
)

@Serializable
class EpisodeDto(
    val id: String? = null,
    val number: Float? = null,
    val title: String? = null,
    val publishedAt: String? = null,
    val redirectHref: String? = null,
)

@Serializable
class CatalogAnimeDto(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val coverImage: String? = null,
    val genres: List<String>? = emptyList(),
    val status: String? = null,
    val latestEpisodeDisplay: String? = null,
    val year: Int? = null,
)
