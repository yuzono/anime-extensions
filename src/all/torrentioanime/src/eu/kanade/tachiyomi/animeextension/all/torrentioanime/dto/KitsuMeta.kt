package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KitsuMetaResponse(
    val meta: KitsuMeta? = null,
)

@Serializable
data class KitsuMeta(
    @SerialName("kitsu_id")
    val kitsuId: String? = null,
    val type: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val videos: List<KitsuVideo>? = null,
)

@Serializable
data class KitsuVideo(
    val title: String? = null,
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
)
