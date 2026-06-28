package aniyomi.lib.anilib

/**
 * Controls which sections are included in the built description string.
 *
 * All sections default to `true`. Set to `false` to exclude a section.
 */
data class DescriptionOptions(
    val genres: Boolean = true,
    val tags: Boolean = true,
    val score: Boolean = true,
    val status: Boolean = true,
    val format: Boolean = true,
    val episodes: Boolean = true,
    val duration: Boolean = true,
    val season: Boolean = true,
    val studio: Boolean = true,
    val synonyms: Boolean = true,
    val relations: Boolean = true,
    val trailer: Boolean = true,
    val stripHtml: Boolean = true,
    val maxTagCount: Int = 10,
    val truncateAt: Int = 0,
)

private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val CLOSE_P_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("<[^>]+>")

/**
 * Build a rich, formatted description string from this [MediaSnapshot].
 *
 * Produces a markdown-like description with metadata sections above the
 * AniList synopsis, making the detail view informative even when the
 * site backend provides limited data.
 *
 * Sections are controlled via [options] — disable any you don't want.
 */
fun MediaSnapshot.buildDescription(options: DescriptionOptions = DescriptionOptions()): String {
    val sections = mutableListOf<String>()

    if (options.genres && genres.isNotEmpty()) {
        sections.add(genres.joinToString(" • ") { "⊛ $it" })
    }

    if (options.tags && !tags.isNullOrEmpty()) {
        val displayTags = tags
            .filter { !it.isGeneralSpoiler && !it.isMediaSpoiler }
            .sortedByDescending { it.rank }
            .take(options.maxTagCount)
            .mapNotNull { it.name }
        if (displayTags.isNotEmpty()) {
            sections.add(displayTags.joinToString(" • ") { "⊞ $it" })
        }
    }

    val infoParts = mutableListOf<String>()

    if (options.score && averageScore != null && averageScore > 0) {
        infoParts.add("★ $averageScore%")
    }

    if (options.status && !status.isNullOrBlank()) {
        infoParts.add(labelStatus(status))
    }

    if (options.format && !format.isNullOrBlank()) {
        infoParts.add(labelFormat(format))
    }

    if (options.episodes && episodes != null && episodes > 0) {
        infoParts.add("$episodes eps")
    }

    if (options.duration && duration != null && duration > 0) {
        infoParts.add("$duration min")
    }

    if (options.season && !season.isNullOrBlank() && seasonYear != null) {
        infoParts.add("${labelSeason(season)} $seasonYear")
    }

    if (options.studio) {
        val studio = AniLib.resolveMainStudio(studios)
        if (studio.isNotBlank()) {
            infoParts.add("Studio: $studio")
        }
    }

    if (infoParts.isNotEmpty()) {
        sections.add(infoParts.joinToString(" │ "))
    }

    if (options.synonyms && synonyms.isNotEmpty()) {
        val filtered = synonyms.filter { !it.isNullOrBlank() && it != title?.userPreferred && it != title?.romaji && it != title?.english }
        if (filtered.isNotEmpty()) {
            sections.add("Also: " + filtered.joinToString(", "))
        }
    }

    if (options.relations && relations?.edges?.isNotEmpty() == true) {
        val relationLines = relations.edges.mapNotNull { edge ->
            val type = edge.relationType ?: return@mapNotNull null
            val node = edge.node ?: return@mapNotNull null
            val name = AniLib.resolveTitle(node.title) ?: return@mapNotNull null
            val format = node.format?.let { labelFormat(it) } ?: ""
            val eps = node.episodes?.let { if (it > 0) " ($it eps)" else "" } ?: ""
            "$type: $name${" " + format}$eps"
        }
        if (relationLines.isNotEmpty()) {
            sections.add(relationLines.joinToString("\n"))
        }
    }

    if (options.trailer && trailer?.url() != null) {
        sections.add("Trailer: ${trailer.url()}")
    }

    val synopsis = if (!description.isNullOrBlank()) {
        val cleaned = if (options.stripHtml) stripHtml(description) else description
        cleaned.trim()
    } else {
        null
    }

    val result = buildString {
        if (sections.isNotEmpty()) {
            append(sections.joinToString("\n\n"))
        }
        if (!synopsis.isNullOrBlank()) {
            if (isNotEmpty()) append("\n\n―\n\n")
            append(synopsis)
        }
    }

    return if (options.truncateAt > 0 && result.length > options.truncateAt) {
        val cutIndex = result.lastIndexOf(' ', options.truncateAt)
        if (cutIndex > options.truncateAt * 2 / 3) {
            result.substring(0, cutIndex) + "\u2026"
        } else {
            result.substring(0, options.truncateAt) + "\u2026"
        }
    } else {
        result
    }
}

private fun stripHtml(input: String): String = input
    .replace(BR_REGEX, "\n")
    .replace(CLOSE_P_REGEX, "\n")
    .replace(HTML_TAG_REGEX, "")
    .trim()

private fun labelStatus(status: String): String = when (status.uppercase()) {
    "RELEASING" -> "Releasing"
    "FINISHED" -> "Finished"
    "CANCELLED" -> "Cancelled"
    "HIATUS" -> "Hiatus"
    "NOT_YET_RELEASED" -> "Unreleased"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun labelFormat(format: String): String = when (format.uppercase()) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.replaceFirstChar { it.uppercase() }
}

private fun labelSeason(season: String): String = when (season.uppercase()) {
    "WINTER" -> "Winter"
    "SPRING" -> "Spring"
    "SUMMER" -> "Summer"
    "FALL" -> "Fall"
    else -> season.replaceFirstChar { it.uppercase() }
}
