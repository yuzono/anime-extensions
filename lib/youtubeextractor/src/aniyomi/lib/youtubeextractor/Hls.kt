package aniyomi.lib.youtubeextractor

import aniyomi.lib.playlistutils.HlsVariant
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

/**
 * Labels HLS variants with codec, frame rate, dynamic range and estimated bandwidth.
 * Variants are sorted by the labelled rate so callers can retain the best of otherwise identical streams.
 */
internal fun List<HlsVariant>.toDetailedVideos(
    headers: Headers,
    standardQuality: (String) -> String,
    videoNameGen: (String) -> String,
): List<Video> = sortedByDescending { it.attributes.dataRate() ?: 0L }.map { variant ->
    val attributes = variant.attributes
    val resolution = attributes["RESOLUTION"]?.let { resolution ->
        val height = QUALITY_REGEX.find(resolution)?.groupValues?.get(1)?.let(standardQuality)
        if (!height.isNullOrBlank()) "$height ($resolution)" else resolution
    }
    val bandwidth = attributes.dataRate()?.takeIf { it > 0 }?.let { "~%.2f Mbps".format(it / 1_000_000.0) }
    val streamName = listOfNotNull(
        resolution,
        attributes["CODECS"]?.let(::formatCodecs),
        attributes["FRAME-RATE"]?.let { "$it fps" },
        attributes["VIDEO-RANGE"]?.takeUnless { it == "SDR" },
    ).joinToString(" - ").ifBlank { "Video" }

    Video(
        videoUrl = variant.url,
        videoTitle = videoNameGen(if (bandwidth != null) "$streamName $bandwidth" else streamName),
        headers = headers,
        subtitleTracks = variant.subtitleTracks,
        audioTracks = variant.audioTracks,
    )
}

/** Converts comma-separated RFC 6381 codec identifiers into readable stream labels. */
internal fun formatCodecs(codecs: String): String = codecs.split(',').map { codec ->
    when (codec.trim().substringBefore('.')) {
        "avc1", "avc3" -> "H.264"
        "hev1", "hvc1" -> "HEVC"
        "vp09", "vp9" -> "VP9"
        "av01" -> "AV1"
        "dvhe", "dvh1", "dvav", "dva1" -> "Dolby Vision"
        "mp4a" -> "AAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        else -> codec.trim()
    }
}.distinct().joinToString(" + ")

/** Returns the average rate when the playlist provides one, otherwise the peak BANDWIDTH. */
private fun Map<String, String>.dataRate(): Long? = this["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 } ?: this["BANDWIDTH"]?.toLongOrNull()

private val QUALITY_REGEX = Regex("""[xX](\d+)""")
