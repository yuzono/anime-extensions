package eu.kanade.tachiyomi.animeextension.en.kayoanime

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

class Kayoanime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Kayoanime"

    override val id = 203922289858257167

    override val baseUrl = "https://kayoanime.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/category/ongoing-anime/")
    } else {
        GET("$baseUrl/category/ongoing-anime/page/$page/")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }

        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeSelector(): String = "ul.posts-items > li.post-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
        title = element.selectFirst("h2.post-title")!!.text().substringBefore(" Episode")
    }

    override fun popularAnimeNextPageSelector(): String = "div.pages-nav span.last-page a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div.tab-content-recent li.widget-single-post-item"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.post-title")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
        title = element.selectFirst("a.post-title")!!.text().substringBefore(" Episode")
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> {
                val cleanQuery = query.replace(" ", "+")
                if (page == 1) {
                    GET("$baseUrl/?s=$cleanQuery")
                } else {
                    GET("$baseUrl/page/$page/?s=$cleanQuery")
                }
            }

            genreFilter.state != 0 -> {
                val path = genreFilter.toUriPart().trimEnd('/')
                if (page == 1) {
                    GET("$baseUrl$path/")
                } else {
                    GET("$baseUrl$path/page/$page/")
                }
            }

            subPageFilter.state != 0 -> {
                val path = subPageFilter.toUriPart().trimEnd('/')
                if (page == 1) {
                    GET("$baseUrl$path/")
                } else {
                    GET("$baseUrl$path/page/$page/")
                }
            }

            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
        GenreFilter(),
    )

    private class SubPageFilter :
        UriPartFilter(
            "Sub-page",
            arrayOf(
                Pair("<select>", ""),
                Pair("Anime Series", "/category/anime-series/"),
                Pair("Anime Movie", "/category/anime-movie/"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genres",
            arrayOf(
                Pair("<select>", ""),
                Pair("Action", "/category/action/"),
                Pair("Adventure", "/category/adventure/"),
                Pair("Comedy", "/category/comedy/"),
                Pair("Demons", "/category/demons/"),
                Pair("Drama", "/category/drama/"),
                Pair("Fantasy", "/category/fantasy/"),
                Pair("Horror", "/category/horror/"),
                Pair("Magic", "/category/magic/"),
                Pair("Martial Arts", "/category/martial-arts/"),
                Pair("Mecha", "/category/mecha/"),
                Pair("Military", "/category/military/"),
                Pair("Music", "/category/music/"),
                Pair("Mystery", "/category/mystery/"),
                Pair("Psychological", "/category/psychological/"),
                Pair("Romance", "/category/romance/"),
                Pair("School", "/category/school/"),
                Pair("Sci-Fi", "/category/sci-fi/"),
                Pair("Shounen", "/category/shounen/"),
                Pair("Slice of Life", "/category/slice-of-life/"),
                Pair("Space", "/category/space/"),
                Pair("Sports", "/category/sports/"),
                Pair("Super Power", "/category/super-power/"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val moreInfo = document.select("div.toggle-content > ul > li").joinToString("\n") { it.text() }
        val realDesc = document.selectFirst("div.entry-content:has(div.toggle + div.clearfix + div.toggle:has(h3:contains(Information)))")?.let {
            it.selectFirst("div.toggle > div.toggle-content")!!.text() + "\n\n"
        } ?: ""

        return SAnime.create().apply {
            status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let {
                parseStatus(it.text())
            } ?: SAnime.UNKNOWN
            description = realDesc + "\n\n$moreInfo"
            genre = document.selectFirst("div.toggle-content > ul > li:contains(Genres)")?.let {
                it.text().substringAfter("Genres: ")
            }
            author = document.selectFirst("div.toggle-content > ul > li:contains(Studios)")?.let {
                it.text().substringAfter("Studios: ")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        fun traverseFolder(url: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == MAX_RECURSION_DEPTH) return

            try {
                val folderId = url.substringAfter("/folders/")
                val driveHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Connection", "keep-alive")
                    .add("Cookie", getCookie("https://drive.google.com"))
                    .add("Host", "drive.google.com")
                    .build()

                val driveDocument = client.newCall(
                    GET(url, headers = driveHeaders),
                ).execute().asJsoup()
                if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

                val key = driveDocument.select("script")
                    .firstOrNull { script -> KEY_REGEX.find(script.data()) != null }
                    ?.data()?.let { KEY_REGEX.find(it)?.groupValues?.get(1) }
                    ?: return

                val driveVersion = driveDocument.select("script")
                    .firstOrNull { script -> VERSION_REGEX.find(script.data()) != null }
                    ?.data()?.let { VERSION_REGEX.find(it)?.groupValues?.get(1) }
                    ?: return

                val sapisid = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                    it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
                }?.value ?: return

                var pageToken: String? = ""
                while (pageToken != null) {
                    val requestUrl = "/drive/v2internal/files?openDrive=false&reason=102&syncType=0&errorRecovery=false&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2ChasVisitorPermissions%2CcontainsUnsubscribedChildren%2CmodifiedByMeDate%2ClastViewedByMeDate%2CalternateLink%2CfileSize%2Cowners(kind%2CpermissionId%2CemailAddressFromAccount%2Cdomain%2Cid)%2ClastModifyingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CcustomerId%2CancestorHasAugmentedPermissions%2ChasThumbnail%2CthumbnailVersion%2Ctitle%2Cid%2CresourceKey%2CabuseIsAppealable%2CabuseNoticeReason%2Cshared%2CaccessRequestsCount%2CsharedWithMeDate%2CuserPermission(role)%2CexplicitlyTrashed%2CmimeType%2CquotaBytesUsed%2Ccopyable%2Csubscribed%2CfolderColor%2ChasChildFolders%2CfileExtension%2CprimarySyncParentId%2CsharingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CflaggedForAbuse%2CfolderFeatures%2Cspaces%2CsourceAppId%2Crecency%2CrecencyReason%2Cversion%2CactionItems%2CteamDriveId%2ChasAugmentedPermissions%2CcreatedDate%2CprimaryDomainName%2CorganizationDisplayName%2CpassivelySubscribed%2CtrashingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CtrashedDate%2Cparents(id)%2Ccapabilities(canMoveItemIntoTeamDrive%2CcanUntrash%2CcanMoveItemWithinTeamDrive%2CcanMoveItemOutOfTeamDrive%2CcanDeleteChildren%2CcanTrashChildren%2CcanRequestApproval%2CcanReadCategoryMetadata%2CcanEditCategoryMetadata%2CcanAddMyDriveParent%2CcanRemoveMyDriveParent%2CcanShareChildFiles%2CcanShareChildFolders%2CcanRead%2CcanMoveItemWithinDrive%2CcanMoveChildrenWithinDrive%2CcanAddFolderFromAnotherDrive%2CcanChangeSecurityUpdateEnabled%2CcanBlockOwner%2CcanReportSpamOrAbuse%2CcanCopy%2CcanDownload%2CcanEdit%2CcanAddChildren%2CcanDelete%2CcanRemoveChildren%2CcanShare%2CcanTrash%2CcanRename%2CcanReadTeamDrive%2CcanMoveTeamDriveItem)%2CcontentRestrictions(readOnly)%2CapprovalMetadata(approvalVersion%2CapprovalSummaries%2ChasIncomingApproval)%2CshortcutDetails(targetId%2CtargetMimeType%2CtargetLookupStatus%2CtargetFile%2CcanRequestAccessToTarget)%2CspamMetadata(markedAsSpamDate%2CinSpamView)%2Clabels(starred%2Ctrashed%2Crestricted%2Cviewed))%2CincompleteSearch&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$pageToken&maxResults=100&supportsTeamDrives=true&includeItemsFromAllDrives=true&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"
                    val body = """--$BOUNDARY
                        |content-type: application/http
                        |content-transfer-encoding: binary
                        |
                        |GET $requestUrl
                        |X-Goog-Drive-Client-Version: $driveVersion
                        |authorization: ${generateSapisidhashHeader(sapisid)}
                        |x-goog-authuser: 0
                        |
                        |--$BOUNDARY--""".trimMargin("|").toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

                    val postUrl = buildString {
                        append("https://clients6.google.com/batch/drive/v2internal")
                        append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
                        append("&key=$key")
                    }

                    val postHeaders = headers.newBuilder()
                        .add("Content-Type", "text/plain; charset=UTF-8")
                        .add("Origin", "https://drive.google.com")
                        .add("Cookie", getCookie("https://drive.google.com"))
                        .build()

                    val postResponse = client.newCall(
                        POST(postUrl, body = body, headers = postHeaders),
                    ).execute()

                    val parsed = try {
                        postResponse.parseAs<GDrivePostResponse> {
                            JSON_REGEX.find(it)?.groupValues?.get(1) ?: return@parseAs ""
                        }
                    } catch (_: Exception) {
                        return
                    }

                    if (parsed.items == null) return
                    parsed.items.forEachIndexed { index, it ->
                        if (it.mimeType.startsWith("video")) {
                            val size = it.fileSize?.toLongOrNull()?.let { sz -> formatBytes(sz) }
                            val pathName = path.trimInfo()

                            episodeList.add(
                                SEpisode.create().apply {
                                    name = if (preferences.trimEpisodeName) it.title.trimInfo() else it.title
                                    this.url = "https://drive.google.com/uc?id=${it.id}"
                                    episode_number = ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)?.toFloatOrNull() ?: index.toFloat()
                                    date_upload = -1L
                                    scanlator = "$size • /$pathName"
                                },
                            )
                        }
                        if (it.mimeType.endsWith(".folder")) {
                            traverseFolder(
                                "https://drive.google.com/drive/folders/${it.id}",
                                "$path/${it.title}",
                                recursionDepth + 1,
                            )
                        }
                    }

                    pageToken = parsed.nextPageToken
                }
            } catch (_: Exception) {
                // Skip inaccessible folders gracefully
            }
        }

        document.select("div.toggle:has(> div.toggle-content > a[href*=drive.google.com])").distinctBy { t ->
            getVideoPathsFromElement(t)
        }.forEach { season ->
            season.select("a[href*=drive.google.com]").distinctBy { it.text() }.forEach {
                try {
                    val url = it.attr("href").substringBeforeLast("?usp=shar")
                    traverseFolder(url, getVideoPathsFromElement(season) + " " + it.text())
                } catch (_: Exception) { }
            }
        }

        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val indexExtractor = DriveIndexExtractor(client, headers)
        document.select("div.toggle:has(> div.toggle-content > a[href*=tinyurl.com])").forEach { season ->
            season.select("a[href*=tinyurl.com]").forEach {
                try {
                    val url = it.attr("href")
                    val redirected = noRedirectClient.newCall(GET(url)).execute()
                    val location = redirected.headers["location"]
                    redirected.close()
                    location?.let { loc ->
                        val host = loc.toHttpUrl().host
                        if (host.contains("workers.dev")) {
                            episodeList.addAll(
                                indexExtractor.getEpisodesFromIndex(
                                    loc,
                                    getVideoPathsFromElement(season) + " " + it.text(),
                                    preferences.trimEpisodeName,
                                ),
                            )
                        }

                        if (host.contains("slogoanime")) {
                            val redirectDoc = client.newCall(GET(loc)).execute().asJsoup()
                            redirectDoc.select("a[href*=drive.google.com]").distinctBy { link -> link.text() }.forEach { link ->
                                val gdriveUrl = link.attr("href").substringBeforeLast("?usp=shar")
                                traverseFolder(gdriveUrl, getVideoPathsFromElement(season) + " " + link.text())
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        return episodeList.reversed()
    }

    private fun getVideoPathsFromElement(element: Element): String = element.selectFirst("h3")!!.text()
        .substringBefore("480p").substringBefore("720p").substringBefore("1080p")
        .replace("Download The Anime From Drive", "", true)

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val httpUrl = episode.url.toHttpUrl()
        val host = httpUrl.host
        return if (host == "drive.google.com") {
            val id = httpUrl.queryParameter("id")!!
            GoogleDriveExtractor(client, headers).videosFromUrl(id)
        } else if (host.contains("workers.dev")) {
            getIndexVideoUrl(episode.url)
        } else {
            throw Exception("Unsupported url: ${episode.url}")
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun generateSapisidhashHeader(SAPISID: String, origin: String = "https://drive.google.com"): String {
        val timeNow = System.currentTimeMillis() / 1000
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    @Serializable
    data class GDrivePostResponse(
        val nextPageToken: String? = null,
        val items: List<ResponseItem>? = null,
    ) {
        @Serializable
        data class ResponseItem(
            val id: String,
            val title: String,
            val mimeType: String,
            val fileSize: String? = null,
        )
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun getIndexVideoUrl(url: String): List<Video> {
        val doc = client.newCall(
            GET("$url?a=view"),
        ).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(videodomain)")?.data()
            ?: doc.selectFirst("script:containsData(downloaddomain)")?.data()
            ?: return listOf(Video(url, "Video", url))

        if (script.contains("\"second_domain_for_dl\":false")) {
            return listOf(Video(url, "Video", url))
        }

        val domainUrl = if (script.contains("videodomain", true)) {
            script
                .substringAfter("\"videodomain\":\"")
                .substringBefore("\"")
        } else {
            script
                .substringAfter("\"downloaddomain\":\"")
                .substringBefore("\"")
        }

        val videoUrl = if (domainUrl.isBlank()) {
            url
        } else {
            domainUrl + url.toHttpUrl().encodedPath
        }

        return listOf(Video(videoUrl, "Video", videoUrl))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "Status: Currently Airing" -> SAnime.ONGOING
        "Status: Finished Airing" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    companion object {
        private val ITEM_NUMBER_REGEX = """ - (?:S\d+E)?(\d+)""".toRegex()
        private val KEY_REGEX = """"(\w{39})"""".toRegex()
        private val VERSION_REGEX = """"([^"]+web-frontend[^"]+)"""".toRegex()
        private val JSON_REGEX = """(?:)\s*(\{(.+)\})\s*(?:)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="

        private const val MAX_RECURSION_DEPTH = 2

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode"
        private const val TRIM_EPISODE_NAME_DEFAULT = true
    }

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_NAME_KEY
            title = "Trim info from episode name"
            setDefaultValue(TRIM_EPISODE_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
