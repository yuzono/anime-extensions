package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
class FiltersDto(
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val officialRatings: List<String>? = null,
    val years: List<Int>? = null,
)
