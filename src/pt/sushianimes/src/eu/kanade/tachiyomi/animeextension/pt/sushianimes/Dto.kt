package eu.kanade.tachiyomi.animeextension.pt.sushianimes

import kotlinx.serialization.Serializable

@Serializable
class AnimeDto(
    val containsSeason: List<SeasonDto> = emptyList(),
)

@Serializable
class SeasonDto(
    val seasonNumber: String? = null,
    val episode: List<EpisodeSchemaDto> = emptyList(),
)

@Serializable
class EpisodeSchemaDto(
    val episodeNumber: String,
    val name: String? = null,
    val url: String,
)
