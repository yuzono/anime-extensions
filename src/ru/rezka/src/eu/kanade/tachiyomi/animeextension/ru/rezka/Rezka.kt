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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class Rezka :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HDRezka"

    override val lang = "ru"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

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

    // get_cdn_series either answers within a second or hangs; cap it so a hang falls
    // through to the error path instead of holding the player for the app's 2-minute limit.
    private val ajaxClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(AJAX_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

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
            return GET(url, headers)
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
            val title = link.text().ifEmpty { item.selectFirst(".b-content__inline_item-cover img")?.attr("alt") }
                ?.ifBlank { null }
                ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = item.selectFirst(".b-content__inline_item-cover img")
                    ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            }
        }

        val nav = document.selectFirst(".b-navigation")
        val hasNextPage = nav != null && (
            nav.select("a").any { it.text().toIntOrNull()?.let { n -> n > page } == true } ||
                nav.selectFirst("a.b-navigation__next") != null
            )
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = (document.selectFirst(".b-post__title") ?: document.selectFirst("h1"))!!.text()
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
        // internalData: path|type|season|episode|post_id|translator_id
        val prefix = "$titlePath|$type|$season|$ep|$postId"

        // Films: the page inits the player with the stream list of the voiceover it opens
        // with. That hoster comes back with its videos already attached, so the app plays
        // it straight away instead of first waiting on a get_cdn_series round trip.
        val inlineVideos = if (type == "movie") parseInlinePlayer(html) else emptyList()
        val inlineTranslatorId = INIT_TRANSLATOR_REGEX.find(html)?.groupValues?.get(1)
        val defaultTranslatorId = inlineTranslatorId ?: scrapeTranslatorId(html)

        if (translators.isNotEmpty()) {
            // The voiceover the page inits the player with is the only eager hoster:
            // it is what auto-play and external players resolve. Every other voiceover
            // is lazy — the app loads it only when the user taps it. Making them all
            // eager makes the app fire one get_cdn_series POST per voiceover in
            // parallel, and the site's anti-flood then drops most of them (only a few
            // hosters ever show up in the sheet).
            val defaultTranslator = translators.firstOrNull { it.id == defaultTranslatorId }
                ?: translators.firstOrNull { it.active }
                ?: translators.first()

            return translators.map { translator ->
                val isDefault = translator === defaultTranslator
                Hoster(
                    hosterName = translator.name,
                    internalData = "$prefix|${translator.id}",
                    // Only hand over the inline streams when they provably belong to this
                    // voiceover (or the page didn't say, and this is the one it opens with).
                    videoList = inlineVideos.takeIf {
                        isDefault && it.isNotEmpty() &&
                            (inlineTranslatorId == null || inlineTranslatorId == translator.id)
                    },
                    lazy = !isDefault,
                )
            }.sortedBy { it.lazy } // the default (eager) hoster goes first
        }

        // Single voiceover — its id lives only in the player init script.
        val soleId = defaultTranslatorId ?: "0"
        return listOf(
            Hoster(
                hosterName = "По умолчанию",
                internalData = "$prefix|$soleId",
                videoList = inlineVideos.ifEmpty { null },
            ),
        )
    }

    // ─── Videos ───────────────────────────────────────────────────────────────

    // Unused hooks of the deprecated request/parse flow — the suspend overrides above
    // cover everything. Throwing (instead of returning empty) matches the base contract.
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        // internalData segments: path|type|season|episode|post_id|translator_id
        val parts = hoster.internalData.split("|")
        val type = parts.getOrElse(1) { "movie" }
        // The endpoint expects season/episode for movies too (the site's own player
        // posts "1"/"1" for films) — not the legacy is_camrip/is_ads/is_director set.
        val season = parts.getOrElse(2) { "" }.ifBlank { "1" }
        val ep = parts.getOrElse(3) { "" }.ifBlank { "1" }
        val postId = parts.getOrElse(4) { "" }
        val translatorId = parts.getOrElse(5) { "0" }

        val body = FormBody.Builder().apply {
            add("id", postId)
            add("translator_id", translatorId)
            add("season", season)
            add("episode", ep)
            add("action", if (type == "series") "get_stream" else "get_movie")
        }.build()

        // Network, timeout and HTTP errors propagate (and so does cancellation). Only the
        // body is parsed leniently: it is not a stable API and changes without notice.
        val response = ajaxClient.post("$baseUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}", ajaxHeaders(), body)
            .bodyString()

        val decoded = runCatching { response.parseAs<CdnResponse>() }.getOrNull()

        if (decoded?.success == true) {
            val videos = parseStreams(
                decodeStreams(decoded.url.stringOrNull().orEmpty()),
                parseSubtitles(decoded.subtitle, decoded.subtitleLns),
            )
            if (videos.isNotEmpty()) return videos
        }

        // Surface the server's own reason (login / premium gate) instead of a bare empty list.
        decoded?.message?.takeIf { it.isNotBlank() }?.let { throw Exception(it) }
        return emptyList()
    }

    // The app applies sortVideos() to every hoster's list itself, so the preferred
    // quality is hoisted through the hook instead of manual sorting.
    override fun List<Video>.sortVideos(): List<Video> = applyQualityPreference(this)

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

    // The player init call carries a JSON config — {"id":"cdnplayer","streams":"[360p]…",
    // "subtitle":…} — with the stream list of the voiceover the page opens with.
    // Mirrors whose script has another shape still quote the bare stream list, which
    // INLINE_STREAM_REGEX picks up.
    private fun parseInlinePlayer(html: String): List<Video> {
        findPlayerConfig(html)?.let { config ->
            val videos = parseStreams(
                decodeStreams(config.streams.stringOrNull().orEmpty()),
                parseSubtitles(config.subtitle, config.subtitleLns),
            )
            if (videos.isNotEmpty()) return videos
        }
        val raw = INLINE_STREAM_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        return parseStreams(raw.unescapeJsString(), emptyList())
    }

    private fun findPlayerConfig(html: String): PlayerConfig? {
        val call = PLAYER_INIT_REGEX.find(html) ?: return null
        val start = html.indexOf('{', call.range.last)
        // The config is the last argument, after a handful of short scalars.
        if (start == -1 || start - call.range.last > 500) return null
        val end = findObjectEnd(html, start) ?: return null
        return runCatching { html.substring(start, end + 1).parseAs<PlayerConfig>() }.getOrNull()
    }

    // Index of the brace that closes the JSON object opening at [start]; braces inside
    // string literals (stream labels carry HTML, ad configs carry JS) don't count.
    private fun findObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    // Some mirrors return the stream list already decoded (starts with "[360p]…"),
    // others "trash"-encode it (starts with "#h" / contains //_//).
    private fun decodeStreams(raw: String): String = if (raw.trimStart().startsWith("[")) raw else clearTrash(raw)

    // The site sends `false` instead of a string/object for absent fields
    // ("subtitle":false, "subtitle_lns":false), so those are read as raw JSON.
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

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
        // Each quality lists several mirror URLs after " or " — keep only one so a single
        // (dubbing, quality) doesn't show up multiple times. A ":hls:manifest.m3u8" suffix
        // names the HLS side of the same rendition; the bare target is the MP4. HLS starts
        // after the first segment, while an MP4 whose index (moov) sits at the end of the
        // file can stall the player for tens of seconds before the first frame.
        val mirrors = match.groupValues[2].split(" or ").map { it.trim() }
        val url = if (preferHls) {
            mirrors.firstOrNull { it.endsWith(".m3u8") } ?: mirrors.first()
        } else {
            mirrors.first().removeSuffix(HLS_SUFFIX)
        }
        if (!url.startsWith("http")) return@mapNotNull null
        Video(
            videoUrl = url,
            videoTitle = quality,
            headers = headers,
            subtitleTracks = subs,
        )
    }

    private val preferHls: Boolean
        get() = preferences.getString(PREF_FORMAT_KEY, PREF_FORMAT_DEFAULT) == "hls"

    // Subtitles come in the same bracketed grammar as the quality list; the codes map
    // maps each label to a language code ("откл." is the player's own "off" menu item).
    private fun parseSubtitles(subtitle: JsonElement?, subtitleLns: JsonElement?): List<Track> {
        val data = subtitle.stringOrNull()?.ifBlank { null } ?: return emptyList()
        val codes = subtitleLns as? JsonObject
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

        screen.addListPreference(
            key = PREF_FORMAT_KEY,
            default = PREF_FORMAT_DEFAULT,
            title = "Формат потока / Stream format",
            summary = "%s\nHLS запускается быстрее; MP4 — прямой файл, на части фильмов стартует с задержкой.",
            entries = listOf("HLS (m3u8)", "MP4"),
            entryValues = listOf("hls", "mp4"),
        )
    }

    // ─── DTO / helpers ───────────────────────────────────────────────────────

    // `subtitle` / `subtitle_lns` (and on failures even `url`) come back as the boolean
    // `false` rather than a string/object, so they are kept as raw JSON.
    @Serializable
    private class CdnResponse(
        val success: Boolean = false,
        val url: JsonElement? = null,
        val message: String? = null,
        val subtitle: JsonElement? = null,
        @SerialName("subtitle_lns") val subtitleLns: JsonElement? = null,
    )

    // The player config inlined in the watch page (same fields as CdnResponse, but the
    // stream list is under `streams`).
    @Serializable
    private class PlayerConfig(
        val streams: JsonElement? = null,
        val subtitle: JsonElement? = null,
        @SerialName("subtitle_lns") val subtitleLns: JsonElement? = null,
    )

    private class Translator(val name: String, val id: String, val active: Boolean)

    companion object {
        private val qualityRegex = Regex("""(\d{3,4})\s*[pр]""")
        private val POST_ID_REGEX = Regex("""initCDN(?:Movies|Series)Events\((\d+)""")
        private val TRANSLATOR_ID_REGEX = Regex(
            """(?:initCDN(?:Movies|Series)Events\(\s*\d+\s*,\s*|["']?translator_id["']?\s*[:=]\s*)["']?(\d+)["']?""",
        )
        private val PLAYER_INIT_REGEX = Regex("""initCDN(?:Movies|Series)Events\(""")
        private val INIT_TRANSLATOR_REGEX = Regex("""initCDN(?:Movies|Series)Events\(\s*\d+\s*,\s*(\d+)""")
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
        private const val PREF_FORMAT_KEY = "pref_stream_format"
        private const val PREF_FORMAT_DEFAULT = "hls"
        private const val HLS_SUFFIX = ":hls:manifest.m3u8"
        private const val AJAX_TIMEOUT_SECONDS = 15L
    }
}
