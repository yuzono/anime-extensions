package eu.kanade.tachiyomi.animeextension.all.torrentio.dto

import kotlinx.serialization.Serializable

// Stream Data For Torrent
@Serializable
class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
)

// Episode Data

@Serializable
class EpisodeList(
    val meta: EpisodeMeta? = null,
)

@Serializable
class EpisodeMeta(
    val id: String? = null,
    val type: String? = null,
    val videos: List<EpisodeVideo>? = null,
)

@Serializable
class EpisodeVideo(
    val id: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val firstAired: String? = null,
    val released: String? = null,
)
