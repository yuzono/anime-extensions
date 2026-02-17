package eu.kanade.tachiyomi.animeextension.es.katanime.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeList(
    @SerialName("ep") val ep: Ep? = Ep(),
    @SerialName("last") val last: Last? = Last(),
)

@Serializable
data class Data(
    @SerialName("numero") val numero: String? = null,
    @SerialName("idserie") val idserie: Int? = null,
    @SerialName("thumb") val thumb: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("url") val url: String? = null,
)

@Serializable
data class Links(
    @SerialName("url") val url: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("active") val active: Boolean? = null,
)

@Serializable
data class Ep(
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("data") val data: List<Data> = listOf(),
    @SerialName("first_page_url") val firstPageUrl: String? = null,
    @SerialName("from") val from: Int? = null,
    @SerialName("last_page") val lastPage: Int? = null,
    @SerialName("last_page_url") val lastPageUrl: String? = null,
    @SerialName("links") val links: List<Links> = listOf(),
    @SerialName("next_page_url") val nextPageUrl: String? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("per_page") val perPage: Int? = null,
    @SerialName("prev_page_url") val prevPageUrl: String? = null,
    @SerialName("to") val to: Int? = null,
    @SerialName("total") val total: Int? = null,
)

@Serializable
data class Last(
    @SerialName("numero") val numero: String? = null,
)

// ===========================================================

@Serializable
data class CryptoDto(
    @SerialName("ct") val ct: String? = null,
    @SerialName("iv") val iv: String? = null,
    @SerialName("s") val s: String? = null,
)
