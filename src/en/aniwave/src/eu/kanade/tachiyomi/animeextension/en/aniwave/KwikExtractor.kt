package eu.kanade.tachiyomi.animeextension.en.aniwave

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class KwikExtractor(
    private val client: OkHttpClient,
) {
    companion object {
        private val M3U8_REGEX = Regex("""['"]([^'"]+\.m3u8[^'"]*)['"]""")
    }

    /**
     * Extracts the HLS m3u8 stream URL from a kwik.cx embed page.
     *
     * Modern kwik embeds the m3u8 URL inside a Dean Edwards packed JS block.
     * The variable name holding the URL is obfuscated, so we extract it by regex.
     *
     * The page also contains other packed scripts (cookie helpers), so we iterate
     * through all of them and return the first one that yields an m3u8 URL.
     */
    fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val document = client.newCall(GET(kwikUrl, Headers.headersOf("referer", referer)))
            .execute()
            .asJsoup()

        val packedScripts = document.select("script:containsData(eval\\(function)")

        for (script in packedScripts) {
            val packedContent = script.data().substringAfterLast("eval(function(")
            if (packedContent.isBlank()) continue

            val unpacked = JsUnpacker.unpackAndCombine("eval(function($packedContent") ?: continue

            M3U8_REGEX.find(unpacked)?.groupValues?.get(1)?.let {
                return it
            }
        }

        throw Exception("Failed to extract m3u8 URL from Kwik")
    }
}
