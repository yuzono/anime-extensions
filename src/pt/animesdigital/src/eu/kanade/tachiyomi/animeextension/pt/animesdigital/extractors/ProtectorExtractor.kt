package eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class ProtectorExtractor(private val client: OkHttpClient, private val headers: Headers) {
    // The protector keeps a single "token" cookie per host, so concurrent resolutions overwrite each other
    private val mutex = Mutex()

    /**
     * Follows the ad-protected player link (campaign page → meta refresh → article page)
     * and returns the embed URL the article reveals after its countdown.
     */
    suspend fun embedUrlFromUrl(url: String): String? = mutex.withLock {
        var nextUrl = if (url.startsWith("//")) "https:$url" else url
        repeat(MAX_HOPS) {
            val document = client.newCall(GET(nextUrl, headers)).awaitSuccess().useAsJsoup()
            document.selectFirst("[data-url]")?.absUrl("data-url")?.takeIf(String::isNotEmpty)
                ?.let { return@withLock it }
            nextUrl = document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.let { REFRESH_URL_REGEX.find(it)?.groupValues?.get(1) }
                ?.let { document.location().toHttpUrl().resolve(it.trim())?.toString() }
                ?: return@withLock null
        }
        null
    }

    companion object {
        private const val MAX_HOPS = 4
        private val REFRESH_URL_REGEX = Regex("""url\s*=\s*['"]?([^'"]+)""", RegexOption.IGNORE_CASE)
    }
}
