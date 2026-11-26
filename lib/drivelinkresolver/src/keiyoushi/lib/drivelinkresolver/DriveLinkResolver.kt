package keiyoushi.lib.drivelinkresolver

import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.post
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Resolves the download buttons of MoviesMod-family sites (UHD Movies, MoviesMod) to a drive
 * (driveseed, driveleech, ...) `/file/<id>` page.
 *
 * Buttons are either a drive `r?key=` link, or a `?sid=` link shortener: a chain of hidden POST forms
 * (`form#lp-land`, `form#lp-s1-form`, ...), then a `?lp_go=` (formerly `?go=`) link that only redirects
 * when the cookie its page sets from JavaScript is present.
 */
class DriveLinkResolver(private val client: OkHttpClient, private val headers: Headers) {

    /** The drive `/file/<id>` url behind a `?sid=` or `r?key=` link, or null when it can't be resolved. */
    suspend fun fileUrl(url: String): String? {
        val driveUrl = when {
            "?sid=" in url -> bypass(url)
            "r?key=" in url -> url
            else -> null
        } ?: return null

        // "r?key=" pages forward to "/file/<id>" with a script
        val doc = client.get(driveUrl, headers, CacheControl.FORCE_NETWORK).asJsoup()
        val target = doc.location().toHttpUrl().takeIf { it.isFile() } ?: doc.redirectTarget()
        return target?.takeIf { it.isFile() }?.toString()
    }

    /** The url a `?sid=` shortener link leads to. */
    suspend fun bypass(url: String): String? {
        var doc = client.get(url, headers, CacheControl.FORCE_NETWORK).asJsoup()
        repeat(MAX_HOPS) {
            doc = submitForm(doc) ?: return goLink(doc)
        }
        return goLink(doc)
    }

    private suspend fun submitForm(doc: Document): Document? {
        val form = doc.selectFirst(FORM_SELECTOR) ?: return null
        val action = form.absUrl("action").ifEmpty { return null }
        val body = FormBody.Builder().apply {
            form.select("input[name]").forEach { add(it.attr("name"), it.attr("value")) }
        }.build()
        return client.post(action, headers.withReferer(doc), body, ensureSuccess = false).asJsoup()
    }

    private suspend fun goLink(doc: Document): String? {
        val (script, goMatch) = doc.select("script").map { it.data() }
            .firstNotNullOfOrNull { data -> GO_URL_REGEX.find(data)?.let { data to it } } ?: return null
        val goUrl = goMatch.groupValues[1].unescape().toHttpUrlOrNull() ?: return null
        val cookieName = goUrl.queryParameter("lp_go") ?: goUrl.queryParameter("go") ?: return null
        val cookieValue = Regex("""['"]${Regex.escape(cookieName)}['"]\s*,\s*['"]([^'"]+)""")
            .find(script)?.groupValues?.get(1)?.unescape() ?: return null

        // Each link gets its own short-lived cookie name, so parallel bypasses don't clash or pile up
        Cookie.parse(goUrl, "$cookieName=$cookieValue; path=/; max-age=$COOKIE_MAX_AGE")
            ?.let { client.cookieJar.saveFromResponse(goUrl, listOf(it)) }

        val page = client.get(goUrl, headers.withReferer(doc), CacheControl.FORCE_NETWORK, ensureSuccess = false).asJsoup()
        // The go page usually forwards with a script, but may also have redirected straight to the drive
        return (page.redirectTarget() ?: page.location().toHttpUrl().takeIf { it.isFile() })?.toString()
    }

    private fun Headers.withReferer(doc: Document) = newBuilder().set("Referer", doc.location()).build()

    private fun HttpUrl.isFile() = encodedPath.startsWith("/file/")

    private fun String.unescape() = replace("\\/", "/")

    /** Where a meta refresh or `location.replace(...)` page leads. */
    private fun Document.redirectTarget(): HttpUrl? {
        val match = selectFirst("meta[http-equiv=refresh]")?.attr("content")?.let(REFRESH_REGEX::find)
            ?: LOCATION_REGEX.find(html())
        return match?.groupValues?.get(1)?.unescape()?.let(location().toHttpUrl()::resolve)
    }

    companion object {
        private const val MAX_HOPS = 6
        private const val COOKIE_MAX_AGE = 120
        private const val FORM_SELECTOR = "form[id^=lp-], form#landing"
        private val GO_URL_REGEX = Regex("""['"](https?:[^'"]*?[?&](?:lp_)?go=[^'"]+)['"]""")
        private val LOCATION_REGEX = Regex("""location\.replace\(\s*['"]([^'"]+)['"]""")
        private val REFRESH_REGEX = Regex("""url\s*=\s*['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE)
    }
}
