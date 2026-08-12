package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.Serializable

@Serializable
data class TheMovieDatabaseResponse(
    val page: Int,
    val results: List<TmdbResult>,
    val total_results: Int,
    val total_pages: Int,
)

@Serializable
data class TmdbResult(
    val id: Int,
    val name: String? = null,
    val title: String? = null,
    val original_name: String? = null,
    val original_title: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val origin_country: List<String>? = null,
    val original_language: String? = null,
    val first_air_date: String? = null,
    val release_date: String? = null,
    val overview: String? = null,
    val genre_ids: List<Int>? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val popularity: Double? = null,
    val adult: Boolean? = null,
    val media_type: String? = null,
)
// TmdbDiscoverPlusResponse.kt

@Serializable
data class TmdbDiscoverPlusResponse(
    val meta: TmdbDiscoverPlusMeta? = null,
    val cacheMaxAge: Int? = null,
    val staleRevalidate: Int? = null,
    val staleError: Int? = null,
)

@Serializable
data class TmdbDiscoverPlusMeta(
    val id: String? = null,
    val tmdbId: Int? = null,
    var imdbId: String? = null,
    var imdb_id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val cast: List<String>? = null,
    val director: String? = null,
    val writer: String? = null,
    val released: String? = null,
    val status: String? = null,
    val videos: List<EpisodeVideo>? = null,
)
