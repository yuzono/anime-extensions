package eu.kanade.tachiyomi.animeextension.en.kisskh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class DramaPageDto(
    val page: Int? = null,
    val totalCount: Int? = null,
    val data: List<DramaDto> = emptyList(),
)

@Serializable
class DramaDto(
    val id: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
)

@Serializable
class DramaDetailDto(
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val status: String? = null,
    val type: String? = null,
    val episodesCount: Int? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
class EpisodeDto(
    val id: Int? = null,
    val number: Float? = null,
)

@Serializable
class EpisodeVideoDto(
    @SerialName("Video")
    val video: String? = null,
    @SerialName("Type")
    val type: Int? = null,
)

@Serializable
class SubtitleDto(
    val src: String? = null,
    val label: String? = null,
)

@Serializable
class KeyDto(
    val key: String,
)
