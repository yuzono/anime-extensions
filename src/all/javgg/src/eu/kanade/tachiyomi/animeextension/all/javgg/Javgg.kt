package eu.kanade.tachiyomi.animeextension.all.javgg

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Source
abstract class Javgg :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    private val preferences by getPreferencesLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidHide"

        private val SERVER_LIST = arrayOf(
            "VidHide",
            "Vidara",
            "StreamWish",
            "StreamHG",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "TurboPlay",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            status = SAnime.COMPLETED
            description = document.selectFirst("#cover")?.text()
        }

        document.select(".data .boxye2").forEach { element ->
            val category = element.select("[id*=owye2]").text().trim()
            val tags = element.select(".sgeneros3 a").joinToString { it.text() }
            when {
                category.contains("Genres:") -> animeDetails.genre = tags
                category.contains("Cast:") -> animeDetails.artist = tags
                category.contains("Maker:") -> animeDetails.author = tags
            }
        }

        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article[id*=post-]")
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".data h3")!!.text()
                thumbnail_url = element.selectFirst(".poster")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-post/page/$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = when {
        query.isNotBlank() -> GET("$baseUrl/jav/page/$page?s=$query", headers)
        else -> popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".result-item article")
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".details .title")!!.text()
                thumbnail_url = element.selectFirst(".image")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun String.stripKeywordForRelatedAnimes(): List<String> {
        val regexWhitespace = Regex("\\s+")
        val regexSpecialCharacters =
            Regex("([-!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        val regexNumberOnly = Regex("^\\d+$")

        return replace(regexSpecialCharacters, " ")
            .split(regexWhitespace)
            .map {
                // remove number only
                it.replace(regexNumberOnly, "")
                    .lowercase()
            }
            // exclude single character
            .filter { it.length > 1 }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.select(".dooplay_player_option").any()) {
            listOf(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1F
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            emptyList()
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get(baseUrl + episode.url, headers).useAsJsoup()

        val hosters = mutableListOf<Hoster>()

        document.select(".dooplay_player_option").forEach { option ->
            val numOpt = option.attr("data-nume")
            if (numOpt.isBlank() || numOpt == "trailer") return@forEach

            val sourceBox = document.selectFirst("#source-player-$numOpt") ?: return@forEach
            val iframe = sourceBox.selectFirst("iframe") ?: return@forEach
            val rawEmbedUrl = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
            if (rawEmbedUrl.isBlank()) return@forEach
            val embedUrl = when {
                rawEmbedUrl.startsWith("//") -> "https:$rawEmbedUrl"
                rawEmbedUrl.startsWith("/") -> baseUrl.trimEnd('/') + rawEmbedUrl
                !rawEmbedUrl.startsWith("http") -> "https://$rawEmbedUrl"
                else -> rawEmbedUrl
            }

            val part = option.selectFirst(".title")?.text().orEmpty()
            val serverAttr = option.selectFirst(".server")?.attr("data-text")?.trim().orEmpty()
            val serverText = option.selectFirst(".server")?.text().orEmpty()
            val rawServer = serverAttr.ifEmpty { serverText }

            if (rawServer.equals("f4scom", true) || embedUrl.contains("f4jav") || embedUrl.contains("f4stream")) {
                return@forEach
            }

            val serverName = when {
                embedUrl.contains("vidara") || rawServer.contains("vidara", true) -> "Vidara"
                embedUrl.contains("earnvid") || embedUrl.contains("vidhide") || rawServer.contains("vidhide", true) -> "VidHide"
                embedUrl.contains("streamhg") || rawServer.contains("streamhg", true) -> "StreamHG"
                embedUrl.contains("streamwish") || embedUrl.contains("wishembed") || embedUrl.contains("strwish") ||
                    embedUrl.contains("wishfast") || embedUrl.contains("dwish") ||
                    rawServer.contains("streamwish", true) || rawServer.contains("wish", true) -> "StreamWish"
                embedUrl.contains("filelions") || embedUrl.contains("fviplions") ||
                    rawServer.contains("filelions", true) || rawServer.contains("lion", true) -> "FileLions"
                embedUrl.contains("voe") || rawServer.contains("voe", true) -> "Voe"
                embedUrl.contains("ok.ru") || rawServer.contains("okru", true) -> "Okru"
                embedUrl.contains("yourupload") || rawServer.contains("yourupload", true) -> "YourUpload"
                embedUrl.contains("turboplay") || rawServer.contains("turboplay", true) -> "TurboPlay"
                rawServer.isNotBlank() -> rawServer.replaceFirstChar { it.uppercase() }
                else -> "Server"
            }

            val hosterName = if (part.isNotBlank() && !part.equals("Server", ignoreCase = true)) {
                "$serverName - $part"
            } else {
                serverName
            }

            hosters.add(
                Hoster(
                    hosterName = hosterName,
                    hosterUrl = embedUrl,
                ),
            )
        }

        return hosters.sortHosters()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl.trim()
        val url = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> baseUrl.trimEnd('/') + rawUrl
            !rawUrl.startsWith("http") -> "https://$rawUrl"
            else -> rawUrl
        }
        val nameLower = hoster.hosterName.lowercase()
        val part = if (nameLower.contains("part")) {
            hoster.hosterName.substringAfter("-").trim()
        } else {
            ""
        }
        val prefix = if (part.isNotBlank()) "$part - " else ""

        return when {
            url.contains("vidara.so") || url.contains("gasobni.com") || nameLower.contains("vidara") -> {
                extractVidara(url, prefix)
            }

            url.contains("earnvid") || url.contains("vidhide") || url.contains("streamhide") ||
                url.contains("guccihide") || url.contains("streamvid") || nameLower.contains("vidhide") -> {
                val extHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
                VidHideExtractor(client, extHeaders).videosFromUrl(url) { quality ->
                    "${prefix}VidHide - $quality"
                }
            }

            url.contains("streamhg") || nameLower.contains("streamhg") -> {
                val extHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
                VidHideExtractor(client, extHeaders).videosFromUrl(url) { quality ->
                    "${prefix}StreamHG - $quality"
                }
            }

            url.contains("streamwish") || url.contains("wishembed") || url.contains("strwish") ||
                url.contains("wishfast") || url.contains("dwish") || nameLower.contains("streamwish") -> {
                val host = url.toHttpUrlOrNull()?.host ?: "streamwish.to"
                val docHeaders = headers.newBuilder()
                    .set("Origin", "https://$host")
                    .set("Referer", "https://$host/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url) { quality ->
                    "${prefix}StreamWish - $quality"
                }
            }

            url.contains("filelions") || url.contains("fviplions") || nameLower.contains("filelions") -> {
                StreamWishExtractor(client, headers).videosFromUrl(url) { quality ->
                    "${prefix}FileLions - $quality"
                }
            }

            url.contains("voe") || nameLower.contains("voe") -> {
                VoeExtractor(client, headers).videosFromUrl(url)
            }

            url.contains("ok.ru") || url.contains("okru") || nameLower.contains("okru") -> {
                OkruExtractor(client).videosFromUrl(url)
            }

            url.contains("yourupload") || nameLower.contains("yourupload") -> {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            }

            url.contains("turboplay") || nameLower.contains("turboplay") -> {
                extractTurboPlay(url)
            }

            else -> emptyList()
        }.sortVideos()
    }

    private suspend fun extractVidara(url: String, prefix: String): List<Video> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val path = httpUrl.encodedPath
        val filecode = when {
            "/e/" in path -> path.substringAfter("/e/").substringBefore("/")
            "/v/" in path -> path.substringAfter("/v/").substringBefore("/")
            else -> httpUrl.pathSegments.lastOrNull { it.isNotBlank() }
        } ?: return emptyList()
        if (filecode.isBlank()) return emptyList()

        val embedHost = httpUrl.host
        val body = """{"filecode":"$filecode","device":"web"}"""
            .toRequestBody("application/json".toMediaType())

        val hostsToTry = listOfNotNull(embedHost, "vidara.so", "gasobni.com").distinct()
        var streamUrl: String? = null
        var subTracks: List<Track> = emptyList()
        var successHost: String = embedHost

        for (host in hostsToTry) {
            val apiHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("Origin", "https://$host")
                .build()

            try {
                val data = client.post("https://$host/api/stream", apiHeaders, body)
                    .parseAs<VidaraResponse>()

                if (!data.streamingUrl.isNullOrBlank()) {
                    streamUrl = data.streamingUrl
                    successHost = host
                    subTracks = data.subtitles?.mapNotNull { sub ->
                        if (sub.filePath.isNotBlank()) {
                            Track(sub.filePath, sub.language ?: "Subtitle")
                        } else {
                            null
                        }
                    }.orEmpty()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
        }

        if (streamUrl.isNullOrBlank()) return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = streamUrl,
            referer = "https://$successHost/",
            videoNameGen = { quality -> "${prefix}Vidara - $quality" },
            subtitleList = subTracks,
        )
    }

    private suspend fun extractTurboPlay(url: String): List<Video> {
        val turboDocument = client.get(url, headers).useAsJsoup()
        val masterUrl = turboDocument.select("#video_player").attr("data-hash")
        if (masterUrl.isBlank()) return emptyList()
        val host = turboDocument.location().toHttpUrl().host
        val customHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", "https://$host")
            .set("Referer", "https://$host/")
            .build()
        return listOf(
            Video(
                videoUrl = masterUrl,
                videoTitle = "TurboPlay",
                headers = customHeaders,
            ),
        )
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val rawServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val server = if (rawServer.equals("StreamHideVid", ignoreCase = true)) "VidHide" else rawServer
        return sortedWith(
            compareBy { it.hosterName.contains(server, true) },
        ).reversed()
    }

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        val imageLinkRegex = """https?://\S+\.(jpg|png)""".toRegex()

        for (link in this.select("[href], [src]")) {
            val href = link.attr("href")
            val src = link.attr("src")
            if (imageLinkRegex.matches(href)) {
                return href
            }
            if (imageLinkRegex.matches(src)) {
                return src
            }
        }

        val textMatches = imageLinkRegex.find(this.text())
        val htmlMatches = imageLinkRegex.find(this.outerHtml())
        return textMatches?.value ?: htmlMatches?.value
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val rawServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val server = if (rawServer.equals("StreamHideVid", ignoreCase = true)) "VidHide" else rawServer
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (preferences.getString(PREF_SERVER_KEY, null) == "StreamHideVid") {
            preferences.edit().putString(PREF_SERVER_KEY, "VidHide").apply()
        }

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    @Serializable
    private data class VidaraResponse(
        @SerialName("streaming_url")
        val streamingUrl: String? = null,
        val subtitles: List<VidaraSubtitle>? = null,
    )

    @Serializable
    private data class VidaraSubtitle(
        @SerialName("file_path")
        val filePath: String = "",
        val language: String? = null,
    )
}
