package eu.kanade.tachiyomi.animeextension.en.asiaflix.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagedDto<T>(
    val hasNext: Boolean = false,
    val body: List<T> = emptyList(),
)

@Serializable
data class NameDto(val name: String = "")

@Serializable
data class EntryDto(
    val name: String = "",
    val slug: String = "",
    val altNames: List<String> = emptyList(),
    val synopsis: String? = null,
    val description: String? = null,
    val image: String? = null,
    val coverImage: String? = null,
    // the v1 API emits "genres": [{"name": …}] and "status": "Ongoing|Completed"
    @SerialName("genres") val genre: List<NameDto> = emptyList(),
    @SerialName("status") val tvStatus: String? = null,

    // some dramas return "episodes": null explicitly
    val episodes: List<EpisodeDto>? = null,
) {
    fun toSAnime() = SAnime.create().apply {
        title = name
        url = slug
        thumbnail_url = image ?: coverImage
        status = when (tvStatus) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        genre = this@EntryDto.genre.joinToString { it.name }

        val altNamesBlock = altNames.joinToString("\n") { "• ${it.trim()}" }
            .takeIf { it.isNotEmpty() }
            ?.let { "\n\nAlternative Names: \n$it" }
            .orEmpty()
        description = (synopsis ?: description.orEmpty()) + altNamesBlock
    }
}

@Serializable
data class EpisodeDto(
    val number: Float,
    val type: String? = null,
    val epUrl: String? = null,
    val streamUrls: List<StreamUrlDto> = emptyList(),
)

@Serializable
data class StreamUrlDto(
    val source: String = "",
    val url: String = "",
)

// the site re-uses identical embed URLs across consecutive episodes of a drama;
// wrapping the episode number keeps every SEpisode.url unique so the app's
// per-anime unique episode url constraint does not collapse them into one row
@Serializable
data class EpisodePayload(
    val n: Float,
    val urls: List<StreamUrlDto> = emptyList(),
)
