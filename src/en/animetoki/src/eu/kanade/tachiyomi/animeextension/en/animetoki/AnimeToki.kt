package eu.kanade.tachiyomi.animeextension.en.animetoki

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animeextension.en.animetoki.extractors.CloudExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.lang.UnsupportedOperationException
import java.net.URLDecoder

class AnimeToki : ParsedAnimeHttpSource() {

    override val name = "AnimeToki"
    override val baseUrl = "https://animetoki.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(SessionWarmUpInterceptor())
        .build()

    private val cloudExtractor by lazy { CloudExtractor(client, headers) }
    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("anime-series")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeSelector() = "li.post-item:has(a)"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.post-thumb, a[aria-label]") ?: element.selectFirst("a")
        if (link != null) {
            val href = link.attr("href")
            setUrlWithoutDomain(if (href.startsWith("http") || href.startsWith("/")) href else "/$href")
            title = link.attr("aria-label").ifEmpty { element.selectFirst(".post-title")?.text() ?: link.text() }
        } else {
            setUrlWithoutDomain("")
            title = ""
        }
        thumbnail_url = element.selectFirst("img")?.let {
            val url = it.absUrl("data-src").ifEmpty { it.absUrl("src") }.ifEmpty {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> baseUrl + url
                else -> url
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "a.load-more-button, a.next.page-numbers, .pages-nav a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val isFirstPage = !response.request.url.pathSegments.contains("page")

        val animes = mutableListOf<SAnime>()

        if (isFirstPage) {
            document.select("#widget_tabs-6-popular .widget-single-post-item").forEach {
                animes.add(popularAnimeFromElement(it))
            }
        }

        document.select(popularAnimeSelector()).forEach {
            animes.add(popularAnimeFromElement(it))
        }

        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("ongoing-anime")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesSelector() = "li.post-item:has(a)"
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var genre = ""
        var subPage = ""
        for (filter in filters) {
            when (filter) {
                is AnimeTokiFilters.GenreFilter -> genre = filter.toUriPart()
                is AnimeTokiFilters.SubPageFilter -> subPage = filter.toUriPart()
                else -> {}
            }
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                addQueryParameter("s", query)
            } else if (genre.isNotEmpty()) {
                addPathSegment("category")
                addPathSegment(genre)
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            } else if (subPage.isNotEmpty()) {
                addPathSegment("category")
                addPathSegment(subPage)
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            } else {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            }
        }.build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.post-title.entry-title")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("figure.single-featured-image img")?.let {
            val url = it.absUrl("data-src").ifEmpty { it.absUrl("src") }.ifEmpty {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> baseUrl + url
                else -> url
            }
        }
        genre = document.select("span.post-cat-wrap > a.post-cat").joinToString(", ") { it.text() }
        val descBuilder = StringBuilder()

        val summary = document.selectFirst(".review-short-summary")?.text()
        if (!summary.isNullOrEmpty()) {
            descBuilder.append(summary).append("\n\n")
        } else {
            document.select(".entry-content > p").forEach {
                descBuilder.append(it.text()).append("\n\n")
            }
        }

        val infoHtml = document.selectFirst("div.toggle-content")?.html() ?: ""
        if (infoHtml.isNotEmpty()) {
            val infoText = infoHtml.replace("(?i)<br[^>]*>".toRegex(), "\n").replace(Regex("<[^>]*>"), "").trim()
            descBuilder.append(infoText)
        }
        description = descBuilder.toString().trim()

