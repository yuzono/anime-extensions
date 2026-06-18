package eu.kanade.tachiyomi.animeextension.ru.rezka

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class Rezka :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HDRezka"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl: String
        get() {
            val selected = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            val raw = if (selected == CUSTOM_DOMAIN) {
                preferences.getString(PREF_CUSTOM_DOMAIN_KEY, "")!!.ifBlank { PREF_DOMAIN_DEFAULT }
            } else {
                selected
            }
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")

    private fun ajaxHeaders(): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    // ─── Popular ─────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/?filter=popular", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response, page = response.pageNum())

    // ─── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request {
        // "Latest" tab uses the /new/ page; the mode (last / popular / watching) is configurable.
        val filter = preferences.getString(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)!!
        val path = if (page <= 1) "$baseUrl/new/" else "$baseUrl/new/page/$page/"
        val url = if (filter == "last") path else "$path?filter=$filter"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response, page = response.pageNum())

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            return GET("$baseUrl/search/?do=search&subaction=search&q=$q&page=$page", headers)
        }

        var section = ""
        var genre = ""
        filters.forEach { filter ->
            when (filter) {
                is SectionFilter -> section = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                else -> {}
            }
        }
        // Genre pages live at the site root (e.g. /action/); they take priority over section.
        val path = when {
            genre.isNotEmpty() -> "/$genre"
            section.isNotEmpty() -> "/$section"
            else -> ""
        }
        return GET("$baseUrl$path/page/$page/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response, page = response.pageNum())

    private fun Response.pageNum(): Int = request.url.pathSegments.let { seg ->
        val idx = seg.indexOf("page")
        seg.getOrNull(idx + 1)?.toIntOrNull() ?: request.url.queryParameter("page")?.toIntOrNull() ?: 1
    }

    private fun parseAnimeList(response: Response, page: Int): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(".b-content__inline_item").mapNotNull { item ->
            val link = item.selectFirst(".b-content__inline_item-link a")
                ?: item.selectFirst(".b-content__inline_item-cover a")
                ?: return@mapNotNull null
            val href = link.attr("abs:href").ifEmpty { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = link.text().ifBlank { item.selectFirst(".b-content__inline_item-cover img")?.attr("alt") ?: "" }
                thumbnail_url = item.selectFirst(".b-content__inline_item-cover img")
                    ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            }
        }

        val nav = document.selectFirst(".b-navigation")
        val hasNextPage = nav != null && (
            nav.select("a").any { it.text().trim().toIntOrNull()?.let { n -> n > page } == true } ||
                nav.selectFirst("a.b-navigation__next") != null
            )
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".b-post__title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".b-sidecover img, .b-post__infotable_left img")
                ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            description = document.selectFirst(".b-post__description_text")?.text()?.trim()
            genre = document.select("span[itemprop=genre], .b-post__info a[href*=/genre/]")
                .joinToString { it.text() }
                .ifBlank { null }
            author = document.select("span[itemprop=director] a, .b-post__info a[href*=/person/]")
                .firstOrNull()?.text()
        }
    }

    // ─── Episodes ───────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val path = response.request.url.encodedPath

        val episodeItems = document.select(".b-simple_episode__item")
        if (episodeItems.isEmpty()) {
            // Movie (single video).
            return listOf(
                SEpisode.create().apply {
                    name = "Фильм"
                    episode_number = 1f
                    url = "$path|movie||"
                },
            )
        }

        // Series: one entry per season/episode shown in the default-translator DOM.
        // Use a continuous 1..N episode_number — composite numbers like season*1000+episode
        // create huge gaps that Mihon renders as "missing N items" dividers.
        val parsed = episodeItems.mapNotNull { el ->
            val season = el.attr("data-season_id").ifBlank { return@mapNotNull null }
            val ep = el.attr("data-episode_id").ifBlank { return@mapNotNull null }
            Triple(season, ep, "$path|series|$season|$ep")
        }.sortedWith(compareBy({ it.first.toIntOrNull() ?: 0 }, { it.second.toIntOrNull() ?: 0 }))

        return parsed.mapIndexed { index, (season, ep, epUrl) ->
            SEpisode.create().apply {
                name = "$season сезон, $ep серия"
                episode_number = (index + 1).toFloat()
                url = epUrl
            }
        }.reversed()
    }

    // ─── Video list ───────────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("|")
        val titlePath = parts.getOrElse(0) { "" }
        val type = parts.getOrElse(1) { "movie" }
        val season = parts.getOrElse(2) { "" }
        val ep = parts.getOrElse(3) { "" }

        val document = client.newCall(GET("$baseUrl$titlePath", headers)).awaitSuccess()
            .use { it.useAsJsoup() }
        val html = document.html()

        val postId = Regex("""initCDN(?:Movies|Series)Events\((\d+)""").find(html)?.groupValues?.get(1)
            ?: document.selectFirst("#post_id")?.attr("value")
            ?: titlePath.trimStart('/').substringBefore('-').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return emptyList()

        val favs = Regex("""['"]?favs['"]?\s*[:=]\s*['"]([a-f0-9-]+)['"]""").find(html)?.groupValues?.get(1)
            ?: document.selectFirst("#ctrl_favs")?.attr("value")
            ?: ""

        val translators = parseTranslators(document, html)

        return translators.parallelCatchingFlatMap { (translatorName, translatorId) ->
            val body = FormBody.Builder().apply {
                add("id", postId)
                add("translator_id", translatorId)
                if (type == "series") {
                    add("season", season)
                    add("episode", ep)
                    add("action", "get_stream")
                } else {
                    add("is_camrip", "0")
                    add("is_ads", "0")
                    add("is_director", "0")
                    add("action", "get_movie")
                }
                add("favs", favs)
            }.build()

            val cdnUrl = "$baseUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}"
            val json = client.newCall(POST(cdnUrl, ajaxHeaders(), body)).awaitSuccess().use { it.body.string() }

            val encoded = Regex(""""url"\s*:\s*"(.*?[^\\])"""").find(json)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.decodeJsonUnicode()
                ?: return@parallelCatchingFlatMap emptyList()

            val subtitleTracks = Regex(""""subtitle"\s*:\s*"(.*?[^\\])"""").find(json)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.decodeJsonUnicode()
                ?.let(::parseSubtitles)
                ?: emptyList()

            // Some mirrors return the stream list already decoded (starts with "[360p]..."),
            // others "trash"-encode it (starts with "#h" / contains //_//). Only decode the latter.
            val streams = if (encoded.trimStart().startsWith("[")) encoded else clearTrash(encoded)
            parseStreams(streams, translatorName, subtitleTracks)
        }.let(::applyQualityPreference)
    }

    // Keep only the preferred quality; if it's unavailable, fall back to the closest one
    // (ties prefer higher). Entries whose quality can't be parsed are always kept.
    private fun applyQualityPreference(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.toIntOrNull()
            ?: return videos
        val available = videos.mapNotNull { it.quality.parseQuality() }.distinct()
        if (available.isEmpty()) return videos
        val target = available.minWithOrNull(
            compareBy({ kotlin.math.abs(it - pref) }, { -it }),
        ) ?: return videos
        return videos.filter { v -> v.quality.parseQuality()?.let { it == target } ?: true }
    }

    private fun String.parseQuality(): Int? {
        qualityRegex.find(this)?.let { return it.groupValues[1].toIntOrNull() }
        return when {
            contains("4K", ignoreCase = true) -> 2160
            contains("2K", ignoreCase = true) -> 1440
            else -> null
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    private fun parseTranslators(document: Document, html: String): List<Pair<String, String>> {
        val items = document.select(".b-translator__item")
        if (items.isNotEmpty()) {
            return items.mapNotNull { el ->
                val id = el.attr("data-translator_id").ifBlank { return@mapNotNull null }
                val name = el.attr("title").ifBlank { el.text() }.trim().ifBlank { "Перевод $id" }
                name to id
            }
        }
        // Single translator — read the default id from the player init call.
        val defaultId = Regex("""initCDN(?:Movies|Series)Events\(\d+,\s*(\d+)""")
            .find(html)?.groupValues?.get(1) ?: "0"
        return listOf("По умолчанию" to defaultId)
    }

    // Parse "[360p]url or url2,[480p]url,..." into Video objects.
    // Each quality lists several mirror URLs after " or " — keep only the first so a single
    // (dubbing, quality) doesn't show up multiple times.
    private fun parseStreams(data: String, translator: String, subs: List<Track>): List<Video> {
        return Regex("""\[([^\]]+)]([^,\[]+(?:\s+or\s+[^,\[]+)*)""").findAll(data).mapNotNull { match ->
            // Premium qualities embed HTML (e.g. <span class="pjs-prem-quality">1080p Ultra<img…></span>).
            // Strip tags and collapse whitespace so the label is clean.
            val quality = match.groupValues[1]
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            val url = match.groupValues[2].split(" or ")
                .map { it.trim() }
                .firstOrNull { it.startsWith("http") }
                ?: return@mapNotNull null
            Video(url, "$translator ($quality)", url, headers = headers, subtitleTracks = subs)
        }.toList()
    }

    // The CDN response is consumed as a raw string, so \uXXXX escapes (e.g. Cyrillic subtitle
    // language names) stay literal. Decode them to real characters.
    private fun String.decodeJsonUnicode(): String = Regex("""\\u([0-9a-fA-F]{4})""").replace(this) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun parseSubtitles(data: String): List<Track> = Regex("""\[([^\]]+)]([^,\[]+)""").findAll(data).mapNotNull { match ->
        val lang = match.groupValues[1].trim()
        val url = match.groupValues[2].trim()
        val normalized = if (url.startsWith("//")) "https:$url" else url
        if (normalized.startsWith("http")) {
            runCatching { Track(normalized, lang) }.getOrNull()
        } else {
            null
        }
    }.toList()

    // ─── HDRezka "trash" decoder ────────────────────────────────────────────────
    // The CDN `url` is base64 of the stream list, with random base64-encoded junk tokens
    // (combinations of @#!^$ of length 2–3) injected and chunks joined by "//_//".

    private val trashCodes: List<String> by lazy {
        val symbols = listOf("@", "#", "!", "^", "$")
        buildList {
            for (a in symbols) {
                for (b in symbols) {
                    add(a + b)
                    for (c in symbols) add(a + b + c)
                }
            }
        }.map { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
    }

    private fun clearTrash(data: String): String {
        var s = data.removePrefix("#h").split("//_//").joinToString("")
        for (code in trashCodes) s = s.replace(code, "")
        s = s.trim().trimEnd('=')
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
    }

    // ─── Filters ─────────────────────────────────────────────────────────────

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Фильтры игнорируются при текстовом поиске"),
        SectionFilter(),
        GenreFilter(),
    )

    private open class UriPartFilter(name: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class SectionFilter :
        UriPartFilter(
            "Раздел",
            arrayOf(
                "Все" to "",
                "Фильмы" to "films",
                "Сериалы" to "series",
                "Мультфильмы" to "cartoon",
                "Аниме" to "anime",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Жанр",
            arrayOf(
                "Любой" to "",
                "Боевик" to "action",
                "Комедия" to "comedy",
                "Драма" to "drama",
                "Мелодрама" to "melodrama",
                "Детектив" to "detective",
                "Криминал" to "crime",
                "Триллер" to "thriller",
                "Ужасы" to "horror",
                "Фантастика" to "fantastic",
                "Фэнтези" to "fantasy",
                "Приключения" to "adventures",
                "Военный" to "military",
                "Исторический" to "historical",
                "Документальный" to "documentary",
                "Семейный" to "family",
                "Биография" to "biography",
            ),
        )

    // ─── Settings ─────────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            default = PREF_DOMAIN_DEFAULT,
            title = "Зеркало / Mirror",
            summary = "%s\nПерезапустите приложение после смены.",
            entries = listOf(
                "rezka-ua.pub",
                "hdrezka.me",
                "omnirezka.tv",
                "hello-rezka.tv",
                "hdrezka.fi (только premium / вход)",
                "Свой домен (указать ниже)",
            ),
            entryValues = listOf(
                "https://rezka-ua.pub",
                "https://hdrezka.me",
                "https://omnirezka.tv",
                "https://hello-rezka.tv",
                "https://hdrezka.fi",
                CUSTOM_DOMAIN,
            ),
        )

        screen.addEditTextPreference(
            key = PREF_CUSTOM_DOMAIN_KEY,
            default = "",
            title = "Свой домен / Custom domain",
            summary = "Используется, если выше выбрано «Свой домен». Напр. https://example.tv",
        )

        screen.addListPreference(
            key = PREF_LATEST_KEY,
            default = PREF_LATEST_DEFAULT,
            title = "Раздел «Последние» / Latest tab",
            summary = "%s",
            entries = listOf("Последние", "Популярные", "Смотрят"),
            entryValues = listOf("last", "popular", "watching"),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Предпочитаемое качество / Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    companion object {
        private val qualityRegex = Regex("""(\d{3,4})\s*[pр]""")
        private const val PREF_DOMAIN_KEY = "pref_domain_v2"
        private const val PREF_DOMAIN_DEFAULT = "https://hdrezka.me"
        private const val PREF_CUSTOM_DOMAIN_KEY = "pref_custom_domain"
        private const val CUSTOM_DOMAIN = "custom"
        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "last"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }
}
