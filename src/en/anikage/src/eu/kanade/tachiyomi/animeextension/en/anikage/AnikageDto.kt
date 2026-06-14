package eu.kanade.tachiyomi.animeextension.en.anikage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NextAiringEpisode(
    val episode: Int,
    val airingAt: Long,
    val timeUntilAiring: Long,
)

@Serializable
data class CoverImage(
    val medium: String,
    val large: String,
    val extraLarge: String,
)

@Serializable
data class Title(
    val romaji: String,
    val english: String?,
)

@Serializable
data class Result(
    val slug: String,
    @SerialName("anilistId") val aniListId: Int,
    val title: Title,
    val coverImage: CoverImage,
    val type: String,
    val format: String,
    val status: String,
    val totalEpisodes: Int?,
    val currentEpisode: Int?,
    val averageScore: Int?,
    val genres: List<String>,
    val year: Int?,
    val nextAiringEpisode: NextAiringEpisode?,

)

@Serializable
data class AnikageResponse(
    val page: Int,
    val perPage: Int,
    val total: Int,
    val hasNextPage: Boolean,
    val results: List<Result>,
)

@Serializable
data class EpisodeResult(
    val id: String,
    val number: Int,
    val title: String?,
    val description: String?,
    val img: String?,
    val airDate: String?,
    val isFiller: Boolean,
    val rating: Float?,
    val updatedAt: Long,
)

@Serializable
data class EpisodeSource(
    val sources: List<SourceData> = emptyList(),
    val subtitles: List<SubtitleData> = emptyList(),
    val embeds: List<Embed>?,
    val intro: TimeStamp?,
    val outro: TimeStamp?,
    val headers: String,
    val cached: Boolean,
    val stale: Boolean,
)

@Serializable
data class SourceData(
    val url: String,
    val quality: String,
    val isM3U8: Boolean?,
    val type: String?, // softsub,
) {
    fun episodeSourceUrl(): String = listOfNotNull(
        "https://prox.anikage.cc",
        isM3U8?.let { "m3u8" } ?: "stream",
        url,
    ).joinToString("/")
}

@Serializable
data class SubtitleData(
    val file: String,
    val label: String,
    val kind: String,
    val default: Boolean,
)

@Serializable
data class Embed(
    val url: String,
    val type: String,
    val server: String,
)

@Serializable
data class TimeStamp(
    val start: Int,
    val end: Int,
)
