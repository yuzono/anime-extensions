package keiyoushi.source.model

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response

/** [headers] are sent when fetching [imageUrls]; the source's headers when null. */
class VideoThumbnails(val tiles: List<Tile>, val imageUrls: List<String>, val headers: Headers? = null) {
    /** [imageIndex] is the index in [imageUrls]. */
    class Tile(
        val imageIndex: Int,
        val timeMs: Long,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    companion object {
        private val cueTiming = Regex("""^\s*((?:\d+:)?\d+:\d+(?:\.\d+)?)\s*-->""")
        private val spriteArea = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""")

        private val credentialHeaders = setOf("Authorization", "Proxy-Authorization", "Cookie")

        /**
         * [fromVtt] for a fetched track: relative image urls resolve against its final url, and the images
         * are fetched with the headers the track was, minus credentials.
         */
        fun fromVtt(response: Response): VideoThumbnails? = response.use {
            val headers = it.request.headers.newBuilder().apply { credentialHeaders.forEach(::removeAll) }.build()
            fromVtt(it.body.string(), it.request.url, headers)
        }

        /**
         * Parses a WebVTT thumbnail track, whose cues are sprite areas (`sprite.jpg#xywh=0,0,160,90`).
         * Relative image urls resolve against [imageBase], usually the vtt's own url. Cues without an
         * area or that don't parse are skipped; returns null when none is left.
         */
        fun fromVtt(vtt: String, imageBase: HttpUrl, headers: Headers? = null): VideoThumbnails? {
            val imageUrls = mutableListOf<String>()
            val tiles = vtt.lineSequence()
                .zipWithNext()
                .mapNotNull { (timing, image) ->
                    val start = cueTiming.find(timing)?.groupValues?.get(1)?.let(::timeMs) ?: return@mapNotNull null
                    val area = spriteArea.find(image)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return@mapNotNull null }
                        ?: return@mapNotNull null
                    val imageUrl = imageBase.resolve(image.substringBefore('#').trim())?.toString() ?: return@mapNotNull null
                    val index = imageUrls.indexOf(imageUrl).takeIf { it >= 0 } ?: imageUrls.size.also { imageUrls += imageUrl }
                    Tile(index, start, area[0], area[1], area[2], area[3])
                }
                .toList()
            return VideoThumbnails(tiles, imageUrls, headers).takeIf { tiles.isNotEmpty() }
        }

        private fun timeMs(time: String): Long? {
            val millis = time.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: return null
            val seconds = time.substringBefore('.').split(':').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: return null) }
            return seconds * 1000 + millis
        }
    }
}
