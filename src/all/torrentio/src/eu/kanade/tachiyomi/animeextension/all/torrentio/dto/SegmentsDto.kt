package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SegmentsDto(
    private val intro: Segments? = null,
    private val outro: Segments? = null,
    @SerialName("post_credits")
    private val postCredits: Segments? = null,
    private val recap: Segments? = null,
) {
    fun toTimeStamps(): List<TimeStamp> = listOfNotNull(
        recap?.toTimeStamp("Recap", ChapterType.Recap),
        intro?.toTimeStamp("Intro", ChapterType.Opening),
        outro?.toTimeStamp("Outro", ChapterType.Ending),
        postCredits?.toTimeStamp("Post-Credits", ChapterType.Other),
    ).sortedBy { it.start }
}

@Serializable
class Segments(
    @SerialName("start_sec")
    private val startSec: Double? = null,
    @SerialName("end_sec")
    private val endSec: Double? = null,
) {
    fun toTimeStamp(name: String, type: ChapterType): TimeStamp? {
        val end = endSec ?: return null
        val start = startSec ?: 0.0
        return if (end > start) TimeStamp(start, end, name, type) else null
    }
}
