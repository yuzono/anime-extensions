package eu.kanade.tachiyomi.animeextension.en.miruro

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

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
data class SourcesResponseDto(
    val streams: List<StreamDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
) {
    @Serializable
    data class NestedWrapper(
        val streams: List<StreamDto> = emptyList(),
        val subtitles: List<SubtitleDto> = emptyList(),
    )

    companion object {
        fun parse(json: String): SourcesResponseDto {
            val element = Json.parseToJsonElement(json).jsonObject

            val directStreams = element["streams"]?.jsonArray
            if (directStreams != null) {
                return jsonParser.decodeFromString<SourcesResponseDto>(json)
            }

            for (entry in element.entries) {
                val value = entry.value
                if (value is JsonObject && value.containsKey("streams")) {
                    return jsonParser.decodeFromString<NestedWrapper>(value.toString()).let {
                        SourcesResponseDto(streams = it.streams, subtitles = it.subtitles)
                    }
                }
            }

            return SourcesResponseDto()
        }
    }
}

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
    val isActive: Boolean = true,
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
data class ConfigResponseDto(
    val streaming: Map<String, ProviderConfigDto> = emptyMap(),
    val providerOrder: List<String> = emptyList(),
    val meta: MetaConfigDto? = null,
) {
    @Serializable
    data class ProviderConfigDto(
        val capabilities: ProviderCapabilitiesDto = ProviderCapabilitiesDto(),
        val parent: String? = null,
        val relationship: String? = null,
        val visible: Boolean = true,
        val player: String = "native",
        val fallback: Int? = null,
        @Serializable(with = ProxyConfigSerializer::class)
        val proxy: ProxyConfigDto = ProxyConfigDto(),
        val cors: Boolean = false,
    )

    @Serializable
    data class ProxyConfigDto(
        val rotate: Boolean = false,
    )

    internal object ProxyConfigSerializer : KSerializer<ProxyConfigDto> {
        override val descriptor: SerialDescriptor = ProxyConfigDto.serializer().descriptor

        override fun serialize(encoder: Encoder, value: ProxyConfigDto) {
            ProxyConfigDto.serializer().serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): ProxyConfigDto = when (val json = decoder.decodeSerializableValue(JsonElement.serializer())) {
            is JsonPrimitive if json.booleanOrNull != null -> ProxyConfigDto(rotate = false)
            is JsonObject -> jsonParser.decodeFromJsonElement(ProxyConfigDto.serializer(), json)
            else -> ProxyConfigDto()
        }
    }

    @Serializable
    data class ProviderCapabilitiesDto(
        val sub: Boolean = false,
        val dub: Boolean = false,
        val ssub: Boolean = false,
        val download: Boolean = false,
        @SerialName("skip_times")
        val skipTimes: Boolean = false,
        val thumbnails: Boolean = false,
    )

    @Serializable
    data class MetaConfigDto(
        val anilist: AnilistConfigDto? = null,
    ) {
        @Serializable
        data class AnilistConfigDto(
            val graphql: String = "https://graphql.anilist.co",
        )
    }
}

@Serializable
data class StatusPageDto(
    val publicGroupList: List<StatusPageGroupDto> = emptyList(),
) {
    @Serializable
    data class StatusPageGroupDto(
        val name: String = "",
        val monitorList: List<StatusMonitorDto> = emptyList(),
    )

    @Serializable
    data class StatusMonitorDto(
        val id: Int = 0,
        val name: String = "",
    )
}

@Serializable
data class StatusHeartbeatDto(
    val heartbeatList: Map<String, List<StatusHeartbeatEntryDto>> = emptyMap(),
) {
    @Serializable
    data class StatusHeartbeatEntryDto(
        val status: Int = 0,
        val ping: Int = 0,
        val time: String = "",
    )
}

@Serializable
data class CachedMirrorsDto(
    val entries: List<String> = emptyList(),
    val values: List<String> = emptyList(),
)
