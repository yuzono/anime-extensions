@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.animeextension.all.stremio

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.apache.commons.text.StringSubstitutor
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class ResultDto<T>(
    val result: T,
)

@Serializable
data class LoginDto(
    val authKey: String,
)

@Serializable
data class CatalogListDto(
    val hasMore: Boolean? = null,
    val metas: List<MetaDto>,
)

@Serializable
data class MetaResultDto(
    val meta: MetaDto,
)

@Serializable
data class MetaDto(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,

    // Details
    val description: String? = null,
    val genres: List<String>? = null,
    val director: List<String>? = null,
    val cast: List<String>? = null,
    val year: String? = null,

    // Episodes
    val videos: List<VideoDto>? = null,

    // Tv
    val streams: List<StreamDto>? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = name
        url = "$type-$id"
        thumbnail_url = poster
        // background_url = background ?: poster

        genre = genres?.joinToString()
        author = director?.take(5)?.joinToString()
        artist = cast?.take(5)?.joinToString()
        description = buildString {
            append(this@MetaDto.description ?: "")
            append("\n\n")
            year?.let {
                append("Release year: ")
                append(it)
            }
        }.trim()
        year?.let {
            status = if (it.last().isDigit()) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }
        }
    }
}

@Serializable
data class LibraryItemDto(
    @SerialName("_id")
    val id: String,
    @SerialName("_ctime")
    val ctime: String,
    val removed: Boolean,
    val name: String,
    val type: String,
    val poster: String? = null,
    val state: LibraryItemState,
) {
    @Serializable
    data class LibraryItemState(
        val lastWatched: String,
        val timesWatched: Int,
    )

    fun toSAnime(): SAnime = SAnime.create().apply {
        title = name
        url = "$type-$id"
        thumbnail_url = poster
    }

    fun watched(): Boolean = state.timesWatched > 0
}

@Serializable
data class VideoDto(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val episode: Int? = null,
    val released: String? = null,

    val season: Int? = null,
    val description: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
) {
    fun toSEpisode(
        episodeTemplate: String,
        scanlatorTemplate: String,
        type: String,
    ): SEpisode = SEpisode.create().apply {
        val values = mapOf(
            "name" to (this@VideoDto.title?.takeNotBlank() ?: this@VideoDto.name ?: ""),
            "episodeNumber" to (this@VideoDto.episode ?: 1),
            "seasonNumber" to (this@VideoDto.season ?: 1),
            "description" to (this@VideoDto.overview?.takeNotBlank() ?: this@VideoDto.description ?: ""),
        )
        val sub = StringSubstitutor(values, "{", "}")

        url = "$type-$id"
        name = sub.replace(episodeTemplate).trim()
        scanlator = sub.replace(scanlatorTemplate).trim().takeNotBlank()
        // summary = overview?.takeNotBlank() ?: description
        // preview_url = thumbnail
        episode_number = episode?.toFloat() ?: 1F
        date_upload = DATE_FORMAT.tryParse(released)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
    }
}

@Serializable
data class SubtitleResultDto(
    val subtitles: List<SubtitleDto>,
) {
    @Serializable
    data class SubtitleDto(
        val url: String,
        val lang: String,
    )
}

@Serializable
data class StreamResultDto(
    val streams: List<StreamDto>,
)

@Serializable
data class StreamDto(
    val name: String? = null,
    val description: String? = null,
    val title: String? = null,

    // Torrent
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,

    // Http stream
    val url: String? = null,
    val behaviorHints: BehaviorHintDto? = null,
) {
    fun toVideo(serverUrl: String?, subtitleList: List<Track>): Video? {
        val headers = behaviorHints?.proxyHeaders?.request?.toHeaders()

        val videoName = buildString {
            name?.let {
                appendLine(it.replace("\n", " "))
            }
            append(description ?: title ?: "")
        }.trim().ifBlank { "Video" }

        if (url?.isNotEmpty() == true) {
            return Video(
                url = url,
                quality = videoName,
                videoUrl = url,
                subtitleTracks = subtitleList,
                headers = headers,
            )
        }

        if (infoHash?.isNotEmpty() == true) {
            val url = if (serverUrl?.isNotEmpty() == true) {
                serverUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment(infoHash)
                    addPathSegment((fileIdx ?: -1).toString())

                    sources?.forEach { tracker ->
                        addQueryParameter("tr", tracker)
                    }
                }.build().toString()
            } else {
                buildString {
                    append("magnet:?xt=urn:btih:$infoHash")

                    sources?.forEach { tracker ->
                        append("&tr=${tracker.urlEncode()}")
                    }

                    if (fileIdx?.equals(-1)?.not() == true) {
                        append("&index=$fileIdx")
                    }
                }
            }

            return Video(
                url = url,
                quality = videoName,
                videoUrl = url,
                subtitleTracks = subtitleList,
            )
        }

        return null
    }

    @Serializable
    data class BehaviorHintDto(
        val proxyHeaders: ProxyHeaderDto? = null,
    ) {
        @Serializable
        data class ProxyHeaderDto(
            val request: Map<String, String>? = null,
        )
    }
}
