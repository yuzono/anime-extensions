package eu.kanade.tachiyomi.animeextension.ru.rezka

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    // No hardcoded User-Agent: the tracker is not behind Cloudflare, and the app's default one
    // already is a browser UA. `super.headersBuilder()` keeps it, so it also stays current.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun ajaxHeaders(): Headers = headers.newBuilder()
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
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("do", "search")
                .addQueryParameter("subaction", "search")
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
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
            title = document.selectFirst(".b-post__title")?.text()
                ?: document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".b-sidecover img, .b-post__infotable_left img")
                ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            description = document.selectFirst(".b-post__description_text")?.text()
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

        // `data-*` attributes rather than class names: the site's own player reads these,
        // so they survive a restyle (verified against the current engine incl. hdrezka.fi).
        val episodeItems = document.select("[data-episode_id]")
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

        // Episodes may omit data-season_id (the season tabs carry it as data-tab_id);
        // fall back to the active season tab, then to "1".
        val activeSeasonId = document.selectFirst("[data-tab_id].active")
            ?.attr("data-tab_id").orEmpty().ifBlank { "1" }

        // Series: one entry per season/episode shown in the default-translator DOM.
        // Use a continuous 1..N episode_number — composite numbers like season*1000+episode
        // create huge gaps that Mihon renders as "missing N items" dividers.
        val parsed = episodeItems.mapNotNull { el ->
            val season = el.attr("data-season_id").ifBlank { activeSeasonId }
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

    // ─── Hosters (hoster-based API, extensions-lib 16) ────────────────────────

    // One hoster per voiceover (translator): switching the audio track in the player
    // actually switches the stream — the app switches hosters, while the videos inside
    // a hoster are just the qualities of that one voiceover.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val titlePath = parts.getOrElse(0) { "" }
        val type = parts.getOrElse(1) { "movie" }
        val season = parts.getOrElse(2) { "" }
        val ep = parts.getOrElse(3) { "" }

        val document = client.get("$baseUrl$titlePath", headers).useAsJsoup()
        val html = document.html()

        val translators = parseTranslators(document)

        // Without a session (hdrezka.fi and other gated mirrors) a watch page is
        // replaced by the login screen, which carries none of the watchable-page
        // markers. Detect it instead of "no available videos" deep inside the flow.
        if (translators.isEmpty() &&
            document.selectFirst("#post_id, [data-id]") == null &&
            POST_ID_REGEX.find(html) == null &&
            (type != "movie" || INLINE_STREAM_REGEX.find(html) == null)
        ) {
            throw Exception(
                "Страница просмотра не открылась — возможно, требуется вход. " +
                    "Для hdrezka.fi войдите в аккаунт через «Открыть в WebView» и повторите.",
            )
        }

        val postId = findPostId(document, html, titlePath) ?: ""
        // internalData: path|type|season|episode|post_id|translator_id[|inline fallback]
        val prefix = "$titlePath|$type|$season|$ep|$postId"

        // hdrezka.fi inlines the stream list of the *default* voiceover in a <script>
        // (the player's first paint), so it can only serve as a fallback for that one
        // voiceover — for any other hoster it would be the wrong streams.
        val inlineFallback = if (type == "movie") findInlineStreams(html) else null
        val defaultTranslatorId = scrapeTranslatorId(html)

        if (translators.isNotEmpty()) {
            return translators.map { translator ->
                val isDefault = translator.id == defaultTranslatorId || translator.active
                val internalData = if (inlineFallback != null && isDefault) {
                    "$prefix|${translator.id}|$inlineFallback"
                } else {
                    "$prefix|${translator.id}"
                }
                Hoster(hosterName = translator.name, internalData = internalData)
            }
        }

        // Single voiceover — its id lives only in the player init script.
        val soleId = defaultTranslatorId ?: "0"
        val internalData = if (inlineFallback != null) {
            "$prefix|$soleId|$inlineFallback"
        } else {
            "$prefix|$soleId"
        }
        return listOf(Hoster(hosterName = "По умолчанию", internalData = internalData))
    }

    // ─── Videos ───────────────────────────────────────────────────────────────

    // Unused hooks of the deprecated request/parse flow — the suspend overrides above
    // cover everything. Throwing (instead of returning empty) matches the base contract.
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        // internalData segments: path|type|season|episode|post_id|translator_id[|inline fallback]
        val parts = hoster.internalData.split("|", limit = 7)
        val type = parts.getOrElse(1) { "movie" }
        // The endpoint expects season/episode for movies too (the site's own player
        // posts "1"/"1" for films) — not the legacy is_camrip/is_ads/is_director set.
        val season = parts.getOrElse(2) { "" }.ifBlank { "1" }
        val ep = parts.getOrElse(3) { "" }.ifBlank { "1" }
        val postId = parts.getOrElse(4) { "" }
        val translatorId = parts.getOrElse(5) { "0" }
        val inlineFallback = parts.getOrNull(6)

        val body = FormBody.Builder().apply {
            add("id", postId)
            add("translator_id", translatorId)
            add("season", season)
            add("episode", ep)
            add("action", if (type == "series") "get_stream" else "get_movie")
        }.build()

        // The response is not a stable API — the site changes it without notice — so
        // never let a single failed call kill the fallback path.
        val response = runCatching {
            client.post("$baseUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}", ajaxHeaders(), body)
                .bodyString()
        }.getOrNull()

        val decoded = response?.let { runCatching { it.parseAs<CdnResponse>() }.getOrNull() }

        if (decoded?.success == true) {
            val raw = decoded.url.orEmpty()
            // Some mirrors return the stream list already decoded (starts with "[360p]…"),
            // others "trash"-encode it (starts with "#h" / contains //_//).
            val streams = if (raw.trimStart().startsWith("[")) raw else clearTrash(raw)
            val videos = parseStreams(streams, parseSubtitles(decoded))
            if (videos.isNotEmpty()) return videos
        }

        // Films: the default voiceover's stream list is inlined on the page itself, so a
        // change in the endpoint contract (or a missing session) still leaves a way in.
        inlineFallback?.let {
            val videos = parseStreams(it.unescapeJsString(), emptyList())
            if (videos.isNotEmpty()) return videos
        }

        // Surface the server's own reason (login / premium gate) instead of a bare empty list.
        decoded?.message?.takeIf { it.isNotBlank() }?.let { throw Exception(it) }
        return emptyList()
    }

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

    private fun String.parseQuality(): Int? {
        qualityRegex.find(this)?.let { return it.groupValues[1].toIntOrNull() }
        return when {
            contains("4K", ignoreCase = true) -> 2160
            contains("2K", ignoreCase = true) -> 1440
            else -> null
        }
    }

    // ─── Parsers ─────────────────────────────────────────────────────────────

    // Voiceover tabs, read structurally (`data-*`) — the site's own player code reads
    // these too, so they survive a restyle. Class names are only trusted for the
    // "active" (current) and "prem" (PRO-only) state markers.
    private fun parseTranslators(document: Document): List<Translator> = document.select("[data-translator_id]").mapNotNull { el ->
        val id = el.attr("data-translator_id").ifBlank { return@mapNotNull null }
        val name = el.attr("title").ifBlank { el.text() }.ifBlank { "Перевод $id" }
        Translator(name, id, el.hasClass("active"))
    }

    // The translator id hides in the player init script in several shapes, depending
    // on the engine version/mirror: `initCDNMoviesEvents(123, 45, …)`,
    // `"translator_id": 45` or `translator_id = 45`. Only the initCDN form existed on
    // the old free mirrors; hdrezka.fi pages can use the others.
    private fun scrapeTranslatorId(html: String): String? = TRANSLATOR_ID_REGEX.find(html)?.groupValues?.get(1)

    private fun findPostId(document: Document, html: String, titlePath: String): String? = POST_ID_REGEX.find(html)?.groupValues?.get(1)
        ?: document.selectFirst("#post_id")?.attr("value")?.ifBlank { null }
        ?: document.selectFirst("[data-id]")?.attr("data-id")?.ifBlank { null }
        // Last path segment's leading digits (/films/drama/1178-amadey-1984.html → 1178).
        ?: titlePath.trim('/').substringAfterLast('/').substringBefore('-')
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    // The first-paint stream list for the default voiceover, JS-escaped inside a
    // <script> (quoted "[1080p]https://…,[720p]…"). Extracted raw and unescaped later.
    private fun findInlineStreams(html: String): String? = INLINE_STREAM_REGEX.find(html)?.groupValues?.get(1)

    private fun String.unescapeJsString(): String {
        // \uXXXX escapes first, then any leftover backslashes (e.g. escaped "\/").
        val unescaped = JSON_UNICODE_ESCAPE_REGEX.replace(this) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return unescaped.replace("\\", "")
    }

    // Parse "[360p]url or url2,[480p]url,..." into Video objects.
    // A comma only separates entries when it is followed by "[" — labels and URLs
    // may themselves contain commas (e.g. signed CDN query strings).
    private fun parseStreams(data: String, subs: List<Track>): List<Video> = data.split(STREAM_COMMA_REGEX).mapNotNull { part ->
        val match = STREAM_ENTRY_REGEX.find(part) ?: return@mapNotNull null
        // Premium qualities embed HTML (e.g. <span class="pjs-prem-quality">1080p Ultra<img…></span>).
        // Strip tags and collapse whitespace so the label is clean.
        val quality = match.groupValues[1]
            .replace(HTML_TAG_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        // Each quality lists several mirror URLs after " or " — keep only the first so a
        // single (dubbing, quality) doesn't show up multiple times. A ":hls:manifest.m3u8"
        // suffix names the HLS side of the same rendition; the bare target is the MP4.
        val url = match.groupValues[2]
            .split(" or ").first().trim()
            .removeSuffix(":hls:manifest.m3u8").trim()
        if (!url.startsWith("http")) return@mapNotNull null
        Video(
            videoUrl = url,
            videoTitle = quality,
            headers = headers,
            subtitleTracks = subs,
        )
    }.let(::applyQualityPreference)

    // Subtitles come in the same bracketed grammar as the quality list; the codes map
    // maps each label to a language code ("откл." is the player's own "off" menu item).
    private fun parseSubtitles(decoded: CdnResponse): List<Track> {
        val data = decoded.subtitle ?: return emptyList()
        val codes = decoded.subtitleLns as? JsonObject
        return data.split(STREAM_COMMA_REGEX).mapNotNull { part ->
            val match = STREAM_ENTRY_REGEX.find(part) ?: return@mapNotNull null
            val label = match.groupValues[1].replace(HTML_TAG_REGEX, "").trim()
            val url = match.groupValues[2].trim()
            val normalized = if (url.startsWith("//")) "https:$url" else url
            if (!normalized.startsWith("http")) return@mapNotNull null
            val lang = codes?.get(label)?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: label
            runCatching { Track(normalized, lang) }.getOrNull()
        }
    }

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

    // ─── DTO / helpers ───────────────────────────────────────────────────────

    // `subtitle_lns` comes back as the boolean `false` (not as an object) when a
    // voiceover has no subtitles, so it must be a JsonElement rather than a Map.
    @Serializable
    private data class CdnResponse(
        val success: Boolean = false,
        val url: String? = null,
        val message: String? = null,
        val subtitle: String? = null,
        @SerialName("subtitle_lns") val subtitleLns: JsonElement? = null,
    )

    private data class Translator(val name: String, val id: String, val active: Boolean)

    companion object {
        private val qualityRegex = Regex("""(\d{3,4})\s*[pр]""")
        private val POST_ID_REGEX = Regex("""initCDN(?:Movies|Series)Events\((\d+)""")
        private val TRANSLATOR_ID_REGEX = Regex(
            """(?:initCDN(?:Movies|Series)Events\(\s*\d+\s*,\s*|["']?translator_id["']?\s*[:=]\s*)["']?(\d+)["']?""",
        )
        private val INLINE_STREAM_REGEX = Regex("""["'](\[(?:1080|720|480|360|2160)p[^\]]*\][^"']+)["']""")
        private val STREAM_COMMA_REGEX = Regex(""",(?=\[)""")
        private val STREAM_ENTRY_REGEX = Regex("""^\[([^\]]+)\](.+)$""", RegexOption.DOT_MATCHES_ALL)
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val JSON_UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")
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
