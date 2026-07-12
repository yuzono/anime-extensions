package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class YummyResponse<T>(
    val response: T? = null,
)

@Serializable
class YummyCatalogDto(
    val data: List<YummyAnimeDto>? = null,
)

@Serializable
class YummyAnimeDto(
    private val title: String? = null,
    @SerialName("anime_url") private val animeUrl: String? = null,
    private val poster: YummyPosterDto? = null,
) {
    fun toSAnime() = SAnime.create().apply {
        title = this@YummyAnimeDto.title ?: ""
        url = animeUrl ?: ""
        thumbnail_url = poster?.big?.let { if (it.startsWith("//")) "https:$it" else it }
    }
}

@Serializable
class YummyPosterDto(
    val big: String? = null,
    val huge: String? = null,
)

@Serializable
class YummyDetailsDto(
    val title: String? = null,
    val description: String? = null,
    val genres: List<YummyNamedDto>? = null,
    @SerialName("anime_status") val status: YummyStatusDto? = null,
    val studios: List<YummyNamedDto>? = null,
    val poster: YummyPosterDto? = null,
    val type: YummyNamedDto? = null,
    val videos: List<YummyVideoDto>? = null,
)

@Serializable
class YummyNamedDto(
    val title: String? = null,
    val alias: String? = null,
)

@Serializable
class YummyStatusDto(
    val value: JsonPrimitive? = null,
)

@Serializable
class YummyVideoDto(
    val number: JsonPrimitive? = null,
    val data: YummyVideoDataDto? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
)

@Serializable
class YummyVideoDataDto(
    val dubbing: String? = null,
    val player: String? = null,
)

@Serializable
class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(val src: String)

@Serializable
class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
)

@Serializable
class KodikData(val links: KodikVideoQuality)
