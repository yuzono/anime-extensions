package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
class MediaLibraryDto(
    val name: String,
    val id: String,
)
