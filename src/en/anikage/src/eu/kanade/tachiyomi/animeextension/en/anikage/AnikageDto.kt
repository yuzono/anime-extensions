package eu.kanade.tachiyomi.animeextension.en.anikage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GraphQLResult(
    val data: DataShow,
) {
    @Serializable
    data class DataShow(
        @SerialName("Page") val page: Page,
    ) {
        @Serializable
        data class Page(
            val pageInfo: PageInfo,
            val media: List<AnikageAnime>,
        ) {
            @Serializable
            data class PageInfo(
                val total: Int,
                val perPage: Int,
                val currentPage: Int,
                val lastPage: Int,
                val hasNextPage: Boolean,
            )
        }
    }
}

@Serializable
data class AnikageAnime(
    val id: Int,
    val title: TitleData,
    val coverImage: CoverImage,
    val startDate: StartDate,
    val bannerImage: String?,
    val season: String?,
    val seasonYear: Int?,
    val description: String?,
    val type: String,
    val format: String?,
    val status: String,
    val episodes: Int?,
    val duration: Int?,
    val chapters: Int?,
    val volumes: Int?,
    val genres: List<String>,
    val isAdult: Boolean,
    val averageScore: Int?,
    val popularity: Long?,
    val nextAiringEpisode: NextAiringEpisode?,
    val mediaListEntry: MediaListEntry?,
)

@Serializable
data class MediaListEntry(
    val id: Int,
    val status: String,
)

@Serializable
data class TitleData(
    val english: String?,
    val romaji: String,
)

@Serializable
data class CoverImage(
    val extraLarge: String,
    val color: String?,
)

@Serializable
data class StartDate(
    val year: Int?,
    val month: Int?,
    val day: Int?,
)

@Serializable
data class NextAiringEpisode(
    val airingAt: Long,
    val timeUntilAiring: Long,
    val episode: Int,
)

@Serializable
data class EpisodeResult(
    val number: Int,
    val title: String?,
    val description: String?,
    val img: String?,
    val isFiller: Boolean,
    val subProviders: List<String> = emptyList(),
    val dubProviders: List<String> = emptyList(),
)

@Serializable
data class EpisodeSource(
    val sources: List<SourceData>,
    val subtitles: List<SubtitleData> = emptyList(),
    val thumbnails: List<String> = emptyList(),

)

@Serializable
data class SourceData(
    val url: String,
    val quality: String,
)

@Serializable
data class SubtitleData(
    val id: String,
    val url: String,
    val lang: String,
    val label: String,
    val kind: String,
    val default: Boolean,
)
