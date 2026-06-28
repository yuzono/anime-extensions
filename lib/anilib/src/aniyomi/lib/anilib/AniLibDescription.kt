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
    val titleStyle: String = "userPreferred",
)

private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val CLOSE_P_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("<[^>]+>")

/**
 * Build a rich, formatted description string from this [MediaSnapshot].
 *
 * Produces a markdown-formatted description with metadata sections above
 * the AniList synopsis. The host app renders markdown in `SAnime.description`,
 * so bold labels, star ratings, and links display with proper styling.
 *
 * Sections are controlled via [options] — disable any you don't want.
 */
fun MediaSnapshot.buildDescription(options: DescriptionOptions = DescriptionOptions()): String {
    val sections = mutableListOf<String>()

    // ---- Score ----
    if (options.score && averageScore != null && averageScore > 0) {
        sections.add(fancyScore(averageScore))
    }

    // ---- Quick-info line ----
    val infoParts = mutableListOf<String>()
    if (options.status && !status.isNullOrBlank()) {
        infoParts.add("**${labelStatus(status)}**")
    }
    if (options.format && !format.isNullOrBlank()) {
        infoParts.add(labelFormat(format))
    }
    if (options.episodes && episodes != null && episodes > 0) {
        val epLabel = if (episodes == 1) "1 episode" else "$episodes episodes"
        val durPart = if (options.duration && duration != null && duration > 0) ", $duration min" else ""
        infoParts.add("$epLabel$durPart")
    } else if (options.duration && duration != null && duration > 0) {
        infoParts.add("$duration min")
    }
    if (options.season && !season.isNullOrBlank() && seasonYear != null) {
        infoParts.add("${labelSeason(season)} $seasonYear")
    }
    if (infoParts.isNotEmpty()) {
        sections.add(infoParts.joinToString(" • "))
    }

    // ---- Studio ----
    if (options.studio) {
        val studio = AniLib.resolveMainStudio(studios)
        if (studio.isNotBlank()) {
            sections.add("**Studio:** $studio")
        }
    }

    // ---- Genres ----
    if (options.genres && genres.isNotEmpty()) {
        sections.add("**Genres:** ${genres.joinToString()}")
    }

    // ---- Tags ----
    if (options.tags && !tags.isNullOrEmpty()) {
        val displayTags = tags
            .filter { !it.isGeneralSpoiler && !it.isMediaSpoiler }
            .sortedByDescending { it.rank }
            .take(options.maxTagCount)
            .mapNotNull { it.name }
        if (displayTags.isNotEmpty()) {
            sections.add("**Tags:** ${displayTags.joinToString()}")
        }
    }

    // ---- Synonyms ----
    if (options.synonyms && synonyms.isNotEmpty()) {
        val primaryTitle = AniLib.resolveTitle(title, options.titleStyle).orEmpty()
        val allTitleVariants = listOfNotNull(title?.userPreferred, title?.romaji, title?.english, title?.native)
        val filtered = synonyms.filter { !it.isNullOrBlank() && it != primaryTitle && it !in allTitleVariants }
        if (filtered.isNotEmpty()) {
            sections.add("*Also known as: ${filtered.joinToString()}*")
        }
    }

    // ---- Relations ----
    if (options.relations && relations?.edges?.isNotEmpty() == true) {
        val relationLines = relations.edges.mapNotNull { edge ->
            val type = edge.relationType ?: return@mapNotNull null
            val node = edge.node ?: return@mapNotNull null
            val name = AniLib.resolveTitle(node.title, options.titleStyle) ?: return@mapNotNull null
            val fmt = node.format?.let { labelFormat(it) } ?: ""
            val eps = node.episodes?.takeIf { it > 0 }?.let { "$it episodes" }
            val details = listOfNotNull(fmt, eps).joinToString(", ")
            val namePart = if (node.siteUrl.isNullOrBlank()) {
                name
            } else {
                "[$name](${node.siteUrl})"
            }
            "**$type:** $namePart" + if (details.isNotBlank()) " — $details" else ""
        }
        if (relationLines.isNotEmpty()) {
            sections.add(relationLines.joinToString("\n"))
        }
    }

    // ---- Trailer ----
    if (options.trailer && trailer?.url() != null) {
        sections.add("[▶ Watch Trailer](${trailer.url()})")
    }

    // ---- Synopsis ----
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
            if (isNotEmpty()) append("\n\n---\n\n")
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

private fun fancyScore(score: Int): String {
    val stars = (score / 20.0).toInt().coerceIn(1, 5)
    return "${"★".repeat(stars)}${"☆".repeat(5 - stars)} $score%"
}

private fun stripHtml(input: String): String = input
    .replace(BR_REGEX, "\n")
    .replace(CLOSE_P_REGEX, "\n")
    .replace(HTML_TAG_REGEX, "")
    .trim()

private fun labelStatus(status: String): String = when (status.uppercase()) {
    "RELEASING" -> "Airing"
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
