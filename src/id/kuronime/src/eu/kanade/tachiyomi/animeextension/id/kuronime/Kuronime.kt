package eu.kanade.tachiyomi.animeextension.id.kuronime

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class Kuronime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val baseUrl: String = "https://kuronime.sbs"
    override val lang: String = "id"
    override val name: String = "Kuronime"
    override val supportsLatest: Boolean = true

    private val preferences by getPreferencesLazy()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val infodetail = document.select("div.infodetail")
        val status = parseStatus(infodetail.select("ul > li:nth-child(3)").text().replace("Status: ", ""))
        anime.title = document.selectFirst("h1.entry-title")?.text() ?: infodetail.select("ul > li:nth-child(1)").text().replace("Judul: ", "")
        anime.genre = infodetail.select("ul > li:nth-child(2) a").joinToString { it.text() }
        anime.status = status
        anime.artist = infodetail.select("ul > li:nth-child(4)").text().replace("Studio: ", "")
        anime.author = "UNKNOWN"
        anime.description = "Synopsis: \n" + document.select("div.main-info div.r span.const p").text()
        return anime
    }

    private fun parseStatus(statusString: String): Int = when (statusString.lowercase(Locale.US)) {
        "ongoing" -> SAnime.ONGOING
        "completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epsNum = getNumberFromEpsString(element.select("span.lchx").text())
        episode.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        episode.episode_number = when {
            epsNum.isNotEmpty() -> epsNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        episode.name = element.select("span.lchx").text()

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    override fun episodeListSelector(): String = "div.bixbox.bxcl > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("div > a")!!.attr("href"))

        val thumbnailElement = element.selectFirst("div > a > div.limit > img")!!
        val thumbnail = thumbnailElement.attr("src")
        anime.thumbnail_url = if (thumbnail.startsWith("https:")) {
            thumbnail
        } else {
            if (thumbnailElement.hasAttr("data-src")) thumbnailElement.attr("data-src") else ""
        }
        anime.title = element.select("div > a > div.tt > h2").text()
        return anime
    }
    override fun latestUpdatesNextPageSelector(): String = "a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/?status=ongoing&order=update")

    override fun latestUpdatesSelector(): String = "div.listupd > article"

    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page/?order=popular")

    override fun popularAnimeSelector(): String = "div.listupd > article"

    override fun searchAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // filter and stuff in v2
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector(): String = "div.listupd > article"

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(videoListRequest(episode)).awaitSuccess().useAsJsoup()
        val id = document.select("script").firstNotNullOfOrNull { ID_REGEX.find(it.data())?.groupValues?.get(1) }
            ?: return emptyList()
        val sources = client.newCall(POST("$API_URL/sources", headers, SourcesRequest(id).toJsonRequestBody()))
            .awaitSuccess()
            .parseAs<SourcesResponse>()
        val mirror = sources.mirror ?: return emptyList()
        val encrypted = String(Base64.decode(mirror, Base64.DEFAULT)).parseAs<EncryptedMirror>()
        val embeds = CryptoAES.decryptWithSalt(encrypted.ct, encrypted.s, MIRROR_KEY).parseAs<Mirror>().embed

        return embeds.flatMap { (quality, hosts) ->
            hosts.mapNotNull { (host, url) -> url?.let { Triple(host, it, quality.removePrefix("v")) } }
        }.parallelCatchingFlatMap { (host, url, quality) ->
            when (host) {
                "doodstream" -> listOfNotNull(DoodExtractor(client).videoFromUrl(url, quality))
                "mp4upload" -> Mp4uploadExtractor(client).videosFromUrl(url, headers, suffix = " - $quality")
                "pixeldrain" -> PixelDrainExtractor().videosFromUrl(url.substringBefore("?"), "$quality - ")
                "filelions" -> VidHideExtractor(client, headers).videosFromUrl(url) { resolution -> "FileLions - $resolution" }
                else -> emptyList()
            }
        }
    }

    @Serializable
    private class SourcesRequest(val id: String)

    @Serializable
    private class SourcesResponse(val mirror: String? = null)

    @Serializable
    private class EncryptedMirror(val ct: String, val s: String)

    @Serializable
    private class Mirror(val embed: Map<String, Map<String, String?>> = emptyMap())

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.videoTitle.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "HD", "SD")
            entryValues = arrayOf("1080", "720", "480", "360", "HD", "SD")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val API_URL = "https://animeku.org/api/v9"
        private const val MIRROR_KEY = "3&!Z0M,VIZ;dZW=="
        private val ID_REGEX = Regex("""var _0x\w+ = "([A-Za-z0-9+/=]{40,})"""")
    }
}
