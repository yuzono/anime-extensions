package eu.kanade.tachiyomi.animeextension.zh.xfani

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AnimeInfo(
    val id: Int,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    val director: String? = null,
    val actors: List<String>? = null,
    @SerialName("meta_tags") val metaTags: List<String>? = null,
    @SerialName("is_finished") val isFinished: Boolean = false,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
class DetailInfo(
    val anime: AnimeInfo,
    val sources: List<SourceInfo>,
)

@Serializable
class RecentInfo(val windows: List<RecentWindow>)

@Serializable
class RecentWindow(val days: Int, val items: List<RecentAnime>)

@Serializable
class RecentAnime(
    val id: Int,
    val title: String,
    val coverUrl: String? = null,
    val isFinished: Boolean = false,
)

@Serializable
class PlayInfo(
    val episodeId: Int,
    val animeId: Int,
    val sources: List<SourceInfo>,
    val pageSourceCode: String,
)

@Serializable
class SourceInfo(
    val id: Int,
    val code: String,
    val name: String,
    val episodes: List<EpisodeInfo>,
)

@Serializable
class EpisodeInfo(
    val id: Int,
    val kind: String = "main",
    val title: String? = null,
    @SerialName("episode_number") val number: Float,
)

@Serializable
class PlaybackInfo(
    val ok: Boolean,
    val error: String? = null,
    val candidates: List<PlaybackCandidate> = emptyList(),
)

@Serializable
class PlaybackCandidate(
    @SerialName("source_id") val sourceId: Int,
    val url: String,
    val quality: String? = null,
)

@Serializable
class CatalogueRequest(
    @SerialName("search_term") val searchTerm: String,
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("items_per_page") val itemsPerPage: Int,
    @SerialName("sort_by") val sortBy: String,
    @SerialName("sort_order") val sortOrder: String,
    @SerialName("filter_type_id") val typeId: Int? = null,
    @SerialName("filter_meta_tags") val metaTags: List<String>? = null,
    @SerialName("filter_format") val format: String? = null,
    @SerialName("filter_release_year") val releaseYear: Int? = null,
)

@Serializable
class CataloguePage(@SerialName("page_number") val pageNumber: Int)

@Serializable
class PlaybackRequest(
    val action: String,
    @SerialName("episode_id") val episodeId: Int,
    @SerialName("source_id") val sourceId: Int,
)

fun AnimeInfo.toAnime(): SAnime = SAnime.create().apply {
    url = "/anime/$id"
    title = this@toAnime.title
    thumbnail_url = coverUrl
    description = this@toAnime.description
    author = director
    artist = actors?.joinToString()
    genre = metaTags?.joinToString()
    status = if (isFinished) SAnime.COMPLETED else SAnime.ONGOING
    fetch_type = FetchType.Episodes
}
