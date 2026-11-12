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
    val medium: String? = null,
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class Title(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class Result(
    @SerialName("anilistId") val aniListId: Int,
    val averageScore: Int?,
    val coverColor: String?,
    val coverImage: CoverImage?,
    val duration: Int?,
    val format: String?,
    val genres: List<String>,
    val isAdult: Boolean,
    val malScore: Float?,
    val meanScore: Float?,
    val nextAiringEpisode: NextAiringEpisode?,
    val popularity: Long,
    val season: String?,
    val slug: String,
    val status: String?,
    val title: Title,
    val totalEpisodes: Int?,
    val type: String?,
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
data class AnimeInfoTitle(
    val romaji: String?,
    val english: String?,
    val native: String? = null,
)

@Serializable
data class AnimeInfoStudio(
    val name: String,
    val isAnimationStudio: Boolean = false,
)

@Serializable
data class AnimeInfo(
    val slug: String,
    val title: AnimeInfoTitle,
    val description: String? = null,
    val coverImage: CoverImage? = null,
    val status: String,
    val genres: List<String> = emptyList(),
    val studios: List<AnimeInfoStudio> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(),
)

@Serializable
data class RelatedAnimeTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class Relation(
    val slug: String,
    val title: RelatedAnimeTitle,
    val format: String? = null,
    val status: String? = null,
    val anilistId: Int? = null,
    val coverImage: String? = null,
    val relationType: String? = null,
)

@Serializable
data class Recommendation(
    val slug: String,
    val title: RelatedAnimeTitle,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val anilistId: Int? = null,
    val coverImage: String? = null,
)

@Serializable
data class AnimeInfoResponse(
    val anime: AnimeInfo,
    val banned: Boolean = false,
)

@Serializable
data class EpisodeResult(
    val id: String,
    val slug: String,
    val number: Int,
    val seasonNumber: Int?,
    val episodeInSeason: Int?,
    val seasonName: String?,
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

/** No defaults: a renamed key must fail the parse so the static server list is used instead. */
@Serializable
class ServerInfo(
    val providerId: String,
    val subTypes: List<String>,
)

@Serializable
class EpisodeServers(
    val servers: List<ServerInfo>,
)

@Serializable
class EpisodeSource(
    val sources: List<SourceData> = emptyList(),
    val subtitles: List<SubtitleData> = emptyList(),
)

@Serializable
class SourceData(
    val url: String,
    val quality: String,
    val isM3U8: Boolean? = null,
) {
    fun episodeSourceUrl(): String = listOfNotNull(
        "https://og.bakayaro.live",
        if (isM3U8 == true) "m3u8" else "stream",
        url,
    ).joinToString("/")
}

@Serializable
class SubtitleData(
    val file: String,
    val label: String? = null,
    val kind: String? = null,
)
