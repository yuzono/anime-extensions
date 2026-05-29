package eu.kanade.tachiyomi.multisrc.dopeflix.dto

import kotlinx.serialization.Serializable

data class VideoData(
    val link: String,
    val name: String,
)

@Serializable
data class SourcesResponse(
    val link: String? = null,
)
