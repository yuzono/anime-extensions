package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import kotlinx.serialization.Serializable

@Serializable
class Anime(
    val data: Data,
)

@Serializable
class Data(
    val episodes: List<Episode>,
)

@Serializable
class Episode(
    val number: Int,
    val season: Int,
    val url: String,
)
