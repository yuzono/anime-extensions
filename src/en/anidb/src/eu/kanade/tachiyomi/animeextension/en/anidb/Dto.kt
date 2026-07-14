package eu.kanade.tachiyomi.animeextension.en.anidb

import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class EpisodeResponseDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    val id: Long,
    val number: Double,
    val number2: Double? = null,
    val filler: Boolean = false,
) {
    fun toSEpisode(offset: Float, showFillerTag: Boolean = true): SEpisode = SEpisode.create().apply {
        val num = number.toFloat()
        val num2 = number2?.toFloat()
        val adjustedNumber = num - offset
        val adjustedNumber2 = num2?.let { it - offset }

        val label = if (adjustedNumber2 != null && adjustedNumber2 != 0f && adjustedNumber2 != adjustedNumber) {
            "${adjustedNumber.toString().removeSuffix(".0")}\u2013${adjustedNumber2.toString().removeSuffix(".0")}"
        } else {
            adjustedNumber.toString().removeSuffix(".0")
        }

        name = "Episode $label"
        if (filler && showFillerTag) name += " (Filler)"
        episode_number = adjustedNumber
        url = id.toString()
    }
}

@Serializable
class LanguageResponseDto(
    val languages: List<LanguageDto>,
)

@Serializable
class LanguageDto(
    val name: String,
    @SerialName("embed_url") val embedUrl: String,
)
