package aniyomi.lib.pixeldrainextractor

import eu.kanade.tachiyomi.animesource.model.Video

class PixelDrainExtractor {

    companion object {
        private val mIdRegex by lazy { Regex("""/u/(.*)""") }
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val mId = mIdRegex.find(url)?.groupValues?.getOrNull(1)
        return if (mId.isNullOrEmpty()) {
            listOf(Video(url, "${prefix}PixelDrain", url))
        } else {
            listOf(Video("https://pixeldrain.com/api/file/$mId?download", "${prefix}PixelDrain", "https://pixeldrain.com/api/file/$mId?download"))
        }
    }
}
