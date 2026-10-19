package eu.kanade.tachiyomi.animeextension.en.rule34video

import kotlinx.serialization.Serializable

@Serializable
class VideoObjectDto(
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val uploadDate: String? = null,
)
