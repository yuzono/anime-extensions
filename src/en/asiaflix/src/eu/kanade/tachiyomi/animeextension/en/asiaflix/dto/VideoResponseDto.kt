package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import kotlinx.serialization.Serializable

@Serializable
data class StreamResultDto(
    val sources: List<StreamFileDto> = emptyList(),
)

@Serializable
data class StreamFileDto(
    val url: String = "",
    val isM3U8: Boolean = false,
)
