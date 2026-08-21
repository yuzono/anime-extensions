package eu.kanade.tachiyomi.animeextension.ru.jutsu

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
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
import uy.kohesive.injekt.injectLazy

class JutsuTv :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Jutsu TV"
    override val baseUrl = "https://jutsu.tv"
    override val lang = "ru"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    // "Топ 100" — a single page with the 100 most popular titles.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top/", headers)

    override fun popularAnimeSelector(): String = "div.krasik"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.krasik__title")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("div.krasik__img img")?.absUrl("src")
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = "div.pagination__pages span:not(.nav_ext) + a"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

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
        val year = filterList.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart()

        return when {
            !genre.isNullOrBlank() -> GET("$baseUrl${genre}page/$page/", headers)
            !year.isNullOrBlank() -> GET("$baseUrl${year}page/$page/", headers)
            !category.isNullOrBlank() -> GET("$baseUrl${category}page/$page/", headers)
            else -> latestUpdatesRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Фильтры не работают при текстовом поиске"),
        CategoryFilter(),
        GenreFilter(),
        YearFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter :
        UriPartFilter(
            "Категория",
            arrayOf(
                "Все" to "",
                "Сериалы" to "/tv-series/",
                "ONA" to "/ona/",
                "OVA" to "/ova/",
                "Спешл" to "/special/",
                "Онгоинги" to "/ongoing/",
                "Китайские" to "/anime/chinese/",
                "С субтитрами" to "/anime/subtitles/",
                "DEEP" to "/anime/deep/",
                "Netflix" to "/anime/netflix/",
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "Год",
            arrayOf(
                "Все" to "",
                "2026" to "/anime/2026/",
                "2025" to "/anime/2025/",
                "2024" to "/anime/2024/",
                "2023" to "/anime/2023/",
                "2022" to "/anime/2022/",
                "2021" to "/anime/2021/",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Жанр",
            arrayOf(
                "Все" to "",
                "Боевые искусства" to "/anime/martial-arts/",
                "Военные" to "/anime/military/",
                "Выживание" to "/anime/survival/",
                "Гарем" to "/anime/harem/",
                "Детективы" to "/anime/detective/",
                "Драмы" to "/anime/drama/",
                "Игры" to "/anime/game/",
                "Исекай" to "/anime/isekai/",
                "Исторические" to "/anime/historical/",
                "Комедия" to "/anime/comedy/",
                "Кулинария" to "/anime/cooking/",
                "Магия" to "/anime/magic/",
                "Меха" to "/anime/mecha/",
                "Мистика" to "/anime/mystic/",
                "Музыка" to "/anime/music/",
                "Повседневность" to "/anime/slice-of-life/",
                "Приключения" to "/anime/adventure/",
                "Психология" to "/anime/psychological/",
                "Реинкарнация" to "/anime/reincarnation/",
                "Романтика" to "/anime/romance/",
                "Сверхъестественное" to "/anime/supernatural/",
                "Спортивные" to "/anime/sports/",
                "Триллеры" to "/anime/suspense/",
                "Ужасы" to "/anime/horror/",
                "Фантастика" to "/anime/sci-fi/",
                "Фэнтези" to "/anime/fantasy/",
                "Школа" to "/anime/school/",
                "Экшены" to "/anime/action/",
                "Этти" to "/anime/ecchi/",
            ),
        )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.zfx__img img")?.absUrl("src")

        // The synopsis lives in the "Описание аниме …" block; everything else on the
        // page (menus, SEO text in --main) must not leak into the description.
        description = document
            .selectFirst("div.jutsutv-zfx__text--top div.full-text, div.jutsutv-zfx__text--top p")
            ?.text()

        genre = document.select("ul.jutsutv-zfx__list li:has(span:contains(Жанр)) a")
            .joinToString { it.text() }
        // Студия; если её нет на странице — имя режиссёра.
        // ("Режисс" покрывает оба написания: «Режиссер» и «Режиссёр».)
        author = document.selectFirst("ul.jutsutv-zfx__list li:has(span:contains(Студия))")
            ?.text()?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("ul.jutsutv-zfx__list li:has(span:contains(Режисс))")
                ?.text()?.substringAfter(":")?.trim()

        // The status <li> carries a malformed attribute (=""), which some parsers choke
        // on — fall back from the label span to the li text to a whole-page regex.
        val statusText = document.selectFirst("span.jutsutv-jutsu-page__info-label")?.text()
            ?: document.select("li").firstOrNull { it.text().contains("Статус:") }?.text()
            ?: STATUS_REGEX.find(document.text())?.groupValues?.get(1)
            ?: ""
        status = when {
            statusText.contains("Онгоинг", ignoreCase = true) -> SAnime.ONGOING
            // Aniyomi has no dedicated "announced" status — the closest one is ONGOING.
            statusText.contains("Анонс", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("Вышел", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // The Kodik player is loaded through the DLE ajax controller; the request
        // parameters are stored on the placeholder element.
        val dataParams = document.selectFirst("div.xfplayer[data-params*=kodik]")
            ?.attr("data-params")
            ?: throw Exception("Плеер Kodik не найден на странице")

        val ajaxHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val playerResponse = client.newCall(
            GET("$baseUrl/engine/ajax/controller.php?$dataParams", ajaxHeaders),
        ).execute().parseAs<PlayerResponse>()

        if (!playerResponse.success || playerResponse.data.isBlank()) {
            throw Exception("Kodik плеер недоступен для этого тайтла")
        }

        val playerUrl = playerResponse.data.fixProtocol()

        // Movies and single videos: anything that is not a serial.
        if (!playerUrl.contains("/serial/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Фильм"
                    episode_number = 1F
                    url = playerUrl
                },
            )
        }

        // Serials: take the maximum episode count across the series list and all
        // translations ("Name (N эп.)").
        val playerDoc = fetchKodikDocument(playerUrl)

        val fromSeriesBox = playerDoc.select("div.serial-series-box option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull() ?: 0
        val fromTranslations = playerDoc.select("div.serial-translations-box option")
            .mapNotNull { EP_COUNT_REGEX.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

        val total = maxOf(fromSeriesBox, fromTranslations)
        if (total == 0) throw Exception("Не удалось получить список серий")

        return (total downTo 1).map { ep ->
            SEpisode.create().apply {
                name = "Серия $ep"
                episode_number = ep.toFloat()
                url = "$playerUrl?episode=$ep"
            }
        }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // =============================== Videos ===============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val requestUrl = episode.url.toHttpUrl()
        val episodeNum = requestUrl.queryParameter("episode")?.toIntOrNull()
        val isSerial = requestUrl.encodedPath.startsWith("/serial/")
        val playerHost = requestUrl.host
        val document = fetchKodikDocument(episode.url)

        val translations = document.select(
            "div.serial-translations-box option, div.movie-translations-box option",
        )

        val videos = if (translations.isEmpty()) {
            // Single translation — the fetched page itself is the player page.
            kodikVideoLinks(requestUrl.toString(), "Kodik")
        } else {
            translations.parallelCatchingFlatMap { option ->
                val mediaId = option.attr("data-media-id")
                val mediaHash = option.attr("data-media-hash")
                if (mediaId.isBlank() || mediaHash.isBlank()) return@parallelCatchingFlatMap emptyList()

                // Skip translations that do not have the requested episode yet.
                val epCount = EP_COUNT_REGEX.find(option.text())?.groupValues?.get(1)?.toIntOrNull()
                if (episodeNum != null && epCount != null && epCount < episodeNum) {
                    return@parallelCatchingFlatMap emptyList()
                }

                val dubbing = option.text().substringBefore(" (").trim().ifBlank { "Kodik" }
                val label = if (option.attr("data-translation-type") == "subtitles") {
                    "$dubbing (Субтитры)"
                } else {
                    dubbing
                }

                val mediaType = if (isSerial) "serial" else "video"
                val episodeQuery = if (isSerial && episodeNum != null) "?episode=$episodeNum" else ""
                val url = "https://$playerHost/$mediaType/$mediaId/$mediaHash/720p$episodeQuery"

                kodikVideoLinks(url, label)
            }
        }

        return applyQualityPreference(videos).sort()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Keep only the quality selected in the extension settings; if it is not available,
    // fall back to the closest one (ties prefer the higher quality). Videos whose quality
    // cannot be parsed are always kept, so the list never ends up empty.
    private fun applyQualityPreference(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.toIntOrNull()
            ?: return videos
        val available = videos.mapNotNull { it.quality.parseQuality() }.distinct()
        if (available.isEmpty()) return videos
        val target = available.minWithOrNull(
            compareBy({ kotlin.math.abs(it - pref) }, { -it }),
        ) ?: return videos
        return videos.filter { video -> video.quality.parseQuality()?.let { it == target } ?: true }
    }

    private fun String.parseQuality(): Int? = QUALITY_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull()

    // Voice-overs before subtitles.
    override fun List<Video>.sort(): List<Video> = sortedBy {
        it.quality.contains("Субтитры", ignoreCase = true)
    }

    // ─── Kodik player ─────────────────────────────────────────────────────

    // Base headers (incl. the default browser-like User-Agent) with the site referer.
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
    private fun fetchKodikDocument(url: String): Document {
        val body = client.newCall(GET(url, kodikHeaders)).execute().use { it.body.string() }
        return Jsoup.parse(body.replace(SELF_CLOSING_SCRIPT_REGEX, "<script$1></script>"), url)
    }

    private fun isUrlAvailable(url: String, headers: Headers): Boolean = runCatching {
        client.newCall(GET(url, headers)).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun kodikVideoLinks(playerPageUrl: String, dubbing: String): List<Video> {
        val page = runCatching {
            fetchKodikDocument(playerPageUrl)
        }.getOrNull() ?: return emptyList()

        val pageHtml = page.html()

        // urlParams is a JSON blob wrapped in quotes.
        val rawParams = Regex("""urlParams\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""urlParams\s*=\s*"([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val formData = runCatching {
            json.decodeFromString(KodikFormData.serializer(), rawParams)
        }.getOrNull() ?: return emptyList()

        if (formData.dSign.isEmpty()) return emptyList()

        // Per-episode type/id/hash come from the vInfo object:
        //     vInfo.type = 'seria';  vInfo.hash = '...';  vInfo.id = '1407443';
        val typeRe = Regex("""\.type\s*=\s*['"]([^'"]+)['"]""")
        val hashRe = Regex("""\.hash\s*=\s*['"]([^'"]+)['"]""")
        val idRe = Regex("""\.id\s*=\s*['"]?([A-Za-z0-9]+)['"]?""")
        var videoType: String? = null
        var videoId: String? = null
        var videoHash: String? = null
        for (script in page.select("script").map { it.data() }) {
            val t = typeRe.find(script)?.groupValues?.get(1) ?: continue
            val h = hashRe.find(script)?.groupValues?.get(1) ?: continue
            val i = idRe.find(script)?.groupValues?.get(1) ?: continue
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
                client.newCall(GET(scriptUrl, kodikHeaders)).execute().body.string()
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
            "1080" to kodikData.links.full,
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
                    buildList {
                        // Kodik's API reports at most 720p, but the CDN usually stores a
                        // 1080p rendition at the same path — probe for it.
                        if (qualityName == "720" && kodikData.links.full.isEmpty()) {
                            val hlsUrl1080 = hlsUrl.replace("/720.mp4", "/1080.mp4")
                            if (hlsUrl1080 != hlsUrl && isUrlAvailable(hlsUrl1080, hlsHeaders)) {
                                add(Video(hlsUrl1080, "$dubbing (1080p Kodik)", hlsUrl1080, headers = hlsHeaders))
                            }
                        }
                        add(Video(hlsUrl, "$dubbing (${qualityName}p Kodik)", hlsUrl, headers = hlsHeaders))
                    }
                }
            }
        }
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Предпочитаемое качество"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun String.fixProtocol(): String = if (startsWith("//")) "https:$this" else this

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val EP_COUNT_REGEX = Regex("""\((\d+)\s*эп""")
        private val STATUS_REGEX = Regex("""Статус:\s*([А-Яа-яёЁ]+)""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""")
        private val ATOB_REGEX = Regex("atob\\([^\"]")
        private val SELF_CLOSING_SCRIPT_REGEX = Regex("""<script([^>]*)/>""")
    }
}
