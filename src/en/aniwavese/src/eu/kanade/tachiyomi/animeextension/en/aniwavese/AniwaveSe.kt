package eu.kanade.tachiyomi.animeextension.en.aniwavese

import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AniwaveSe :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Aniwave.se"

    override val baseUrl by lazy {
        val customDomain = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)
        if (customDomain.isNullOrBlank()) {
            preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        } else {
            customDomain
        }
    }

    override val lang = "en"

    override val supportsLatest = true

    private val utils by lazy { AniwaveSeUtils() }

    private val preferences by getPreferencesLazy()

    private val refererHeaders = headers.newBuilder().apply {
        add("Referer", "$baseUrl/")
    }.build()

    private val markFiller by lazy { preferences.getBoolean(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending-anime")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        refererHeaders,
    )

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.select("a.name").let { a ->
            setUrlWithoutDomain(a.attr("href").substringBefore("?"))
            title = a.text()
        }
        thumbnail_url = element.select("div.poster img").attr("src")
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("anime-list")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }.build(),
        refererHeaders,
    )

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AniwaveSeFilters.getSearchParameters(filters)

        val vrf = if (query.isNotBlank()) utils.vrfEncrypt(query) else ""
        var url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", query)
        }.build().toString()

        if (searchParams.genre.isNotBlank()) url += searchParams.genre
        if (searchParams.genreMode.isNotBlank()) url += searchParams.genreMode
        if (searchParams.country.isNotBlank()) url += searchParams.country
        if (searchParams.season.isNotBlank()) url += searchParams.season
        if (searchParams.year.isNotBlank()) url += searchParams.year
        if (searchParams.type.isNotBlank()) url += searchParams.type
        if (searchParams.status.isNotBlank()) url += searchParams.status
        if (searchParams.language.isNotBlank()) url += searchParams.language
        if (searchParams.rating.isNotBlank()) url += searchParams.rating
        if (searchParams.sort.isNotBlank()) url += "&sort=${searchParams.sort}"

        return GET("$url&page=$page&vrf=$vrf", refererHeaders)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniwaveSeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val newDocument = resolveSearchAnime(document)
        anime.apply {
            title = newDocument.select("h1.title").text()
            genre = newDocument.select("div:contains(Genre) > span > a").joinToString { it.text() }
            description = newDocument.select("div.synopsis > div.shorting > div.content").text()
            author = newDocument.select("div:contains(Studio) > span > a").text()
            status = parseStatus(newDocument.select("div:contains(Status) > span").text())

            val altName = "Other name(s): "
            newDocument.select("h1.title").attr("data-jp").let {
                if (it.isNotBlank()) {
                    description = when {
                        description.isNullOrBlank() -> altName + it
                        else -> description + "\n\n$altName" + it
                    }
                }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val response = client.newCall(GET(baseUrl + anime.url)).execute()
        var document = response.asJsoup()
        document = resolveSearchAnime(document)
        val id = document.selectFirst("div[data-id]")?.attr("data-id")
            ?: throw IllegalStateException("ID not found")

        val vrf = utils.vrfEncrypt(id)

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + anime.url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return GET("$baseUrl/ajax/episode/list/$id?vrf=$vrf", listHeaders)
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer")
            ?: throw IllegalStateException("Referrer header not found in request")
        val animeUrl = referer.toHttpUrl().encodedPath
        val document = response.parseAs<ResultResponse>().toDocument()

        val episodeElements = document.select(episodeListSelector())
        return episodeElements.parallelMapBlocking { episodeFromElement(it, animeUrl) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, animeUrl: String): SEpisode {
        val title = element.parent()?.attr("title") ?: ""

        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toInt() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toInt() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.find(title) != null) "SoftSub" else ""

        val extraInfo = if (element.hasClass("filler") && markFiller) {
            " • Filler Episode"
        } else {
            ""
        }
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"

        return SEpisode.create().apply {
            this.name = "Episode $epNum" +
                if (name.isNotEmpty() && name != namePrefix) ": $name" else ""
            this.url = "$ids&epurl=$animeUrl/ep-$epNum"
            episode_number = epNum.toFloat()
            date_upload = RELEASE_REGEX.find(title)?.let {
                parseDate(it.groupValues[1])
            } ?: 0L
            scanlator = arrayOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(", ") + extraInfo
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val vrf = utils.vrfEncrypt(ids)
        val url = "/ajax/server/list/$ids?vrf=$vrf"
        val epurl = episode.url.substringAfter("epurl=")

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epurl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return GET("$baseUrl$url", listHeaders)
    }

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
    )

    override fun videoListParse(response: Response): List<Video> {
        val referer = response.request.header("Referer")
            ?: throw IllegalStateException("Referrer header not found in request")
        val epurl = referer.toHttpUrl().encodedPath
        val document = response.parseAs<ResultResponse>().toDocument()
        val hosterSelection = getHosters()
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)!!

        return document.select("div.servers > div").parallelFlatMapBlocking { elem ->
            val type = elem.attr("data-type").replaceFirstChar { it.uppercase() }
            elem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                val serverName = serverElement.text().lowercase()
                if (hosterSelection.contains(serverName, true).not()) return@mapNotNull null
                if (typeSelection.contains(type, true).not()) return@mapNotNull null

                VideoData(type, serverId, serverName)
            }
        }
            .parallelFlatMapBlocking { extractVideo(it, epurl) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
//    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
//    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
//    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
//    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    private fun extractVideo(server: VideoData, epUrl: String): List<Video> {
        /*
         * Calling server script and return the encrypted JSON response here, will work on decrypting it later.
         */
//        val vrf = utils.vrfEncrypt(server.serverId)
//
//        val listHeaders = headers.newBuilder().apply {
//            add("Accept", "application/json, text/javascript, */*; q=0.01")
//            add("Referer", baseUrl + epUrl)
//            add("X-Requested-With", "XMLHttpRequest")
//        }.build()
//
//        val response = client.newCall(
//            GET("$baseUrl/ajax/server/${server.serverId}?vrf=$vrf", listHeaders),
//        ).execute()
//        if (response.code != 200) return emptyList()

        return runCatching {
//            val parsed = response.parseAs<ServerResponse>()
//            val embedLink = utils.vrfDecrypt(parsed.result.url)
            when (server.serverName) {
                "f4 - noads" -> videosFromUrl(server.serverId, "F4 - Noads", server.type)

                //                "vidstream" -> vidsrcExtractor.videosFromUrl(embedLink, "Vidstream", server.type)
//                "megaf" -> vidsrcExtractor.videosFromUrl(embedLink, "MegaF", server.type)
//                "moonf" -> filemoonExtractor.videosFromUrl(embedLink, "MoonF - ${server.type} - ")
//                "streamtape" -> streamtapeExtractor.videoFromUrl(embedLink, "StreamTape - ${server.type}")?.let(::listOf) ?: emptyList()
//                "mp4u" -> mp4uploadExtractor.videosFromUrl(embedLink, headers, suffix = " - ${server.type}")
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    // one-piece-episode-1128-ep-id-1128-sv-id-41
    private val episodeUrlRegex = Regex("""([\w-]+)-episode-(\d+)""")

    // sources:[{"file":"https://hlsx3cdn.echovideo.to/one-piece/1128/master.m3u8","quality":"default"},{"file":"https://hlsx3cdn.echovideo.to/one-piece/1128/master.m3u8","quality":"default"}]
    // sources:[{"file":"https://hlsx112cdn.echovideo.to/embed-2/3326505230727/11262573528/1/1/0f37b1b5b55ea4d35fab74f86f8768aba2cc09a5/master.m3u8","quality":"default"}],tracks:[{"file":"https://s.megastatics.com/subtitle/c1c05d1df7016a987f7f0277170a602f/c1c05d1df7016a987f7f0277170a602f.vtt","label":"English","kind":"captions","default":true}]
    private val playlistRegex = Regex("""sources:\[\{"file":"([^"]+)"(.*tracks:.*)?""")
    private val subtitlesRegex = Regex("""\{"file":"([^"]+)","label":"([^"]+)","kind":"captions"""")

    private fun videosFromUrl(
        embedLink: String,
        hosterName: String,
        type: String = "",
    ): List<Video> {
        val matchResult = episodeUrlRegex.find(embedLink)
            ?: throw IllegalStateException("Episode ID not found in embed link: $embedLink")
        val episodeId = matchResult.groupValues[0]
        val animeName = matchResult.groupValues[1]
        val episodeNumber = matchResult.groupValues[2]

        // Should construct this link:
        // $baseUrl/ajax/player/?ep=one-piece-episode-1280&dub=false&sn=one-piece&epn=1130&g=true&autostart=true
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("ajax")
            addPathSegment("player")
            addQueryParameter("ep", episodeId)
            addQueryParameter("dub", (type.lowercase() == "dub").toString())
            addQueryParameter("sn", animeName) // Might need to add "-dub" if type is "Dub"
            if (type == "S-sub") {
                addQueryParameter("svr", "z")
            }
            addQueryParameter("epn", episodeNumber)
            addQueryParameter("g", "true")
        }

        val response = client.newCall(GET(url.build(), refererHeaders)).execute()
        if (response.code != 200) {
            throw IllegalStateException("Failed to fetch video links: ${response.message}")
        }

        val data = response.body.string()

        val sources = playlistRegex.find(data)?.groupValues
            ?: throw IllegalStateException("Playlist URL not found in response: $data")
        val playlistUrl = sources[1]

        val subtitles = sources[2].let { tracks ->
            subtitlesRegex.findAll(tracks)
                .map { it.groupValues.drop(1) }
                .map { Pair(it[0], it[1]) }
        }

        return playlistUtils.extractFromHls(
            playlistUrl = playlistUrl,
            referer = "$baseUrl/",
            videoNameGen = { q -> hosterName + (if (type.isBlank()) "" else " - $type") + " - $q" },
            subtitleList = subtitles.map { (file, label) -> Track(url = file, lang = label) }.toList(),
        )
    }

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean = any { it.equals(s, ignoreCase) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    @Synchronized
    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }
        .getOrNull() ?: 0L

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "Ongoing Anime" -> SAnime.ONGOING
        "Finished Airing" -> SAnime.COMPLETED
        "Completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun resolveSearchAnime(document: Document): Document {
        if (document.location().startsWith("$baseUrl/filter?keyword=")) { // redirected to search
            val element = document.selectFirst(searchAnimeSelector())
            val foundAnimePath = element?.selectFirst("a[href]")?.attr("href")
                ?: throw IllegalStateException("Search element not found (resolveSearch)")
            return client.newCall(GET(baseUrl + foundAnimePath)).execute().asJsoup()
        }
        return document
    }

    private fun getHosters(): Set<String> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        var invalidRecord = false
        hosterSelection.forEach { str ->
            val index = HOSTERS_NAMES.indexOf(str)
            if (index == -1) {
                invalidRecord = true
            }
        }

        // found invalid record, reset to defaults
        if (invalidRecord) {
            preferences.edit().putStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
            return PREF_HOSTER_DEFAULT.toSet()
        }

        return hosterSelection.toSet()
    }

    companion object {
        private const val DOMAIN = "ww.aniwave.se"

        private val SOFTSUB_REGEX by lazy { Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE) }
        private val RELEASE_REGEX by lazy { Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""") }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://$DOMAIN"

        private const val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidstream"

        private const val PREF_MARK_FILLERS_KEY = "mark_fillers"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf(
            "F4 - Noads",
            "Vidstream",
            "Megaf",
            "MoonF",
            "StreamTape",
            "MP4u",
        )
        private val HOSTERS_NAMES = arrayOf(
            "f4 - noads",
            "vidstream",
            "megaf",
            "moonf",
            "streamtape",
            "mp4u",
        )
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("Sub", "S-sub", "Dub")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES.toSet()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // validate hosters preferences and if invalid reset
        try {
            getHosters()
        } catch (e: Exception) {
            Toast.makeText(screen.context, e.toString(), Toast.LENGTH_LONG).show()
        }

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = arrayOf(DOMAIN)
            entryValues = arrayOf(PREF_DOMAIN_DEFAULT)
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Restart App to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Type"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(PREF_MARK_FILLERS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "Restart App to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_TOGGLE_KEY
            title = "Enable/Disable Types"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_TYPES_TOGGLE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_DOMAIN_KEY
            title = "Custom domain"
            setDefaultValue(null)
            val currentValue = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)
            summary = if (currentValue.isNullOrBlank()) {
                "Custom domain of your choosing"
            } else {
                "Domain: \"$currentValue\". \nLeave blank to disable. Overrides any domain preferences!"
            }

            setOnPreferenceChangeListener { _, newValue ->
                val newDomain = newValue.toString().trim().removeSuffix("/")
                if (newDomain.isBlank() || URLUtil.isValidUrl(newDomain)) {
                    summary = "Restart to apply changes"
                    Toast.makeText(screen.context, "Restart App to apply changes", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, newDomain).apply()
                    true
                } else {
                    Toast.makeText(screen.context, "Invalid url. Url example: $PREF_DOMAIN_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.also(screen::addPreference)
    }
}
