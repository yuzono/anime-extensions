package eu.kanade.tachiyomi.animeextension.en.miruro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
data class PipePayload(
    val path: String,
    val method: String,
    val query: JsonObject = JsonObject(emptyMap()),
    val body: JsonElement? = null,
    val version: String = "0.2.0",
)

@Serializable
data class AnimeBrowseResponse(
    val media: List<AnimeMediaDto>? = null,
    val pageInfo: PageInfoDto? = null,
    val results: List<AnimeMediaDto>? = null,
    val data: List<AnimeMediaDto>? = null,
)

@Serializable
data class PageInfoDto(
    val hasNextPage: Boolean = false,
)

@Serializable
data class AnimeMediaDto(
    val id: Int = 0,
    @SerialName("idMal") val malId: Int? = null,
    val title: TitleDto? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val studios: StudiosDto? = null,
) {
    @Serializable
    data class TitleDto(
        val userPreferred: String? = null,
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )

    @Serializable
    data class CoverImageDto(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null,
    )

    @Serializable
    data class StudiosDto(
        val edges: List<StudioEdgeDto>? = null,
    )

    @Serializable
    data class StudioEdgeDto(
        val isMain: Boolean = false,
        val node: StudioNodeDto? = null,
    )

    @Serializable
    data class StudioNodeDto(
        val name: String? = null,
    )
}

@Serializable
data class EpisodesResponseDto(
    val providers: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProviderEpisodeDto(
    val number: Float,
    val id: String = "",
    val title: String = "",
)

@Serializable
data class SourcesResponseDto(
    val streams: List<StreamDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class StreamDto(
    val type: String = "",
    val url: String = "",
    val quality: String = "",
    val resolution: ResolutionDto? = null,
    val codec: String = "",
    val audio: String = "",
    val fansub: String = "",
    val referer: String = "https://kwik.cx/",
)

@Serializable
data class ResolutionDto(
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class SubtitleDto(
    val url: String = "",
    val label: String = "",
    val language: String = "",
)

@Serializable
class AnilistMalIdResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            @SerialName("idMal") val idMal: Int? = null,
        )
    }
}

@Serializable
class JikanEpisodesDto(
    val data: List<JikanEpisodeDataDto>,
    val pagination: JikanPaginationDto,
) {
    @Serializable
    class JikanEpisodeDataDto(
        @SerialName("mal_id") val number: Int,
        val filler: Boolean,
    )

    @Serializable
    class JikanPaginationDto(
        @SerialName("has_next_page") val hasNextPage: Boolean,
    )
}
