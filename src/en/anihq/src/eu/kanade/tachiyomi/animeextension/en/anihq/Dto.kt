package eu.kanade.tachiyomi.animeextension.en.anihq

import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone

@Serializable
class SearchResponseDto(val data: SearchDataDto)

@Serializable
class SearchDataDto(
    val html: String,
    @SerialName("max_pages") val maxPages: Int,
    @SerialName("current_page") val currentPage: Int,
)

@Serializable
class RecommendedResponseDto(
    @SerialName("result") val html: String,
)

@Serializable
class EpisodeResponseDto(val data: EpisodeDataDto)

@Serializable
class EpisodeDataDto(
    val episodes: List<EpisodeItemDto>,
    @SerialName("max_episodes_page") val maxEpisodesPage: Int,
)

@Serializable
class EpisodeItemDto(
    val number: String,
    val released: String,
    val url: String,
    @SerialName("meta_number") val metaNumber: String,
) {
    fun toSEpisode(dateFormat: SimpleDateFormat) = SEpisode.create().apply {
        val variant = audioVariant(this@EpisodeItemDto.url)
        name = number
        episode_number = metaNumber.toFloatOrNull() ?: number.toFloatOrNull() ?: 1F
        url = this@EpisodeItemDto.url
        scanlator = variant
        date_upload = parseReleasedDate(released, dateFormat)
    }

    private fun audioVariant(slug: String): String? = when {
        slug.contains("dubbed", true) -> "Dub"
        slug.contains("subbed", true) -> "Sub"
        else -> null
    }

    private fun parseReleasedDate(released: String, dateFormat: SimpleDateFormat): Long = try {
        released.toIntOrNull()?.let { days ->
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(1900, Calendar.JANUARY, 1, 0, 0, 0)
                add(Calendar.DAY_OF_YEAR, days)
            }.timeInMillis
        } ?: dateFormat.tryParse(released)
    } catch (_: Exception) {
        0L
    }
}
