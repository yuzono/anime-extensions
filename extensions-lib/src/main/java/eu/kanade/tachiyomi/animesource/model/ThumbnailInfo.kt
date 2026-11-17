package eu.kanade.tachiyomi.animesource.model

import keiyoushi.annotation.AnimiruOnly

@AnimiruOnly
open class ThumbnailInfo(
    val tileInfo: List<TileInfo>,
    val imageTileUrls: List<String>,
)

@AnimiruOnly
data class TileInfo(
    val imageIndex: Int,
    val timeMs: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
