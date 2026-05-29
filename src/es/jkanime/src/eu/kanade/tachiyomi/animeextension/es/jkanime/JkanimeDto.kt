package eu.kanade.tachiyomi.animeextension.es.jkanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsLinks(
    val remote: String? = null,
    val slug: String? = null,
    val server: String? = null,
    val lang: Int? = null,
)

@Serializable
data class EpisodeDto(
    val title: String,
    val number: Int,
    val image: String?, // <file_name>.jpg, should be appended to https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/<file_name>.jpg
    val timestamp: String?, // 2011-07-06 10:31:24
)

@Serializable
data class EpisodesPageDto(
    val data: List<EpisodeDto>,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
    @SerialName("next_page_url") val nextPageUrl: String? = null,
    val from: Int,
    val to: Int,
    val total: Int,
)

@Serializable
data class AnimeDto(
    val title: String,
    val synopsis: String?,
    @SerialName("image")
    val thumbnailUrl: String,
    val studios: String?,
    @SerialName("estado")
    val status: String?,
    @SerialName("status")
    val statusEn: String?,
    val url: String,
    @SerialName("slug")
    val slug: String?,
    @SerialName("tipo")
    val type: String?, // ONA, Serie
    @SerialName("type")
    val typeEn: String?, // ONA, TV
    @SerialName("base64_id")
    val base64Id: String?,
    @SerialName("short_title")
    val shortTitle: String?,
)

@Serializable
data class AnimePageDto(
    @SerialName("current_page")
    val currentPage: Int?,
    @SerialName("last_page")
    val lastPage: Int?,
    @SerialName("next_page_url")
    val nextPageUrl: String?,
    val data: List<AnimeDto>,
)
