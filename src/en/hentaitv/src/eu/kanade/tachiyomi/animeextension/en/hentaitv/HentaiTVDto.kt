package eu.kanade.tachiyomi.animeextension.en.hentaitv

import kotlinx.serialization.Serializable

const val EMBED_UNKNOWN = "unknown"

/** Response of `/api/browse` (28 per page) and `/api/search` (no paging, `total`/`pages` absent). */
@Serializable
class VideoListDto(
    val videos: List<VideoDto> = emptyList(),
    val total: Int = 0,
    val pages: Int = 0,
)

/** One episode, as returned by the site's APIs and embedded in the pages' React Server Component payload. */
@Serializable
class VideoDto(
    val id: String? = null,
    val slug: String = "",
    val title: String? = null,
    val titleSlug: String? = null,
    val ep: Double? = null,
    val views: Long? = null,
    val rating: Double? = null,
    val censored: Boolean? = null,
    val brand: String? = null,
    val quality: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val duration: String? = null,
    val tags: List<String>? = null,
    val cover: String? = null,
    val thumb: String? = null,
    val backdrop: String? = null,
    val featureImage: String? = null,
    /** `null` when the site has no player for the episode, [EMBED_UNKNOWN] when the field was absent. */
    val embedUrl: String? = EMBED_UNKNOWN,
    val description: String? = null,
    val releasedAt: String? = null,
)

/** Answer of nhplayer's `get-video-url-v2.php`. */
@Serializable
class VideoUrlDto(
    val url: String? = null,
    val error: String? = null,
)

/** Everything needed to (re)resolve a server, carried in [eu.kanade.tachiyomi.animesource.model.Video.internalData]. */
@Serializable
data class StreamRef(
    val embedUrl: String,
    val index: Int,
    val name: String,
    val dataId: String,
    val quality: String? = null,
) {
    val key: String get() = "${embedUrl.trimEnd('/').substringAfterLast('/')}-$index"
}
