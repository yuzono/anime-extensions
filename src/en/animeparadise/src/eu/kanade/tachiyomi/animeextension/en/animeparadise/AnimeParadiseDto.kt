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
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@AnimeObject.title
        thumbnail_url = posterImage.original ?: posterImage.large ?: posterImage.medium ?: posterImage.small
        url = LinkData(slug = link, id = id).toJsonString()
    }
}

@Serializable
class ImageObject(
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

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
    private val synopsys: String? = null,
    private val genres: List<String>? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        description = synopsys
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
