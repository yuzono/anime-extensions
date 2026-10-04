package keiyoushi.templating

import eu.kanade.tachiyomi.animesource.model.Video

/**
 * Utility functions for video sorting and filtering.
 *
 * These functions can be used directly or via the extension functions
 * at the bottom of this file.
 */
object VideoUtils {
    private val QUALITY_REGEX = Regex("""(\d+)p""")
    private val SERVER_QUALITY_REGEX = Regex("""\[([^\]]+)\].*?(\d+)p""", RegexOption.IGNORE_CASE)

    fun sortByQuality(
        videos: List<Video>,
        quality: String,
        server: String? = null,
    ): List<Video> = videos.sortedWith(
        compareByDescending<Video> { it.quality.contains(quality) }
            .thenByDescending { server?.let { s -> it.quality.contains(s, true) } ?: false }
            .thenByDescending { extractQualityNumber(it.quality) },
    )

    fun filterByExcludedServers(
        videos: List<Video>,
        excludedServers: Set<String>,
    ): List<Video> {
        if (excludedServers.isEmpty()) return videos
        return videos.filter { video ->
            excludedServers.none { excluded ->
                video.quality.contains(excluded, ignoreCase = true)
            }
        }
    }

    fun filterByType(
        videos: List<Video>,
        allowedTypes: Set<String>,
    ): List<Video> {
        if (allowedTypes.isEmpty()) return videos
        return videos.filter { video ->
            allowedTypes.any { type ->
                video.quality.contains(type, ignoreCase = true)
            }
        }
    }

    fun deduplicate(videos: List<Video>): List<Video> = videos.distinctBy { video ->
        "${normalizeQuality(video.quality)}|${video.url}"
    }

    fun deduplicateByQuality(videos: List<Video>): List<Video> {
        val seen = mutableSetOf<String>()
        return videos.filter { video ->
            val normalized = normalizeQuality(video.quality)
            if (normalized in seen) {
                false
            } else {
                seen.add(normalized)
                true
            }
        }
    }

    fun normalizeQuality(quality: String): String {
        val qualityNum = extractQualityNumber(quality)
        return if (qualityNum > 0) "${qualityNum}p" else quality
    }

    fun extractQualityNumber(quality: String): Int = QUALITY_REGEX.find(quality)?.groupValues?.get(1)?.toIntOrNull()
        ?: SERVER_QUALITY_REGEX.find(quality)?.groupValues?.get(2)?.toIntOrNull()
        ?: 0

    fun extractServer(quality: String): String? {
        val match = Regex("""\[([^\]]+)\]""").find(quality)
        return match?.groupValues?.get(1)
    }

    fun filterAndSort(
        videos: List<Video>,
        preferredQuality: String = "720",
        preferredServer: String? = null,
        excludedServers: Set<String> = emptySet(),
        allowedTypes: Set<String> = emptySet(),
        deduplicate: Boolean = true,
    ): List<Video> {
        var result = videos
        if (excludedServers.isNotEmpty()) {
            result = result.filterByExcludedServers(excludedServers)
        }
        if (allowedTypes.isNotEmpty()) {
            result = result.filterByType(allowedTypes)
        }
        if (deduplicate) {
            result = result.deduplicate()
        }
        return result.sortByQuality(preferredQuality, preferredServer)
    }
}

fun List<Video>.sortByQuality(
    quality: String,
    server: String? = null,
): List<Video> = VideoUtils.sortByQuality(this, quality, server)

fun List<Video>.filterByExcludedServers(
    excludedServers: Set<String>,
): List<Video> = VideoUtils.filterByExcludedServers(this, excludedServers)

fun List<Video>.filterByType(
    allowedTypes: Set<String>,
): List<Video> = VideoUtils.filterByType(this, allowedTypes)

fun List<Video>.deduplicate(): List<Video> = VideoUtils.deduplicate(this)

fun List<Video>.deduplicateByQuality(): List<Video> = VideoUtils.deduplicateByQuality(this)

fun List<Video>.normalizeQuality(): List<Video> = map { Video(it.quality, it.url, it.videoUrl) }

fun List<Video>.filterAndSort(
    preferredQuality: String = "720",
    preferredServer: String? = null,
    excludedServers: Set<String> = emptySet(),
    allowedTypes: Set<String> = emptySet(),
    deduplicate: Boolean = true,
): List<Video> = VideoUtils.filterAndSort(this, preferredQuality, preferredServer, excludedServers, allowedTypes, deduplicate)