        val normalizedInfoHtml = java.text.Normalizer.normalize(infoHtml, java.text.Normalizer.Form.NFKD)
        status = parseStatus(normalizedInfoHtml)
        author = Regex("(?i)studios?\\s*[\\p{Punct}⋩]*\\s*(.*?)(?:<br>|$)").find(normalizedInfoHtml)?.groupValues?.get(1)?.replace(Regex("<.*?>"), "")?.replace("⋩", "")?.trim()
        initialized = true
    }

    private fun parseStatus(text: String): Int {
        val lowerText = text.lowercase()
        return when {
            "completed" in lowerText || "finished airing" in lowerText -> SAnime.COMPLETED
            "ongoing" in lowerText || "currently airing" in lowerText || "releasing" in lowerText -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val cloudLinks = document.select("a[href*=\"cloud.animetoki.com\"], a[href*=\"drive.animetoki.com\"]")
        cloudLinks.forEach { link ->
            val attrHref = link.attr("href")
            val href = if (attrHref.startsWith("//")) "https:$attrHref" else attrHref
            val text = link.text().trim()
            episodes.addAll(cloudExtractor.getEpisodesFromCloudUrl(href, text))
        }

        val cdnLinks = document.select("a.shortc-button[href]").filterNot {
            val href = it.attr("href")
            href.contains("cloud.animetoki.com") || href.contains("drive.animetoki.com")
        }
        var epNum = 1
        cdnLinks.forEach { link ->
            val attrHref = link.attr("href")
            val href = if (attrHref.startsWith("//")) {
                "https:$attrHref"
            } else if (attrHref.startsWith("http")) {
                attrHref
            } else {
                link.absUrl("href")
            }
            val text = link.text()

            val path = href.substringBefore("?")
            if (href.contains("workers.dev") && href.endsWith("/")) {
                episodes.addAll(fetchWorkerEpisodes(href, text))
            } else if (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".avi") || href.contains("?a=view")) {
                episodes.add(
                    SEpisode.create().apply {
                        this.url = href
                        val fallbackName = if (text.isNotBlank()) text else "Episode $epNum"
                        name = fallbackName.replace("[AnimeToki] ", "", ignoreCase = true)
                            .replace("[AnimeSakura] ", "", ignoreCase = true).trim()
                        episode_number = epNum.toFloat()
                    },
                )
                epNum++
            }
        }

        episodes.forEachIndexed { index, episode ->
            episode.episode_number = (index + 1).toFloat()
        }
        return episodes.reversed()
    }

    private fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val charA = a[ia]
            val charB = b[ib]
            if (charA.isDigit() && charB.isDigit()) {
                val startA = ia
                while (ia < a.length && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < b.length && b[ib].isDigit()) ib++

                val numA = a.substring(startA, ia).toLongOrNull() ?: 0L
                val numB = b.substring(startB, ib).toLongOrNull() ?: 0L
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp

                val lenCmp = (ia - startA).compareTo(ib - startB)
                if (lenCmp != 0) return lenCmp
            } else {
                val cmp = charA.lowercaseChar().compareTo(charB.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }
        return a.length.compareTo(b.length)
    }

    private fun fetchWorkerEpisodes(folderUrl: String, prefix: String = ""): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        try {
            val doc = client.newCall(GET(folderUrl, headers)).execute().asJsoup()
            val folderParsed = folderUrl.toHttpUrl()
            val folderRoot = folderParsed.encodedPath.substringBefore("/0:/") + if (folderParsed.encodedPath.contains("/0:/")) "/0:/" else ""

            val links = doc.select("a[href]").toList().filter { a ->
                val href = a.attr("href")
                href.isNotBlank() && href != "." && href != ".." && href != "../"
            }

            links.forEach { a ->
                val href = a.absUrl("href")
                val hrefParsed = href.toHttpUrlOrNull() ?: return@forEach

                if (hrefParsed.host == folderParsed.host && hrefParsed.encodedPath.startsWith(folderRoot)) {
                    val path = href.substringBefore("?")
                    if (href.endsWith("/")) {
                        val newPrefix = if (prefix.isNotBlank()) "$prefix / ${a.text()}" else a.text()
                        episodes.addAll(fetchWorkerEpisodes(href, newPrefix))
                    } else if (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".avi") || href.contains("?a=view")) {
                        episodes.add(
                            SEpisode.create().apply {
                                this.url = href
                                val extractedName = a.text().ifEmpty { URLDecoder.decode(href.substringAfterLast("/"), "UTF-8") }
                                name = extractedName.replace("[AnimeToki] ", "", ignoreCase = true)
                                    .replace("[AnimeSakura] ", "", ignoreCase = true).trim()
                                if (prefix.isNotBlank()) {
                                    scanlator = prefix
                                }
                            },
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeToki", "Error in fetchWorkerEpisodes: $folderUrl", e)
        }
        episodes.sortWith(Comparator { a, b -> naturalCompare(a.name, b.name) })
        return episodes
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> = Observable.fromCallable {
        val url = episode.url
        val quality = episode.name
        if (url.contains("cloud.animetoki.com") || url.contains("drive.animetoki.com")) {
            listOf(Video(url, quality, url, getVideoHeaders(url)))
        } else if (url.contains("workers.dev")) {
            val resolvedUrl = resolveWorkerUrl(url)
            listOf(Video(resolvedUrl, quality, resolvedUrl, getVideoHeaders(resolvedUrl)))
        } else {
            val cleanUrl = try {
                url.toHttpUrl().newBuilder().removeAllQueryParameters("a").build().toString()
            } catch (e: Exception) {
                url
            }
            listOf(Video(cleanUrl, quality, cleanUrl, getVideoHeaders(cleanUrl)))
        }
    }

    private fun getVideoHeaders(url: String): okhttp3.Headers {
        val builder = headers.newBuilder()
        try {
            val cookies = client.cookieJar.loadForRequest(url.toHttpUrl())
            if (cookies.isNotEmpty()) {
                builder.add("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            }
        } catch (e: Exception) {
            // Ignore
        }
        return builder.build()
    }

    private fun resolveWorkerUrl(url: String): String {
        return try {
            val parsed = url.toHttpUrl()
            if (!parsed.host.contains("workers.dev")) {
                return parsed.newBuilder().removeAllQueryParameters("a").build().toString()
            }

            val path = parsed.encodedPath.removeSuffix("/")
            val parentPath = path.substringBeforeLast("/") + "/"
            val fileName = URLDecoder.decode(path.substringAfterLast("/"), "UTF-8")

            val parentUrl = parsed.newBuilder()
                .encodedPath(parentPath)
                .removeAllQueryParameters("a")
                .addQueryParameter("a", "view")
                .build()

            client.newCall(POST(parentUrl.toString(), headers, ByteArray(0).toRequestBody(null))).execute().use { postResponse ->
                val responseBody = postResponse.body.string()
                val files = json.parseToJsonElement(responseBody).jsonObject["files"]?.jsonArray ?: return url

                files.forEach { file ->
                    val f = file.jsonObject
                    val name = f["name"]?.jsonPrimitive?.content
                    if (name == fileName) {
                        val fileId = f["id"]?.jsonPrimitive?.content ?: return@forEach
                        val encodedName = Base64.encodeToString(fileName.toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
                        return parsed.newBuilder()
                            .encodedPath("/")
                            .query(null)
                            .addQueryParameter("a", "download")
                            .addQueryParameter("id", fileId)
                            .addQueryParameter("name", encodedName)
                            .fragment(fileName)
                            .build().toString()
                    }
                }
            }
            url
        } catch (e: Exception) {
            Log.e("AnimeToki", "Error resolving worker url: $url", e)
            url
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeTokiFilters.getFilterList()
}
