package keiyoushi.source.model

class VideoThumbnails(val tiles: List<Tile>, val imageUrls: List<String>) {
    /** [imageIndex] is the index in [imageUrls]. */
    class Tile(
        val imageIndex: Int,
        val timeMs: Long,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )
}
