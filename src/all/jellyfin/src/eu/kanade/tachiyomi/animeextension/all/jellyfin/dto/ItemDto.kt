package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import eu.kanade.tachiyomi.animeextension.all.jellyfin.format
import eu.kanade.tachiyomi.animeextension.all.jellyfin.getImageUrl
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.utils.formatBytes
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.parser.Parser
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class ItemListDto(
    val items: List<ItemDto>,
    val totalRecordCount: Int,
)

@Serializable
class ItemDto(
    // Common
    val name: String,
    val type: ItemType,
    val id: String,
    val locationType: String,
    val imageTags: ImageDto,

    // Libraries
    val collectionType: String? = null,

    // Anime
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val backdropImageTags: List<String>? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String>? = null,

    // Anime Details
    val status: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val studios: List<StudioDto>? = null,

    // Episode
    val originalTitle: String? = null,
    val sortName: String? = null,
    val indexNumber: Int? = null,
    val premiereDate: String? = null,
    val runTimeTicks: Long? = null,
    val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null,
) {
    @Serializable
    class ImageDto(
        val primary: String? = null,
    )

    @Serializable
    class StudioDto(
        val name: String,
    )

    // =============================== Anime ================================

    fun toSAnime(baseUrl: String, userId: String, concatenateNames: Boolean): SAnime = SAnime.create().apply {
        val typeMap = mapOf(
            ItemType.Season to "season,$seriesId",
            ItemType.Movie to "movie",
            ItemType.BoxSet to "boxSet",
            ItemType.Series to "series",
            // Not using ItemType.Episode (episode) here
        )
        fetch_type = when (type) {
            ItemType.BoxSet, ItemType.Series -> FetchType.Seasons
            ItemType.Movie, ItemType.Season, ItemType.Episode, ItemType.Other -> FetchType.Episodes
        }
        url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Users")
            addPathSegment(userId)
            addPathSegment("Items")
            addPathSegment(id)
            fragment(typeMap[type])
        }.build().toString()
        thumbnail_url = imageTags.primary?.getImageUrl(baseUrl, id)
        background_url = when {
            backdropImageTags?.firstOrNull() != null -> {
                backdropImageTags.first().getImageUrl(baseUrl, id, "Backdrop", 0)
            }

            parentBackdropImageTags?.firstOrNull() != null && parentBackdropItemId != null -> {
                parentBackdropImageTags.first().getImageUrl(baseUrl, parentBackdropItemId, "Backdrop", 0)
            }

            else -> thumbnail_url
        }
        title = name
        description = overview?.let(::convertHtml)
        genre = genres?.joinToString()
        author = studios?.joinToString { it.name }
        season_number = indexNumber?.toDouble() ?: -1.0

        status = if (type == ItemType.Movie) {
            SAnime.COMPLETED
        } else {
            this@ItemDto.status.parseStatus()
        }

        if (type == ItemType.Season) {
            if (locationType == "Virtual") {
                title = seriesName ?: "Season"
                seriesId?.let {
                    thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it)
                }
            } else {
                title = buildString {
                    if (concatenateNames) {
                        seriesName?.takeIf { it.isNotBlank() }?.let {
                            append(it)
                            append(" ")
                        }
                    }
                    append(name)
                }
            }

            // Use series image as fallback
            if (imageTags.primary == null) {
                seriesId?.let {
                    thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it)
                }
            }
        }
    }

    private fun convertHtml(html: String): String {
        var markdown = html
        markdown = BOLD_REGEX.replace(markdown, "**$2**")
        markdown = ITALICS_REGEX.replace(markdown, "*$2*")
        markdown = BREAK_REGEX.replace(markdown, "\n")
        markdown = HORIZONTAL_RULE_REGEX.replace(markdown, "\n---\n")
        markdown = TAG_REGEX.replace(markdown, " ")
        return Parser.unescapeEntities(markdown, false)
    }

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ended" -> SAnime.COMPLETED
        "continuing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episode ===============================

    fun toSEpisode(
        baseUrl: String,
        userId: String,
        epDetails: Set<String>,
        episodeTemplate: String,
    ): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000)
        val size = mediaSources?.first()?.size?.formatBytes()
        val runTime = runtimeInSec?.formatSeconds()
        val title = if (type == ItemType.Movie) "" else this@ItemDto.name

        val values = mapOf(
            "title" to title,
            "originalTitle" to (originalTitle ?: ""),
            "sortTitle" to (sortName ?: ""),
            "type" to type.name,
            "typeShort" to type.name.replace("Episode", "Ep."),
            "seriesTitle" to (seriesName ?: ""),
            "seasonTitle" to (seasonName ?: ""),
            "number" to (indexNumber ?: 1),
            "createdDate" to (dateCreated?.substringBefore("T") ?: ""),
            "releaseDate" to (premiereDate?.substringBefore("T") ?: ""),
            "size" to (size ?: ""),
            "sizeBytes" to (mediaSources?.first()?.size ?: 0L),
            "runtime" to (runTime ?: ""),
            "runtimeS" to (runtimeInSec ?: 0L),
        )
        val extraInfo = buildList {
            if (epDetails.contains("Overview") && overview != null && type == ItemType.Episode) {
                add(overview)
            }
            if (epDetails.contains("Size") && size != null) {
                add(size)
            }
            if (epDetails.contains("Runtime") && runTime != null) {
                add(runTime)
            }
        }

        name = format(values, episodeTemplate).trim()
            .removeSuffix("-")
            .removePrefix("-")
            .trim()
        url = "$baseUrl/Users/$userId/Items/$id"
        scanlator = extraInfo.joinToString(" • ")
        summary = overview?.let(::convertHtml)
        preview_url = imageTags.primary?.getImageUrl(baseUrl, id)
        premiereDate?.let {
            date_upload = parseDateTime(it.removeSuffix("Z"))
        }
        if (type == ItemType.Movie) {
            episode_number = 1F
        }
    }

    private fun Long.formatSeconds(): String {
        val minutes = this / 60
        val hours = minutes / 60

        val remainingSeconds = this % 60
        val remainingMinutes = minutes % 60

        val formattedHours = if (hours > 0) "${hours}h " else ""
        val formattedMinutes = if (remainingMinutes > 0) "${remainingMinutes}m " else ""
        val formattedSeconds = "${remainingSeconds}s"

        return "$formattedHours$formattedMinutes$formattedSeconds".trim()
    }

    private fun parseDateTime(date: String) = try {
        FORMATTER_DATE_TIME.parse(date.removeSuffix("Z"))!!.time
    } catch (_: ParseException) {
        0L
    }

    companion object {
        private val BOLD_REGEX = Regex("""<(b|strong)>(.*?)</\1>""", RegexOption.IGNORE_CASE)
        private val ITALICS_REGEX = Regex("""<(i|em)>(.*?)</\1>""", RegexOption.IGNORE_CASE)
        private val BREAK_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HORIZONTAL_RULE_REGEX = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE)
        private val TAG_REGEX = Regex("""<[^>]*>""", RegexOption.IGNORE_CASE)

        private val FORMATTER_DATE_TIME = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS",
            Locale.ENGLISH,
        )
    }
}

