package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AioMetaResponse(
    val meta: AioMeta? = null,
)

@Serializable
data class AioMeta(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val released: String? = null,
    val status: String? = null,
    val poster: String? = null,
    val background: String? = null,
    @SerialName("_kitsuId") val kitsuId: String? = null,
    @SerialName("_imdbId") val imdbId: String? = null,
    @SerialName("imdb_id") val imdbIdAlt: String? = null,
    @SerialName("_stability") val stability: AioStability? = null,
    @SerialName("_providerArt") val providerArt: AioProviderArt? = null,
    val videos: List<AioVideo>? = null,
)

@Serializable
data class AioStability(
    val ongoing: Boolean? = null,
)

@Serializable
data class AioProviderArt(
    val poster: String? = null,
    val background: String? = null,
)

@Serializable
data class AioVideo(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val released: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val available: Boolean? = null,
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
)

@Serializable
data class BackUpEpisodeResponse(
    val data: List<BackUpEpisodeVideo>? = null,
)

@Serializable
data class BackUpEpisodeVideo(
    val title: String? = null,
    val airDate: String? = null,
    val seasonNumber: Int? = null,
    val seasonEpisodeNumber: Int? = null,
    val thumbnail: String? = null,
    val id: String? = null,
    val imdbId: String? = null,
    val summary: String? = null,
    val type: String? = null,
    val episodeNumber: Int? = null,
    val kitsuId: Int? = null,
)
