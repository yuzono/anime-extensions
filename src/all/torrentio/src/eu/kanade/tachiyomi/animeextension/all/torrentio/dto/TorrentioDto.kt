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