@Serializable
class SessionDto(
    val mediaSources: List<MediaDto>,
    val playSessionId: String,
)

@Serializable
class MediaDto(
    val size: Long? = null,
    val id: String? = null,
    val bitrate: Int? = null,
    val transcodingUrl: String? = null,
    val transcodingContainer: String? = null,
    val isRemote: Boolean = false,
    val path: String? = null,
    val name: String? = null,
    val supportsTranscoding: Boolean,
    val supportsDirectStream: Boolean,
    // Can be empty for .strm items that haven't been probed yet
    val mediaStreams: List<MediaStreamDto> = emptyList(),
) {
    private val videoStream
        get() = mediaStreams.firstOrNull { it.type == "Video" }

    private val audioStream
        get() = mediaStreams.firstOrNull { it.type == "Audio" && it.isDefault }
            ?: mediaStreams.firstOrNull { it.type == "Audio" }

    /** Short summary of the video stream, e.g. "1080p H264 SDR" or "4K HEVC DV" */
    val videoInfo: String?
        get() = videoStream?.run {
            listOfNotNull(resolution, codec?.uppercase(), dynamicRange).joinToString(" ")
        }?.ifEmpty { null }

    /** Short summary of the default audio stream, e.g. "AAC stereo" or "EAC3 5.1" */
    val audioInfo: String?
        get() = audioStream?.run {
            listOfNotNull(codec?.uppercase(), channels?.toChannelLayout()).joinToString(" ")
        }?.ifEmpty { null }

    /** Bitrate of the video stream alone, without audio */
    val videoBitrate: Int?
        get() = videoStream?.bitRate

    @Serializable
    class MediaStreamDto(
        val codec: String? = null,
        val index: Int,
        val type: String,
        val supportsExternalStream: Boolean,
        val isExternal: Boolean,
        val isDefault: Boolean = false,
        val language: String? = null,
        val displayTitle: String? = null,
        val bitRate: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val videoRange: String? = null,
        val videoRangeType: String? = null,
        val channels: Int? = null,
    ) {
        // Same thresholds as Jellyfin's own display title, but also for portrait videos
        val resolution: String?
            get() {
                val width = width ?: return null
                val height = height ?: return null
                val w = maxOf(width, height)
                val h = minOf(width, height)
                return RESOLUTIONS.firstOrNull { (maxWidth, maxHeight) -> w <= maxWidth && h <= maxHeight }?.third
            }

        val dynamicRange: String?
            get() = when (val type = videoRangeType) {
                null, "Unknown", "DOVIInvalid" -> videoRange?.takeUnless { it == "Unknown" }
                "HDR10Plus" -> "HDR10+"
                else -> if (type.startsWith("DOVI")) "DV" else type
            }
    }

    private fun Int.toChannelLayout(): String = when (this) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${this}ch"
    }

    companion object {
        private val RESOLUTIONS = listOf(
            Triple(256, 144, "144p"),
            Triple(426, 240, "240p"),
            Triple(640, 360, "360p"),
            Triple(682, 384, "384p"),
            Triple(720, 404, "404p"),
            Triple(854, 480, "480p"),
            Triple(960, 544, "540p"),
            Triple(1024, 576, "576p"),
            Triple(1280, 962, "720p"),
            Triple(2560, 1440, "1080p"),
            Triple(4096, 3072, "4K"),
            Triple(8192, 6144, "8K"),
        )
    }
}
