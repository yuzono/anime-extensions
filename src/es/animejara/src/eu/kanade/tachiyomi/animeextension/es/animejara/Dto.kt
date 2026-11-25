package eu.kanade.tachiyomi.animeextension.es.animejara

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `data-anime` attribute of a catalog card. */
@Serializable
class CardDto(
    val titulo: String,
    val poster: String,
)

@Serializable
class SeasonDto(
    @SerialName("numero_temporada") val number: Int,
    @SerialName("episodios") val episodes: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    @SerialName("numero_episodio") val number: String,
    @SerialName("nombre_episodio") val title: String? = null,
    @SerialName("fecha_actualizacion") val updatedAt: String? = null,
    @SerialName("idiomas") val languages: List<String> = emptyList(),
)

/** Returns the JSON array that follows [marker] in a script, cutting it at its matching bracket. */
fun String.jsonArrayAfter(marker: String): String? {
    val start = indexOf('[', indexOf(marker).takeIf { it >= 0 } ?: return null)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until length) {
        val c = this[i]
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '[' -> depth++
            c == ']' && --depth == 0 -> return substring(start, i + 1)
        }
    }
    return null
}
