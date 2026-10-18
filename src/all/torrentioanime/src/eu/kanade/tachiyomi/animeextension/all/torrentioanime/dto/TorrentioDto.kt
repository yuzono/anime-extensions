package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Stream Data For Torrent
@Serializable
data class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val behaviorHints: BehaviorHints? = null,
)

@Serializable
data class BehaviorHints(
    val bingeGroup: String? = null,
)

// Episode Data

@Serializable
data class EpisodeList(
    val meta: EpisodeMeta? = null,
)

@Serializable
data class EpisodeMeta(
    @SerialName("id")
    val kitsuId: String? = null,
    val type: String? = null,
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
data class EpisodeVideo(
    @SerialName("id")
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
)
