package eu.kanade.tachiyomi.animeextension.pt.animefire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class AFResponse<T>(
    val data: T,
    val meta: AFMeta? = null,
)

@Serializable
class AFMeta(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
class AFAnime(
    val id: String,
    val titles: Map<String, String>,
    @SerialName("poster_src") val posterSrc: String,
    val status: String? = null,
    val synopsis: String? = null,
    val audio: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
class AFHome(
    val carousels: List<AFCarousel>,
)

@Serializable
class AFCarousel(
    val key: String,
    val items: List<AFRecentEpisode>,
)

@Serializable
class AFRecentEpisode(
    val id: String,
    val titles: Map<String, String>,
    @SerialName("poster_src") val posterSrc: String,
)

@Serializable
class AFDetails(
    val hero: AFAnime,
    val seasons: List<AFSeason>,
    val episodes: List<AFEpisode>,
    val relations: AFRelated? = null,
    val recommendations: AFRelated? = null,
)

@Serializable
class AFRelated(
    val items: List<AFAnime> = emptyList(),
)

@Serializable
class AFSeason(
    val number: Int,
    val title: String? = null,
    @SerialName("first_episode_number") val firstEpisodeNumber: Int,
)

@Serializable
class AFEpisode(
    val id: String,
    val number: Float,
    val season: Int? = null,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
class AFPlayback(
    val anime: AFAnime,
    val streams: List<AFStream>,
)

@Serializable
class AFStream(
    val audio: String,
    val url: String? = null,
    @SerialName("is_offline") val isOffline: Boolean = false,
    @SerialName("is_mtl") val isMtl: Boolean = false,
)
