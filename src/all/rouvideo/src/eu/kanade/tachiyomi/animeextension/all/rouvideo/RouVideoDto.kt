package eu.kanade.tachiyomi.animeextension.all.rouvideo

import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideo.Companion.resolutionDesc
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LIKE_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_VIEW_KEY
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

internal object RouVideoDto {
    @Serializable
    data class VideoList(
        val props: PropsObject,
    ) {
        @Serializable
        data class PropsObject(
            val pageProps: PagePropsObject,
        ) {
            @Serializable
            data class PagePropsObject(
                val order: String?, // createdAt...
                val videos: List<Video>,
                val pageNum: Int,
                val totalPage: Int,
                val totalVideoNum: Int,
                val tagsForCNAV: List<TagItem>?,
                val tags91: List<TagItem>?,
                val tagsOF: List<TagItem>?, // OnlyFans, only in tag browse
                val hotSearches: List<String>?, // only in Search
            ) {
                fun toAnimePage(): AnimesPage = AnimesPage(
                    videos.map { video -> video.toSAnime() },
                    pageNum < totalPage,
                )
            }
        }
    }

    @Serializable
    data class HotVideoList(
        val props: PropsObject,
    ) {
        @Serializable
        data class PropsObject(
            val pageProps: PagePropsObject,
        ) {
            @Serializable
            data class PagePropsObject(
                val latestVideos: List<Video>,
                val dailyHotCNAV: List<Video>,
                val dailyHotSelfie: List<Video>,
                val dailyHot91: List<Video>,
                val dailyOnlyFans: List<Video>,
                val dailyJV: List<Video>,
                val hotCNAV: List<Video>,
                val hotSelfie: List<Video>,
                val hot91: List<Video>,
            ) {
                fun toAnimePage(sort: String?): AnimesPage = AnimesPage(
                    listOf(
                        latestVideos,
                        dailyHotCNAV,
                        dailyHotSelfie,
                        dailyHot91,
                        dailyOnlyFans,
                        dailyJV,
                        hotCNAV,
                        hotSelfie,
                        hot91,
                    ).flatten()
                        .sortedWith { vid1, vid2 ->
                            when (sort) {
                                SORT_VIEW_KEY -> {
                                    if (vid1.viewCount > vid2.viewCount) {
                                        -1
                                    } else if (vid1.viewCount < vid2.viewCount) {
                                        1
                                    } else {
                                        vid2.createdAt.compareTo(vid1.createdAt)
                                    }
                                }

                                SORT_LIKE_KEY -> {
                                    if (vid1.likeCount == null && vid2.likeCount != null) {
                                        1
                                    } else if (vid1.likeCount != null && vid2.likeCount == null) {
                                        -1
                                    } else if (vid1.likeCount != null && vid2.likeCount != null) {
                                        if (vid1.likeCount > vid2.likeCount) {
                                            -1
                                        } else {
                                            1
                                        }
                                    } else {
                                        vid2.createdAt.compareTo(vid1.createdAt)
                                    }
                                }

                                else -> vid2.createdAt.compareTo(vid1.createdAt)
                            }
                        }
                        .associateBy { it.id }
                        .map { (_, video) -> video.toSAnime() },
                    false,
                )
            }
        }
    }

    fun List<Video>.toAnimePage(): AnimesPage = AnimesPage(
        map { video -> video.toSAnime() },
        false,
    )

    @Serializable
    data class VideoDetails(
        val props: PropsObject,
    ) {
        @Serializable
        data class PropsObject(
            val pageProps: PagePropsObject,
        ) {
            @Serializable
            data class PagePropsObject(
                val video: Video,
                val relatedVideos: List<Video>,
            )
        }
    }

    @Serializable
    data class Video(
        val id: String,
        @SerialName("vid")
        val code: String?,
        val name: String,
        val description: String?,
        val ref: String?,
        val tags: List<String>,
        val createdAt: String, // "2025-01-14T23:18:27.933Z"
        val viewCount: Int,
        val likeCount: Int?, // not available in search & relatedVideos
        val duration: Float, // in seconds
        val coverImageUrl: String,
        val nameZh: String?,
        val tagZh: List<String>?,
        val sources: List<Source>?, // not available in details
    ) {
        private val desc = StringBuilder().apply {
            sources?.firstOrNull()?.let { append("${resolutionDesc(it.resolution.toString())}\n") }
            append("Duration: ${formatDuration(duration.toInt())}\n")
            append("View: $viewCount")
            likeCount?.let { append(" - Like: $likeCount") }
            ref?.let { append("\nRef: $it") }
            description?.let { append("\n\n$description") }
        }.toString()

        private val majorCategory = tags.firstOrNull()

        fun toSAnime(): SAnime = SAnime.create().apply {
            url = id
            title = name
            thumbnail_url = coverImageUrl
            artist = majorCategory
            author = majorCategory
            description = desc
            genre = (listOfNotNull(code) + tags).joinToString()
            status = SAnime.COMPLETED
            initialized = true
        }

        fun toEpisode(): SEpisode = SEpisode.create().apply {
            name = id
            url = id
            date_upload = createdAt.toDate()
            episode_number = 1f
        }

        fun getTagList(): Set<Tag> = tags.map { Tag(it, it) }.toSet()
    }

    fun formatDuration(seconds: Int): String {
        val duration = seconds.seconds
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        val remainingSeconds = duration.inWholeSeconds % 60

        return "$hours:$minutes:$remainingSeconds"
    }

    @Serializable
    data class TagList(
        val props: PropsObject,
    ) {
        @Serializable
        data class PropsObject(
            val pageProps: PagePropsObject,
        ) {
            @Serializable
            data class PagePropsObject(
                val gcAV: List<TagItem>,
                val madouAV: List<TagItem>,
                val v91: List<TagItem>,
                val onlyfans: List<TagItem>,
            ) {
                fun toTagList(): Tags = listOf(
                    gcAV,
                    madouAV,
                    v91,
                    onlyfans,
                ).flatten()
                    .map { Pair(it.name, it.name) }
                    .toTypedArray()
            }
        }
    }

    @Serializable
    data class TagItem(
        @SerialName("id")
        val name: String,
        val count: Int,
        val parent: String,
        val level: Int, // usually 0
    )

    @Serializable
    data class VideoData(
        val video: VideoObject,
    ) {
        @Serializable
        data class VideoObject(
            val thumbVTTUrl: String,
            val videoUrl: String,
        )
    }

    /* Not available in details */
    @Serializable
    data class Source(
        val id: String?, // not available in relatedVideos
        val videoId: String?, // not available in relatedVideos
        val resolution: Int,
        val folder: String?, // not available in relatedVideos
    )

    private val DATE_FORMATTER by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
}
