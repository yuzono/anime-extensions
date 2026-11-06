package eu.kanade.tachiyomi.animeextension.all.kartoons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CatalogResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: List<CatalogItem> = emptyList(),
)

@Serializable
class CatalogItem(
    val id: String,
    val title: String,
    val image: String? = null,
    val coverImage: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val tags: List<String> = emptyList(),
    val type: String? = null,
)

@Serializable
class SearchResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: List<SearchItem> = emptyList(),
)

@Serializable
class SearchItem(
    val id: String,
    val title: String,
    val image: String? = null,
    val year: Int? = null,
    val type: String? = null,
)

@Serializable
class ShowDetailsResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: ShowData? = null,
)

@Serializable
class ShowData(
    @SerialName("_id")
    val id: String,
    val title: String,
    val description: String? = null,
    val image: String? = null,
    val coverImage: String? = null,
    val releaseYear: Int? = null,
    val status: String? = null,
    val tags: List<String> = emptyList(),
    val seasons: List<SeasonData> = emptyList(),
)

@Serializable
class SeasonData(
    @SerialName("_id")
    val id: String,
    val seasonNumber: Int? = null,
    val title: String? = null,
    val slug: String? = null,
    val episodes: List<String> = emptyList(),
)

@Serializable
class MovieDetailsResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: MovieData? = null,
)

@Serializable
class MovieData(
    @SerialName("_id")
    val id: String,
    val title: String,
    val description: String? = null,
    val image: String? = null,
    val coverImage: String? = null,
    val releaseYear: Int? = null,
    val status: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
class EpisodesResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: List<EpisodeItem> = emptyList(),
)

@Serializable
class EpisodeItem(
    @SerialName("_id")
    val id: String,
    val seasonId: String? = null,
    val episodeNumber: Float? = null,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val duration: String? = null,
    val slug: String? = null,
)

@Serializable
class LinksResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val data: LinksData? = null,
)

@Serializable
class LinksData(
    val links: List<StreamLink> = emptyList(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val title: String? = null,
)

@Serializable
class StreamLink(
    val name: String,
    val url: String,
)

@Serializable
class SubtitleItem(
    val url: String,
    val lang: String? = null,
)

@Serializable
class StremioCatalogResponse(
    val metas: List<StremioMeta> = emptyList(),
)

@Serializable
class StremioMeta(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val description: String? = null,
)
