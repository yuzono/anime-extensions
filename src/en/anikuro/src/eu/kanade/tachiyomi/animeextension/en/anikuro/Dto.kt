package eu.kanade.tachiyomi.animeextension.en.anikuro

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SearchResponseDto(
    val ok: Boolean? = null,
    val data: SearchDataDto? = null,
    val meta: MetaDto? = null,
)

@Serializable
data class SearchDataDto(
    val items: List<AnimeItemDto>? = null,
)

@Serializable
data class MetaDto(
    val count: Int? = null,
    val filters: FiltersMetaDto? = null,
)

@Serializable
data class FiltersMetaDto(
    val page: Int? = null,
    val perPage: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnimeItemDto(
    val id: Int? = null,
    val title: TitleDto? = null,
    val images: ImagesDto? = null,
    @JsonNames("coverimage")
    val coverImage: CoverImageDto? = null,
    val banner: String? = null,
)

@Serializable
private data class TitleSurrogate(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable(with = TitleSerializer::class)
data class TitleDto(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

object TitleSerializer : KSerializer<TitleDto> {
    override val descriptor: SerialDescriptor = TitleSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): TitleDto {
        val jsonDecoder = decoder as? JsonDecoder ?: return TitleDto()
        return runCatching {
            val element = jsonDecoder.decodeJsonElement()
            when {
                element is JsonObject -> {
                    val surrogate = jsonDecoder.json.decodeFromJsonElement(TitleSurrogate.serializer(), element)
                    TitleDto(
                        userPreferred = surrogate.userPreferred,
                        english = surrogate.english,
                        romaji = surrogate.romaji,
                        native = surrogate.native,
                    )
                }

                element is JsonPrimitive && element.isString -> {
                    val str = element.content
                    TitleDto(userPreferred = str, english = str, romaji = str, native = str)
                }

                else -> TitleDto()
            }
        }.getOrDefault(TitleDto())
    }

    override fun serialize(encoder: Encoder, value: TitleDto) {
        val surrogate = TitleSurrogate(
            userPreferred = value.userPreferred,
            english = value.english,
            romaji = value.romaji,
            native = value.native,
        )
        TitleSurrogate.serializer().serialize(encoder, surrogate)
    }
}

@Serializable
data class ImagesDto(
    val cover: String? = null,
    val banner: String? = null,
    val thumbnail: String? = null,
)

@Serializable
private data class CoverImageSurrogate(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable(with = CoverImageSerializer::class)
data class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

object CoverImageSerializer : KSerializer<CoverImageDto> {
    override val descriptor: SerialDescriptor = CoverImageSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): CoverImageDto {
        val jsonDecoder = decoder as? JsonDecoder ?: return CoverImageDto()
        return runCatching {
            val element = jsonDecoder.decodeJsonElement()
            when {
                element is JsonObject -> {
                    val surrogate = jsonDecoder.json.decodeFromJsonElement(CoverImageSurrogate.serializer(), element)
                    CoverImageDto(extraLarge = surrogate.extraLarge, large = surrogate.large, medium = surrogate.medium)
                }

                element is JsonPrimitive && element.isString -> {
                    val str = element.content
                    CoverImageDto(extraLarge = str, large = str, medium = str)
                }

                else -> CoverImageDto()
            }
        }.getOrDefault(CoverImageDto())
    }

    override fun serialize(encoder: Encoder, value: CoverImageDto) {
        val surrogate = CoverImageSurrogate(extraLarge = value.extraLarge, large = value.large, medium = value.medium)
        CoverImageSurrogate.serializer().serialize(encoder, surrogate)
    }
}

@Serializable
data class AnimeDetailResponseDto(
    val ok: Boolean? = null,
    val data: AnimeDetailDataDto? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnimeDetailDataDto(
    val id: Int? = null,
    val title: TitleDto? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val images: ImagesDto? = null,
    @JsonNames("coverimage")
    val coverImage: CoverImageDto? = null,
    val averageScore: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val studio: String? = null,
    val recommendations: List<AnimeItemDto>? = null,
    val relations: List<AnimeItemDto>? = null,
)

@Serializable
data class EpisodesResponseDto(
    val ok: Boolean? = null,
    val data: EpisodeDataDto? = null,
)

@Serializable
data class EpisodeDataDto(
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: String? = null,
    val number: Float? = null,
    val displayNumber: String? = null,
    val title: String? = null,
    val image: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val overview: String? = null,
    val airedAt: String? = null,
    val filler: Boolean? = null,
    val variants: List<String>? = null,
)

@Serializable
data class ProviderResponseDto(
    val ok: Boolean? = null,
    val data: ProviderDataDto? = null,
)

@Serializable
data class ProviderDataDto(
    val provider: String? = null,
    val label: String? = null,
    val normalized: List<NormalizedVariantDto>? = null,
)

@Serializable
data class NormalizedVariantDto(
    val variant: String? = null,
    val sources: List<SourceItemDto>? = null,
    val subtitles: List<SubtitleItemDto>? = null,
    val headers: Map<String, String>? = null,
)

@Serializable
data class SourceItemDto(
    val url: String? = null,
    val quality: String? = null,
    val type: String? = null,
    @SerialName("isM3U8")
    val isM3U8: Boolean? = null,
)

@Serializable
data class SubtitleItemDto(
    val url: String? = null,
    val label: String? = null,
    val lang: String? = null,
)
