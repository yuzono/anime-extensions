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
    @SerialName("anilistId") val aniListId: Int,
    val averageScore: Int?,
    val coverColor: String?,
    val coverImage: CoverImage,
    val duration: Int?,
    val format: String,
    val genres: List<String>,
    val isAdult: Boolean,
    val malScore: Float?,
    val meanScore: Float?,
    val nextAiringEpisode: NextAiringEpisode?,
    val popularity: Long,
    val season: String?,
    val slug: String,
    val status: String,
    val title: Title,
    val totalEpisodes: Int?,
    val type: String,
    val year: Int?,

)

@Serializable
data class AnikageResponse(
    val count: Int,
    val data: List<Result>,
    val hasNext: Boolean,
    val matchQuality: String,
    val page: Int,
    val relaxedBy: List<String>,
    val total: Int,
)

@Serializable
data class EpisodeResult(
    val id: String,
    val slug: String,
    val number: Int,
    val seasonNumber: Int,
    val episodeInSeason: Int,
    val seasonName: String,
    val title: String?,
    val titleRomaji: String?,
    val titleNative: String?,
    val description: String?,
    val image: String?,
    val airDate: String?,
    val runtime: Int?,
    val rating: Float?,
    val isFiller: Boolean,
    val isRecap: Boolean,
)

@Serializable
data class EmbedOptions(
    val key: String,
    val label: String,
    val url: String,
)

@Serializable
data class EpisodeSource(
    val slug: String,
    val number: Int,
    val providerId: String,
    val subType: String,
    val sources: List<SourceData> = emptyList(),
    val subtitles: List<SubtitleData> = emptyList(),
    val embeds: List<Embed>? = null,
    val intro: TimeStamp? = null,
    val outro: TimeStamp? = null,
    val headers: Map<String, String> = emptyMap(),
    val embedOptions: List<EmbedOptions>? = null,
    val cached: Boolean,
    val stale: Boolean,
)

@Serializable
data class SourceData(
    val url: String,
    val quality: String,
    val isM3U8: Boolean? = null,
    val embedUrl: String? = null,
    val type: String?,
) {
    fun episodeSourceUrl(): String = listOfNotNull(
        "https://gg.akage.lol",
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
    val embedUrl: String? = null,
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
