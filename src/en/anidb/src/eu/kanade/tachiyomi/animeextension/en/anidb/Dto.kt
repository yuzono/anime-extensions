package eu.kanade.tachiyomi.animeextension.en.anidb

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
class HomeDto(
    val sections: List<SectionDto>,
)

@Serializable
class SectionDto(
    val name: String,
    val posts: List<PostItemDto>,
)

@Serializable
class PostListDto(
    val posts: List<PostItemDto>,
)

@Serializable
class PostItemDto(
    val id: Long,
)

@Serializable
class PostDto(
    private val id: Long,
    private val title: String,
    private val type: String? = null,
    private val poster: String? = null,
    private val overview: String? = null,
    private val status: String? = null,
    private val runtime: String? = null,
    private val premiered: String? = null,
    private val age: String? = null,
    private val score: String? = null,
    private val genres: String? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = id.toString()
        title = this@PostDto.title
        thumbnail_url = poster
        genre = genres
        status = when (this@PostDto.status?.lowercase()) {
            "currently airing" -> SAnime.ONGOING
            "finished airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        description = buildString {
            score?.toFloatOrNull()?.let {
                val filled = (it / 2.0).roundToInt().coerceIn(0, 5)
                append("★".repeat(filled) + "☆".repeat(5 - filled) + " $it\n\n")
            }
            overview?.let { append(it) }
            val meta = listOfNotNull(
                type?.let { "**Type:** $it" },
                premiered?.let { "**Season:** $it" },
                runtime?.let { "**Duration:** $it" },
                age?.let { "**Rating:** $it" },
            ).joinToString(" | ")
            if (meta.isNotEmpty()) append("\n\n$meta")
        }.trim()
    }
}

@Serializable
class EpisodeListDto(
    val list: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    private val id: String,
    val number: String,
    private val filler: Boolean = false,
) {
    fun toSEpisode(offset: Float): SEpisode = SEpisode.create().apply {
        val adjustedNumber = number.toFloatOrNull()?.minus(offset) ?: 0f
        name = "Episode ${adjustedNumber.toString().removeSuffix(".0")}"
        episode_number = adjustedNumber
        fillermark = filler
        url = id
    }
}

@Serializable
class ServerListDto(
    val list: List<ServerDto>,
)

@Serializable
class ServerDto(
    val id: String,
)

@Serializable
class IframeDto(
    val link: String,
)
