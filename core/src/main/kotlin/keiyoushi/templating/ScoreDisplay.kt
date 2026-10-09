package keiyoushi.templating

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Utility for displaying scores as star ratings.
 *
 * Used by extensions that display ratings from sources (e.g., AnikotoTheme,
 * AnimeKaiTheme, YFlixTheme).
 */
object ScoreDisplay {
    /**
     * Convert a numeric score to a star rating string.
     *
     * @param score Score as string (e.g., "8.5")
     * @param maxStars Maximum number of stars (default 5)
     * @return Star rating string (e.g., "★★★★☆ 8.5")
     */
    fun toStarRating(score: String?, maxStars: Int = 5): String {
        if (score.isNullOrBlank()) return ""

        return try {
            val scoreBig = BigDecimal(score)
            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP).toInt()
                .coerceIn(0, maxStars)
            val emptyStars = maxStars - stars
            "${"★".repeat(stars)}${"☆".repeat(emptyStars)} $score"
        } catch (e: NumberFormatException) {
            ""
        }
    }

    /**
     * Convert a numeric score to just the star characters (no score number).
     *
     * @param score Score as string (e.g., "8.5")
     * @param maxStars Maximum number of stars (default 5)
     * @return Star characters only (e.g., "★★★★☆")
     */
    fun toStarsOnly(score: String?, maxStars: Int = 5): String {
        if (score.isNullOrBlank()) return ""

        return try {
            val scoreBig = BigDecimal(score)
            val stars = scoreBig.divide(BigDecimal(2), 0, RoundingMode.HALF_UP).toInt()
                .coerceIn(0, maxStars)
            val emptyStars = maxStars - stars
            "${"★".repeat(stars)}${"☆".repeat(emptyStars)}"
        } catch (e: NumberFormatException) {
            ""
        }
    }
}

/**
 * Convert a score string to a star rating.
 */
fun String?.toStarRating(maxStars: Int = 5): String = ScoreDisplay.toStarRating(this, maxStars)

/**
 * Convert a score string to stars only.
 */
fun String?.toStarsOnly(maxStars: Int = 5): String = ScoreDisplay.toStarsOnly(this, maxStars)
