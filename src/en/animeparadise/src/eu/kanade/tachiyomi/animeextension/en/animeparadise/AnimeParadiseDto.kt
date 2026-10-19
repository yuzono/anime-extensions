package eu.kanade.tachiyomi.animeextension.en.animeparadise

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Pagination(
    val hasNext: Boolean,
)

@Serializable
class AnimeListResponse(
    val data: List<AnimeObject>,
    val pagination: Pagination,
)

@Serializable
class RecentEpisodesResponse(
    val data: List<RecentEpisodeObject>,
    val pagination: Pagination,
)

@Serializable
class RecentEpisodeObject(
    val origin: AnimeObject,
)

@Serializable
class AnimeObject(
    @SerialName("_id") val id: String,
    val title: String,
    val link: String,
    val posterImage: ImageObject,
    val alternativeTitle: AlternativeTitle? = null,
) {
    fun toSAnime(titleLang: String): SAnime = SAnime.create().apply {
        title = alternativeTitle.pick(titleLang) ?: this@AnimeObject.title
        thumbnail_url = posterImage.url
        url = LinkData(slug = link, id = id).toJsonString()
    }
}

@Serializable
class ImageObject(
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
) {
    // Kitsu "original" links are often pre-signed S3 URLs that expire after 15 minutes
    val url get() = large ?: medium ?: small ?: original?.takeUnless { "X-Amz-" in it }
}

@Serializable
class AlternativeTitle(
    val english: String? = null,
    val native: String? = null,
)

private fun AlternativeTitle?.pick(titleLang: String): String? = when (titleLang) {
    "english" -> this?.english
    "native" -> this?.native
    else -> null
}?.takeIf { it.isNotBlank() }

@Serializable
class LinkData(
    val slug: String,
    val id: String,
)

@Serializable
class AnimeDetailsResponse(
    val data: AnimeDetails,
)

@Serializable
class AnimeDetails(
    private val title: String,
    private val alternativeTitle: AlternativeTitle? = null,
    private val synopsys: String? = null,
    private val genres: List<String>? = null,
    private val posterImage: ImageObject? = null,
) {
    fun toSAnime(titleLang: String): SAnime = SAnime.create().apply {
        val displayTitle = alternativeTitle.pick(titleLang) ?: this@AnimeDetails.title
        title = displayTitle
        val otherTitles = listOfNotNull(this@AnimeDetails.title, alternativeTitle?.english, alternativeTitle?.native)
            .filter { it.isNotBlank() && it != displayTitle }
            .distinct()
        description = buildString {
            if (otherTitles.isNotEmpty()) append("Also known as: ${otherTitles.joinToString()}\n\n")
            synopsys?.let(::append)
        }.trim().ifEmpty { null }
        thumbnail_url = posterImage?.url
        genre = genres?.joinToString()
    }
}

@Serializable
class EpisodeListResponse(
    val data: List<EpisodeObject>,
)

@Serializable
class EpisodeObject(
    private val uid: String,
    private val origin: String,
    private val number: String? = null,
    private val title: String? = null,
) {
    fun toSEpisode(): SEpisode = SEpisode.create().apply {
        episode_number = number?.toFloatOrNull() ?: 1F
        name = (number?.let { "Ep. $number" } ?: "Episode") + (title?.let { " - $it" } ?: "")
        url = "/watch/$uid?origin=$origin"
    }
}

@Serializable
class EpisodeDataResponse(
    val data: EpisodeData,
)

@Serializable
class EpisodeData(
    val episode: StreamEpisode,
)

@Serializable
class StreamEpisode(
    val streamLink: String? = null,
    val subData: List<SubtitleObject>? = null,
)

@Serializable
class SubtitleObject(
    val src: String,
    val label: String,
    val type: String,
)
