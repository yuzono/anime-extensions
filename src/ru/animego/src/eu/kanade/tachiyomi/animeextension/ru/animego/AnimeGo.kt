package eu.kanade.tachiyomi.animeextension.ru.animego

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeGo :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeGO.studio"
    override val baseUrl = "https://animego.studio"
    override val lang = "ru"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    private fun popularAnimeSelector(): String = "div.item-main"

    private fun popularAnimeNextPageSelector(): String = "div.pagination__pages span:not(.nav_ext) + a"

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.item-main__title")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("div.item__img img")?.absUrl("src")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ongoing/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            if (query.length < 4) throw Exception("Минимальная длина поискового запроса — 4 символа")

            val postHeaders = headers.newBuilder()
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("Origin", baseUrl)
                .build()

            val body = buildString {
                append("do=search&subaction=search")
                if (page > 1) append("&search_start=$page&full_search=0&result_from=${(page - 1) * 10 + 1}")
                append("&story=${Uri.encode(query)}")
            }.toRequestBody("application/x-www-form-urlencoded".toMediaType())

            return if (page == 1) {
                POST("$baseUrl/", body = body, headers = postHeaders)
            } else {
                POST("$baseUrl/index.php?do=search", body = body, headers = postHeaders)
            }
        }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val category = filterList.filterIsInstance<CategoryFilter>().firstOrNull()?.toUriPart()
        val genre = filterList.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart()

        return when {
            !genre.isNullOrBlank() -> GET("$baseUrl${genre}page/$page/", headers)
            !category.isNullOrBlank() -> GET("$baseUrl${category}page/$page/", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Фильтры не работают при текстовом поиске"),
        CategoryFilter(),
        GenreFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter :
        UriPartFilter(
            "Категория",
            arrayOf(
                "Все" to "",
                "Фильмы" to "/movie/",
                "Сериалы" to "/tv-series/",
                "ONA" to "/ona/",
                "OVA" to "/ova/",
                "Спешл" to "/special/",
                "Онгоинги" to "/ongoing/",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Жанр",
            arrayOf(
                "Все" to "",
                "Безумие" to "/madness/",
                "Боевые искусства" to "/martial-arts/",
                "Вампиры" to "/vampires/",
                "Военное" to "/military/",
                "Гарем" to "/harem/",
                "Демоны" to "/demons/",
                "Детектив" to "/detective/",
                "Детское" to "/kids/",
                "Драма" to "/drama/",
                "Игры" to "/game/",
                "Исторический" to "/historical/",
                "Комедия" to "/comedy/",
                "Космос" to "/space/",
                "Магия" to "/magic/",
                "Машины" to "/cars/",
                "Меха" to "/mecha/",
                "Музыка" to "/music/",
                "Пародия" to "/parody/",
                "Повседневность" to "/everyday-life/",
                "Полиция" to "/police/",
                "Приключения" to "/adventures/",
                "Психологическое" to "/psychological/",
                "Романтика" to "/romance/",
                "Самураи" to "/samurai/",
                "Сверхъестественное" to "/supernatural/",
                "Сёдзё" to "/shoujo/",
                "Сёдзё Ай" to "/shoujo-ai/",
                "Сёнэн" to "/shounen/",
                "Спорт" to "/sports/",
                "Супер сила" to "/super-power/",
                "Сэйнэн" to "/seinen/",
                "Триллер" to "/thriller/",
                "Ужасы" to "/horrors/",
                "Фантастика" to "/sci-fi/",
                "Фэнтези" to "/fantasy/",
                "Школа" to "/school/",
                "Экшен" to "/action/",
            ),
        )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.item-page__title")?.text()
                ?: throw Exception("Название не найдено")
            thumbnail_url = document.selectFirst("div.item-page__poster img")?.absUrl("src")
            description = document.selectFirst("div.full-text")?.text()
            genre = document.select("div.item-page ul.item__list li:has(span:contains(Жанр)) a")
                .joinToString { it.text() }
            author = document.select("div.item-page ul.item__list li:has(span:contains(Студия)) a")
                .joinToString { it.text() }
                .ifBlank { null }
            val statusText = document.selectFirst("div.item-page ul.item__list li:has(span:contains(Статус))")
                ?.text() ?: ""
            status = when {
                statusText.contains("Онгоинг", ignoreCase = true) -> SAnime.ONGOING
                // Aniyomi has no dedicated "announced" status — the closest one is ONGOING.
                statusText.contains("Анонс", ignoreCase = true) -> SAnime.ONGOING
                // «Заверш» покрывает оба написания: «Завершён» и «Завершен».
                statusText.contains("Заверш", ignoreCase = true) -> SAnime.COMPLETED
                statusText.contains("Вышел", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    // Fetched via getEpisodeList below (suspend network calls can't live in episodeListParse).
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url, headers).use { it.asJsoup() }
        val iframeSrc = document.selectFirst("iframe[data-src*=kodik], iframe[src*=kodik]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Плеер Kodik не найден на странице")

        val playerUrl = iframeSrc.fixProtocol()

        // Movies and single videos: /video/{id}/{hash}/720p (anything that is not a serial)
        if (!playerUrl.contains("/serial/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Фильм"
                    episode_number = 1F
                    url = playerUrl
                },
            )
        }

        // Serials: /serial/{id}/{hash}/720p — take the maximum episode count across
        // the series list and all translations ("Name (N эп.)").
        val playerDoc = fetchKodikDocument(playerUrl)

        val fromSeriesBox = playerDoc.select("div.serial-series-box option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull() ?: 0
        val fromTranslations = playerDoc.select("div.serial-translations-box option")
            .mapNotNull { EP_COUNT_REGEX.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

        // Fall back to a single episode when the page exposes no dropdowns (movies,
        // single-episode serials, newly airing shows).
        val total = maxOf(fromSeriesBox, fromTranslations).coerceAtLeast(1)

        return (total downTo 1).map { ep ->
            SEpisode.create().apply {
                name = "Серия $ep"
                episode_number = ep.toFloat()
                url = playerUrl.toHttpUrl().newBuilder()
                    .setQueryParameter("episode", ep.toString())
                    .build()
                    .toString()
            }
        }
    }

    // =============================== Videos ===============================

    // One Hoster per translation/dubbing so that switching the audio track in the player
    // actually switches the stream: the app switches hosters, while the videos inside a
    // hoster are just the qualities of that one dubbing.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val requestUrl = episode.url.toHttpUrl()
        val episodeNum = requestUrl.queryParameter("episode")?.toIntOrNull()
        val isSerial = requestUrl.encodedPath.startsWith("/serial/")
        val playerHost = requestUrl.host
        val document = fetchKodikDocument(episode.url)

        val translations = document.select(
            "div.serial-translations-box option, div.movie-translations-box option",
        )

        // Single translation — the episode URL itself is the player page.
        if (translations.isEmpty()) {
            return listOf(Hoster(hosterName = "Kodik", internalData = episode.url))
        }

        // Carry the signed urlParams over so Kodik actually serves the requested dubbing:
        // without them the media id/hash in the path are ignored and the first dubbing wins.
        val pageHtml = document.html()
        val rawParams = extractUrlParams(pageHtml) ?: return emptyList()
        val signQuery = urlParamsToQuery(rawParams)

        return translations.mapNotNull { option ->
            val mediaId = option.attr("data-media-id")
            val mediaHash = option.attr("data-media-hash")
            if (mediaId.isBlank() || mediaHash.isBlank()) return@mapNotNull null

            // Skip translations that do not have the requested episode yet.
            val epCount = EP_COUNT_REGEX.find(option.text())?.groupValues?.get(1)?.toIntOrNull()
            if (episodeNum != null && epCount != null && epCount < episodeNum) return@mapNotNull null

            val dubbing = option.text().substringBefore(" (").trim().ifBlank { "Kodik" }
            val label = if (option.attr("data-translation-type") == "subtitles") {
                "$dubbing (Субтитры)"
            } else {
                dubbing
            }

            val mediaType = if (isSerial) "serial" else "video"
            val params = listOfNotNull(
                signQuery.takeIf { it.isNotEmpty() },
                if (isSerial && episodeNum != null) "episode=$episodeNum" else null,
            ).joinToString("&")
            val url = buildString {
                append("https://$playerHost/$mediaType/$mediaId/$mediaHash/720p")
                if (params.isNotEmpty()) append("?$params")
            }

            Hoster(hosterName = label, internalData = url)
        }
    }

    private fun urlParamsToQuery(raw: String): String = runCatching {
        raw.parseAs<JsonObject>().entries.joinToString("&") { (key, value) ->
            "$key=${value.jsonPrimitive.content}"
        }
    }.getOrDefault("")

    override suspend fun getVideoList(hoster: Hoster): List<Video> = applyQualityPreference(kodikVideoLinks(hoster.internalData, hoster.hosterName))

    // Voice-overs before subtitles now applies to the hoster (audio track) list.
    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedBy { it.hosterName.contains("Субтитры", ignoreCase = true) }

    // Put the preferred quality first but keep the others: filtering them out would silently
    // drop a whole dubbing whose catalogue has no rendition at the preferred quality.
    private fun applyQualityPreference(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.toIntOrNull()
            ?: return videos
        return videos.sortedWith(
            compareBy(
                { it.videoTitle.parseQuality()?.let { q -> kotlin.math.abs(q - pref) } ?: Int.MAX_VALUE },
                { -(it.videoTitle.parseQuality() ?: 0) },
            ),
        )
    }

    private fun String.parseQuality(): Int? = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull()

    // Voice-overs before subtitles.
    override fun List<Video>.sortVideos(): List<Video> = sortedBy {
        it.videoTitle.contains("Субтитры", ignoreCase = true)
    }

    // ─── Kodik player ─────────────────────────────────────────────────────

    // Base headers (incl. the default browser-like User-Agent) with the site referer.
    // Building headers from scratch would drop the User-Agent and make Kodik serve a
    // stripped-down player page without the translations panel.
    private val kodikHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
    }

    private val decodeScriptCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Kodik pages contain a self-closing <script .../> inside an inline <svg>. Browsers
    // parse it as an empty element (SVG foreign-content rules), but Jsoup treats it as an
    // opening <script> tag and swallows the rest of the page — including the translations
    // panel — as raw script text. Balance such tags before parsing.
    private suspend fun fetchKodikDocument(url: String): Document {
        val body = client.get(url, kodikHeaders).bodyString()
        return Jsoup.parse(body.replace(SELF_CLOSING_SCRIPT_REGEX, "<script$1></script>"), url)
    }

    private suspend fun kodikVideoLinks(playerPageUrl: String, dubbing: String): List<Video> {
        val page = runCatching {
            fetchKodikDocument(playerPageUrl)
        }.getOrNull() ?: return emptyList()

        val pageHtml = page.html()

        // urlParams is a JSON blob wrapped in quotes.
        val rawParams = extractUrlParams(pageHtml) ?: return emptyList()

        val formData = runCatching {
            rawParams.parseAs<KodikFormData>()
        }.getOrNull() ?: return emptyList()

        if (formData.dSign.isEmpty()) return emptyList()

        // Per-episode type/id/hash come from the vInfo object:
        //     vInfo.type = 'seria';  vInfo.hash = '...';  vInfo.id = '1407443';
        var videoType: String? = null
        var videoId: String? = null
        var videoHash: String? = null
        for (script in page.select("script").map { it.data() }) {
            val t = VIDEO_TYPE_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val h = VIDEO_HASH_REGEX.find(script)?.groupValues?.get(1) ?: continue
            val i = VIDEO_ID_REGEX.find(script)?.groupValues?.get(1) ?: continue
            videoType = t
            videoHash = h
            videoId = i
            break
        }

        // Fallback to the player URL path: https://{host}/{type}/{id}/{hash}/720p
        val urlParts = playerPageUrl.substringAfter("://").substringBefore('?').split('/')
        val resolvedType = videoType ?: urlParts.getOrNull(1)
        val resolvedId = videoId ?: urlParts.getOrNull(2)
        val resolvedHash = videoHash ?: urlParts.getOrNull(3)
        if (resolvedType == null || resolvedId == null || resolvedHash == null) return emptyList()

        val playerHost = playerPageUrl.substringAfter("://").substringBefore('/')

        val postBody = FormBody.Builder()
            .add("d", formData.d)
            .add("d_sign", Uri.decode(formData.dSign))
            .add("pd", formData.pd)
            .add("pd_sign", Uri.decode(formData.pdSign))
            .add("ref", Uri.decode(formData.ref))
            .add("ref_sign", Uri.decode(formData.refSign))
            .add("type", resolvedType)
            .add("id", resolvedId)
            .add("hash", resolvedHash)
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val postHeaders = headers.newBuilder()
            .set("Referer", playerPageUrl)
            .set("Origin", "https://$playerHost")
            .build()

        val kodikData = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://$playerHost/ftor")
                    .post(postBody)
                    .headers(postHeaders)
                    .build(),
            ).execute().parseAs<KodikData>()
        }.getOrNull() ?: return emptyList()

        // Decode each quality with the page's own JS function via QuickJs.
        val scriptUrl = (
            page.selectFirst("script[src*=app.serial]")
                ?: page.selectFirst("script[src*=app.video]")
                ?: page.selectFirst("script[src*=player]")
                ?: page.selectFirst("script[src*=app]")
            )?.attr("abs:src") ?: return emptyList()

        val jsScript = decodeScriptCache.getOrPut(scriptUrl) {
            runCatching {
                client.get(scriptUrl, kodikHeaders).bodyString()
            }.getOrNull() ?: return emptyList()
        }

        val atobMatch = ATOB_REGEX.find(jsScript) ?: return emptyList()

        var encodeScript = "("
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atobMatch.range.last until jsScript.length) {
            val char = jsScript[i]
            when (char) {
                '(', '{' -> deque.addFirst(char)
                ')', '}' -> if (deque.isNotEmpty()) deque.removeFirst()
            }
            encodeScript += char
            if (deque.isEmpty()) break
        }

        val hlsHeaders = headers.newBuilder()
            .set("Referer", "https://$playerHost/")
            .set("Origin", "https://$playerHost")
            .build()

        val qualityMap = mapOf(
            "360" to kodikData.links.ugly,
            "480" to kodikData.links.bad,
            "720" to kodikData.links.good,
        )

        return QuickJs.create().use { qjs ->
            qualityMap.flatMap { (qualityName, links) ->
                val encodedSrc = links.firstOrNull()?.src ?: return@flatMap emptyList()
                val base64Url = runCatching {
                    qjs.evaluate("t='$encodedSrc'; $encodeScript").toString()
                }.getOrNull() ?: return@flatMap emptyList()

                val hlsUrl = runCatching {
                    Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8)
                }.getOrNull()?.fixProtocol() ?: return@flatMap emptyList()

                if (hlsUrl.contains(".mpd")) {
                    PlaylistUtils(client, headers).extractFromDash(
                        hlsUrl,
                        { res: String -> "$dubbing (${qualityName}p Kodik - $res)" },
                        hlsHeaders,
                        hlsHeaders,
                    )
                } else {
                    listOf(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
                }
            }
        }
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Предпочитаемое качество"
            entries = arrayOf("720p", "480p", "360p")
            entryValues = arrayOf("720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun String.fixProtocol(): String = if (startsWith("//")) "https:$this" else this

    // urlParams is a JSON blob assigned to a JS variable, quoted with either quote style.
    private fun extractUrlParams(pageHtml: String): String? = URL_PARAMS_SINGLE_QUOTED_REGEX.find(pageHtml)?.groupValues?.get(1)
        ?: URL_PARAMS_DOUBLE_QUOTED_REGEX.find(pageHtml)?.groupValues?.get(1)

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val EP_COUNT_REGEX = Regex("""\((\d+)\s*эп""")
        private val ATOB_REGEX = Regex("atob\\([^\"]")
        private val SELF_CLOSING_SCRIPT_REGEX = Regex("""<script([^>]*)/>""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""")
        private val URL_PARAMS_SINGLE_QUOTED_REGEX = Regex("""urlParams\s*=\s*'([^']+)'""")
        private val URL_PARAMS_DOUBLE_QUOTED_REGEX = Regex("""urlParams\s*=\s*"([^"]+)"""")
        private val VIDEO_TYPE_REGEX = Regex("""\.type\s*=\s*['"]([^'"]+)['"]""")
        private val VIDEO_HASH_REGEX = Regex("""\.hash\s*=\s*['"]([^'"]+)['"]""")
        private val VIDEO_ID_REGEX = Regex("""\.id\s*=\s*['"]?([A-Za-z0-9]+)['"]?""")
    }
}
