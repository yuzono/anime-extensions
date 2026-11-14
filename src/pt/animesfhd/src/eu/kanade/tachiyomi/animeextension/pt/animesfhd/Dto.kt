package eu.kanade.tachiyomi.animeextension.pt.animesfhd

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SeriesPageDto(
    val series: List<SeriesDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
class SeriesDto(
    val id: Long,
    val title: String,
    val description: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
    val genre: String? = null,
    val status: String? = null,
    val category: String? = null,
    val year: Int? = null,
    val studio: String? = null,
    @SerialName("alt_titles") val altTitles: String? = null,
) {
    fun toSAnime(heading: String? = null) = SAnime.create().apply {
        url = id.toString()
        title = buildString {
            append(heading?.takeIf { it.isNotBlank() } ?: this@SeriesDto.title)
            if (category == "dublado") append(" (Dublado)")
        }
        thumbnail_url = coverImage?.takeIf { it.isNotBlank() }
        description = buildString {
            this@SeriesDto.description?.takeIf { it.isNotBlank() }?.let { append(it) }
            val extra = listOfNotNull(
                year?.let { "Ano: $it" },
                altTitles?.takeIf { it.isNotBlank() && it != this@SeriesDto.title }?.let { "Outros nomes: $it" },
            )
            if (extra.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extra.joinToString("\n"))
            }
        }.ifEmpty { null }
        genre = this@SeriesDto.genre?.takeIf { it.isNotBlank() }
        author = studio?.takeIf { it.isNotBlank() }
        status = when (this@SeriesDto.status?.lowercase()) {
            "completed", "completo", "finalizado" -> SAnime.COMPLETED
            "ongoing", "airing", "em lançamento", "lancando" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }
}

@Serializable
class SeriesDetailDto(
    val series: SeriesDto,
    val episodes: List<EpisodeDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val seo: SeoDto? = null,
)

@Serializable
class SeoDto(
    val heading: String? = null,
)

@Serializable
class EpisodeDto(
    val id: Long,
    @SerialName("episode_number") private val number: Float? = null,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("aired_at") val airedAt: String? = null,
) {
    val episodeNumber get() = number ?: 0f
}

@Serializable
class EpisodeDetailDto(
    val episode: EpisodeInfoDto,
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
class EpisodeInfoDto(
    @SerialName("subtitle_url") val subtitleUrl: String? = null,
)

@Serializable
class SourceDto(
    val label: String? = null,
    val url: String = "",
    val audio: String? = null,
    @SerialName("native_video") val nativeVideo: Boolean = false,
)
