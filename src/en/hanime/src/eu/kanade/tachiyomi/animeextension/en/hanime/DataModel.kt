package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wrapper around the v11 search response: GET {guest_api}/api/v11/search_hvs
 * returns `{"data": [ ...hits ] }`.
 */
@Serializable
data class SearchResponse(
    val data: List<HitsModel> = emptyList(),
)

@Serializable
data class HitsModel(
    val id: Long? = null,
    val name: String = "",
    @SerialName("search_titles")
    val searchTitles: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val views: Long? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val brand: String? = null,
    @SerialName("brand_id")
    val brandId: Long? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("released_at")
    val releasedAt: String? = null,
    @SerialName("created_at_unix")
    val createdAtUnix: Long? = null,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long? = null,
)

/**
 * Decrypted payload of the `x-token` returned by POST /api/v11/handshake.
 * Contains preroll (ad) metadata and the HLS sources for the video.
 *
 * Source URLs are relative paths like `/hls/{hv_id}/{token}` that must be
 * resolved against https://hanime.tv. Entries with kind == "promotion" are
 * premium placeholders (often with an empty src); kind == "normal" are
 * playable streams.
 */
@Serializable
data class HandshakePayload(
    @SerialName("is_preroll_enabled")
    val isPrerollEnabled: Boolean? = null,
    val probability: Double? = null,
    @SerialName("preroll_url")
    val prerollUrl: String? = null,
    @SerialName("ad_variant")
    val adVariant: String? = null,
    val sources: List<HandshakeSource> = emptyList(),
)

@Serializable
data class HandshakeSource(
    val src: String = "",
    val type: String? = null,
    val height: Int? = null,
    val width: Long? = null,
    val label: String? = null,
    /** "normal" for playable streams, "promotion" for ad/premium placeholders. */
    val kind: String? = null,
)
