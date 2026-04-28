/* The following file is based on: https://github.com/LagradOst/CloudStream-3/blob/4d6050219083d675ba9c7088b59a9492fcaa32c7/app/src/main/java/com/lagradost/cloudstream3/animeproviders/AnimePaheProvider.kt
 * It is published under the following license:
 *
MIT License

Copyright (c) 2021 Osten

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 *
 */

package eu.kanade.tachiyomi.animeextension.en.aniwave

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient

class KwikExtractor(private val client: OkHttpClient) {

    companion object {
        private val M3U8_REGEX = Regex("""['"]([^'"]+\.m3u8[^'"]*)['"]""")

        // Kwik serves a Cloudflare JS challenge to non-desktop User-Agents
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"
    }

    /**
     * Extracts the HLS m3u8 stream URL from a kwik.cx embed page.
     *
     * Modern kwik embeds the m3u8 URL inside a Dean Edwards packed JS block.
     * The variable name holding the URL is obfuscated (e.g. `var q='...'`, `const source='...'`),
     * so we extract it by regex instead of relying on a fixed identifier.
     *
     * The page also contains other packed scripts (cookie helpers), so we iterate
     * through all of them and return the first one that yields an m3u8 URL.
     */
    suspend fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer)
            .build()

        val document = client.newCall(GET(kwikUrl, headers = requestHeaders))
            .await()
            .useAsJsoup()

        val packedScripts = document.select("script:containsData(eval\\(function)")

        for (script in packedScripts) {
            val packedContent = script.data().substringAfter("eval(function(")
            if (packedContent.isBlank()) continue

            val unpacked = JsUnpacker.unpackAndCombine("eval(function($packedContent")
                ?: continue

            M3U8_REGEX.find(unpacked)?.groupValues?.get(1)?.let {
                return it
            }
        }

        throw Exception("Failed to extract m3u8 URL from Kwik")
    }
}
