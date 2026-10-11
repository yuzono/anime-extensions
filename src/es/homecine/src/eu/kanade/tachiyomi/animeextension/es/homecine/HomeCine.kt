
package eu.kanade.tachiyomi.animeextension.es.homecine

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class HomeCine :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HomeCine"

    override val baseUrl = "https://www3.homecine.to"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private val NUMBER_REGEX = Regex("""\d+""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "YourUpload"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "StreamWish",
            "Mp4Upload",
            "Fastream",
            "Upstream",
            "Filemoon",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET(listUrl("series", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animeList = document.select(".movies-list .ml-item").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a.ml-mask")!!.absUrl("href"))
                title = element.selectFirst(".mli-info h2")!!.text()
                thumbnail_url = element.selectFirst("img.mli-thumb")?.let { getImageUrl(it) }
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li.active + li") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET(listUrl("peliculas-nuevas", page), headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page")
            addQueryParameter("s", query)
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun listUrl(path: String, page: Int) = if (page > 1) "$baseUrl/$path/page/$page" else "$baseUrl/$path"

    // Links saved before the site redesign used /serie/<slug> and /pelicula/<slug>.
    private fun migrateUrl(url: String) = when {
        url.startsWith("/serie/") -> "/series/" + url.removePrefix("/serie/")
        url.startsWith("/pelicula/") -> "/" + url.removePrefix("/pelicula/")
        else -> url
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(baseUrl + migrateUrl(anime.url), headers)

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun getAnimeUrl(anime: SAnime) = baseUrl + migrateUrl(anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val isSeries = document.selectFirst(".mvi-content[itemtype*=TVSeries]") != null
        return SAnime.create().apply {
            title = document.selectFirst(".mvic-desc [itemprop=name]")!!.text()
            description = document.selectFirst(".mvic-desc .desc")?.text()
            thumbnail_url = document.selectFirst(".mvic-thumb img")?.let { getImageUrl(it)?.replace("/w185/", "/w500/") }
            genre = document.select(".mvici-left p:contains(Genre) a").joinToString { it.text() }
            status = if (isSeries) {
                val tvStatus = document.selectFirst(".mvici-right p:contains(TV Status) span")?.text()?.lowercase().orEmpty()
                when {
                    tvStatus.contains("returning") -> SAnime.ONGOING
                    tvStatus.contains("ended") -> SAnime.COMPLETED
                    tvStatus.contains("cancel") -> SAnime.CANCELLED
                    else -> SAnime.UNKNOWN
                }
            } else {
                SAnime.COMPLETED
            }
        }
    }

    private fun getImageUrl(element: Element): String? = when {
        element.hasAttr("data-original") -> element.absUrl("data-original")
        element.hasAttr("data-src") -> element.absUrl("data-src")
        element.hasAttr("src") -> element.absUrl("src")
        else -> null
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val seasons = document.select(".tvseason")
        if (seasons.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Película"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        }
        return seasons.flatMap { season ->
            val seasonNumber = season.selectFirst(".les-title")?.text()?.let { NUMBER_REGEX.find(it)?.value }
            season.select(".les-content a").mapIndexed { idx, link ->
                val epNumber = NUMBER_REGEX.find(link.text())?.value ?: "${idx + 1}"
                SEpisode.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = if (seasonNumber != null) "T$seasonNumber - Episodio $epNumber" else "Episodio $epNumber"
                    episode_number = epNumber.toFloatOrNull() ?: (idx + 1).toFloat()
                }
            }
        }.reversed()
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.useAsJsoup()
        return document.select(".player_nav a[href^=#tab]").mapNotNull { tab ->
            val lang = tab.text().lowercase()
            val prefix = when {
                lang.contains("latino") -> "[LAT]"
                lang.contains("castellano") -> "[CAST]"
                lang.contains("sub") || lang.contains("vose") -> "[SUB]"
                else -> ""
            }
            val iframe = document.getElementById(tab.attr("href").removePrefix("#"))?.selectFirst("iframe")
                ?: return@mapNotNull null
            val src = iframe.absUrl("src").ifEmpty { iframe.absUrl("data-src") }
                .replace("#038;", "&").replace("&amp;", "&")
            val url = src.toHttpUrlOrNull() ?: return@mapNotNull null
            Hoster(
                hosterUrl = src,
                hosterName = "$prefix ${tab.text()} - ${url.host}",
                internalData = prefix,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val prefix = hoster.internalData
        var src = hoster.hosterUrl
        if (src.contains("homecine")) {
            src = client.newCall(GET(src, headers)).awaitSuccess().useAsJsoup()
                .selectFirst("iframe")?.absUrl("src").orEmpty()
        }
        return when {
            src.contains("fastream") -> {
                if (src.contains("emb.html")) {
                    val key = src.split("/").last()
                    src = "https://fastream.to/embed-$key.html"
                }
                FastreamExtractor(client, headers).videosFromUrl(src, needsSleep = false, prefix = "$prefix Fastream:")
            }

            src.contains("upstream") -> {
                UpstreamExtractor(client).videosFromUrl(src, prefix = "$prefix ")
            }

            src.contains("yourupload") -> {
                YourUploadExtractor(client).videoFromUrl(src, headers, prefix = "$prefix ")
            }

            src.contains("voe") -> {
                VoeExtractor(client, headers).videosFromUrl(src, prefix = "$prefix ")
            }

            src.contains("wish") -> {
                StreamWishExtractor(client, headers).videosFromUrl(src) { "$prefix StreamWish:$it" }
            }

            src.contains("mp4upload") -> {
                Mp4uploadExtractor(client).videosFromUrl(src, headers, prefix = "$prefix ")
            }

            src.contains("burst") -> {
                BurstCloudExtractor(client).videoFromUrl(src, headers = headers, prefix = "$prefix ")
            }

            src.contains("filemoon") || src.contains("moonplayer") -> {
                FilemoonExtractor(client).videosFromUrl(src, headers = headers, prefix = "$prefix Filemoon:")
            }

            else -> emptyList()
        }.sortVideos()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(language) }
                .thenByDescending { it.hosterName.contains(server, true) },
        )
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(lang) },
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
