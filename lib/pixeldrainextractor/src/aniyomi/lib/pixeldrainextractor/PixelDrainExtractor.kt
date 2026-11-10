package aniyomi.lib.pixeldrainextractor

import eu.kanade.tachiyomi.animesource.model.Video

class PixelDrainExtractor {

    companion object {
        private val mIdRegex by lazy { Regex("""/u/(.*)""") }
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val mId = mIdRegex.find(url)?.groupValues?.getOrNull(1)
        return if (mId.isNullOrEmpty()) {
            listOf(Video(videoUrl = url, videoTitle = "${prefix}PixelDrain"))
        } else {
            listOf(Video(videoUrl = "https://pixeldrain.com/api/file/$mId?download", videoTitle = "${prefix}PixelDrain"))
        }
    }
}
