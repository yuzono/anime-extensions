package eu.kanade.tachiyomi.animeextension.en.moviesmod

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class RedirectorBypasser(private val client: OkHttpClient, private val headers: Headers) {
    fun bypass(url: String): String? {
        val lastDoc = client.newCall(GET(url, headers)).execute()
            .let { recursiveDoc(it.asJsoup()) }

        val script = lastDoc.selectFirst("script:containsData(go=):containsData(href)")
            ?.data()
            ?: return null

        val nextUrl = HREF_REGEX.find(script)?.groupValues?.get(2)?.unescape() ?: return null
        val httpUrl = nextUrl.toHttpUrlOrNull() ?: return null
        val cookieName = httpUrl.queryParameter("lp_go") ?: httpUrl.queryParameter("go") ?: return null
        val cookieValue = Regex("""['"]${Regex.escape(cookieName)}['"]\s*,\s*['"]([^'"]+)""")
            .find(script)?.groupValues?.get(1)?.unescape()
            ?: return null
        val cookie = Cookie.parse(httpUrl, "$cookieName=$cookieValue") ?: return null
        val headers = headers.newBuilder().set("referer", lastDoc.location()).build()

        val doc = runBlocking(Dispatchers.IO) {
            MUTEX.withLock {
                // Mutex to prevent overwriting cookies from parallel requests
                client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
                client.newCall(GET(nextUrl, headers)).execute().asJsoup()
            }
        }

        doc.selectFirst("meta[http-equiv=refresh]")?.let {
            return REFRESH_REGEX.find(it.attr("content"))?.groupValues?.get(1)
        }

        val redirect = doc.selectFirst("script:containsData(location.replace)")?.data() ?: return null
        return LOCATION_REGEX.find(redirect)?.groupValues?.get(2)?.unescape()
    }

    private fun recursiveDoc(doc: Document): Document {
        val form = doc.selectFirst("form#landing, form[id^=lp-]") ?: return doc
        val url = form.absUrl("action")
        val body = FormBody.Builder().apply {
            form.select("input").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        val headers = headers.newBuilder()
            .set("referer", doc.location())
            .build()

        return client.newCall(POST(url, headers, body)).execute().let {
            recursiveDoc(it.asJsoup())
        }
    }

    private fun String.unescape() = replace("\\/", "/")

    companion object {
        private val MUTEX by lazy { Mutex() }
        private val HREF_REGEX = Regex("""['"]href['"]\s*,\s*(['"])(.+?)\1""")
        private val LOCATION_REGEX = Regex("""location\.replace\((['"])(.+?)\1""")
        private val REFRESH_REGEX = Regex("""url\s*=\s*['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE)
    }
}
