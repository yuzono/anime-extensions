package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.SAnime

/**
 * Utility for parsing anime status strings to SAnime status constants.
 */
object StatusParser {
    private val COMPLETED_STATUSES = setOf(
        "completed",
        "completo",
        "finished airing",
        "ended",
        "released",
        "concluído",
        "tamamlandı",
        "завершено",
    )

    private val ONGOING_STATUSES = setOf(
        "ongoing",
        "lançamento",
        "releasing",
        "currently airing",
        "emission",
        "em andamento",
        "devam ediyor",
        "в эфире",
    )

    /**
     * Parse a status string to SAnime status constant.
     *
     * @param statusString Status string from the source
     * @return SAnime status constant (ONGOING, COMPLETED, or UNKNOWN)
     */
    fun parse(statusString: String?): Int {
        val status = statusString?.trim()?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            COMPLETED_STATUSES.any { status.contains(it) } -> SAnime.COMPLETED
            ONGOING_STATUSES.any { status.contains(it) } -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }
}

/**
 * Extension function to parse a status string to SAnime status constant.
 */
fun String?.parseStatus(): Int = StatusParser.parse(this)
