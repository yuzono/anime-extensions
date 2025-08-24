package eu.kanade.tachiyomi.animeextension.es.hentaila

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.es.hentaila.extractors.FireLoadExtractor
import eu.kanade.tachiyomi.animeextension.es.hentaila.extractors.MediaFireExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Hentaila : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Hentaila"
    override val baseUrl = "https://hentaila.com"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        val server = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

        if (server !in SERVER_LIST) {
            edit().also { editor ->
                editor.putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
            }.apply()
        }
    }

    companion object {
        private const val CDN_BASE_URL = "https://cdn.hentaila.com"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private val SERVER_LIST = arrayOf(
            "Megacloud",
            "Voe",
            "Arc",
            "YourUpload",
            "BurstCloud",
            "Sendvid",
            "MediaFire",
            "FireLoad",
            "VidHide",
            "VIP",
            "Mp4Upload",
            "StreamWish",
        )
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()
    }

    override fun popularAnimeRequest(page: Int) = GET(
        "$baseUrl/catalogo/__data.json?order=popular&page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response) = getAnimes(response)

    override fun latestUpdatesRequest(page: Int) = GET(
        "$baseUrl/catalogo/__data.json?order=latest_released&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response) = getAnimes(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/catalogo/__data.json".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter("search", query)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters

            filterList.forEach { filter ->
                when (filter) {
                    is GenreFilter -> urlBuilder.addQueryParameter("genre", filter.toUriPart())
                    is OrderFilter -> urlBuilder.addQueryParameter("filter", filter.toUriPart())
                    is StatusOngoingFilter -> if (filter.state) {
                        urlBuilder.addQueryParameter("status", "emision")
                    }

                    is StatusCompletedFilter -> if (filter.state) {
                        urlBuilder.addQueryParameter("status", "finalizado")
                    }

                    is UncensoredFilter -> if (filter.state) {
                        urlBuilder.addQueryParameter("uncensored", "")
                    }

                    else -> {}
                }
            }
        }
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response) = getAnimes(response)

    private fun getAnimes(response: Response): AnimesPage {
        val document = response.parseAs<HentailaJsonDto>()

        for (doc in document.nodes) {
            if (doc?.uses?.searchParams != null) {
                val data = doc.data ?: continue
                val result = data.getOrNull(0)?.jsonObject ?: continue

                val resultsCode = result["results"]?.jsonPrimitive?.intOrNull ?: continue
                val animeIdJson = data.getOrNull(resultsCode)?.jsonArray ?: continue
                val animeIds = animeIdJson.map { it.jsonPrimitive.int }

                val paginationCode = result["pagination"]?.jsonPrimitive?.intOrNull ?: -1
                val pagination = data.getOrNull(paginationCode)?.jsonObject

                val currentPageCode = pagination?.get("currentPage")?.jsonPrimitive?.intOrNull ?: -1
                val currentPage = data.getOrNull(currentPageCode)?.jsonPrimitive?.intOrNull ?: -1

                val totalPageCode = pagination?.get("totalPages")?.jsonPrimitive?.intOrNull ?: -1
                val totalPage = data.getOrNull(totalPageCode)?.jsonPrimitive?.intOrNull ?: -1

                val animes = animeIds.mapNotNull { id ->
                    val currentAnimeDetails = data.getOrNull(id)?.jsonObject ?: return@mapNotNull null
                    val animeId = data.getString(currentAnimeDetails, "id")
                    val animeTitle = data.getString(currentAnimeDetails, "title") ?: return@mapNotNull null
                    val animeSynopsis = data.getString(currentAnimeDetails, "synopsis")
                    val animeSlug = data.getString(currentAnimeDetails, "slug") ?: return@mapNotNull null

                    SAnime.create().apply {
                        title = animeTitle
                        url = "/media/$animeSlug"
                        description = animeSynopsis
                        animeId?.let {
                            thumbnail_url = "$CDN_BASE_URL/covers/$it.jpg"
                        }
                        update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                    }
                }

                val hasNextPage = currentPage < totalPage
                return AnimesPage(animes, hasNextPage)
            }
        }
        return AnimesPage(emptyList(), false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val statusData = document.select("div.flex.flex-wrap.items-center.text-sm span")

        val currentStatus = if (statusData.any { it.text() == "En emisión" }) {
            SAnime.ONGOING
        } else {
            SAnime.COMPLETED
        }

        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("img.object-cover.w-full.aspect-poster")?.attr("src")
            title = document.selectFirst(".grid.items-start h1.text-lead")?.text()!!
            description = document.select(".entry.text-lead.text-sm p").text()
            genre = document.select(".flex-wrap.items-center .btn.btn-xs.rounded-full:not(.sm\\:w-auto)")
                .joinToString { it.text() }
            status = currentStatus
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()

        jsoup.select("article.group\\/item").forEach {
            val href = it.select("a").attr("abs:href")
            val epNum = it.select("div.bg-line.text-subs span").text()
            val episode = SEpisode.create().apply {
                epNum.toFloatOrNull()?.let { num -> episode_number = num }
                name = "Episodio $epNum"
                setUrlWithoutDomain(href)
            }
            episodes.add(episode)
        }
        return episodes.reversed()
    }

    private fun JsonArray.getString(obj: JsonObject, key: String): String? {
        val code = obj[key]?.jsonPrimitive?.intOrNull ?: return null
        return this.getOrNull(code)?.jsonPrimitive?.content
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val mediaFireExtractor by lazy { MediaFireExtractor(client) }
    private val fireLoadExtractor by lazy { FireLoadExtractor(client) }
    private val vidhideExtractor by lazy { VidHideExtractor(client, headers) }
    private val megacloudExtractor by lazy { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = "$baseUrl${episode.url}/__data.json"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.parseAs<HentailaJsonDto>()
        val serverList = mutableListOf<VideoData>()

        for (each in document.nodes) {
            if (each?.uses?.params != null && each.uses.params.firstOrNull() == "number") {
                val data = each.data ?: continue
                val result = data.getOrNull(0)?.jsonObject ?: continue

                val embedsCode = result["embeds"]?.jsonPrimitive?.intOrNull ?: continue
                val embedData = data.getOrNull(embedsCode)?.jsonObject ?: continue

                val subEmbedDataCode = embedData["SUB"]?.jsonPrimitive?.intOrNull ?: continue
                val subEmbedVideoArray = data.getOrNull(subEmbedDataCode)?.jsonArray ?: continue

                val subEmbedVideoArrayList = subEmbedVideoArray.mapNotNull {
                    it.jsonPrimitive.intOrNull
                }

                for (sourceCode in subEmbedVideoArrayList) {
                    val currentSourceData = data.getOrNull(sourceCode)?.jsonObject ?: continue

                    val serverNameCode = currentSourceData["server"]?.jsonPrimitive?.intOrNull ?: continue
                    val serverUrlCode = currentSourceData["url"]?.jsonPrimitive?.intOrNull ?: continue

                    val serverName = data.getOrNull(serverNameCode)?.jsonPrimitive?.content ?: continue
                    val serverUrl = data.getOrNull(serverUrlCode)?.jsonPrimitive?.content ?: continue

                    serverList.add(VideoData(serverName, serverUrl))
                }
            }
        }

        val allVideos = serverList.parallelCatchingFlatMapBlocking { server ->
            val serverName = serverDomainCatalog.firstOrNull { (_, names) -> names.any { it.lowercase() in server.url.lowercase() } }?.first
                ?: server.name.lowercase()
            when (serverName) {
                "streamwish" -> streamWishExtractor.videosFromUrl(server.url, videoNameGen = { "StreamWish:$it" })
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(server.url, headers = headers, prefix = "Mp4Upload")
                "voe" -> voeExtractor.videosFromUrl(server.url)
                "arc" -> listOf(Video(server.url.substringAfter("#"), "Arc", server.url.substringAfter("#")))
                "yupi", "yourupload" -> yourUploadExtractor.videoFromUrl(server.url, headers = headers)
                "burst", "burstcloud" -> burstCloudExtractor.videoFromUrl(server.url, headers = headers)
                "sendvid" -> sendvidExtractor.videosFromUrl(server.url)
                "mediafire" -> mediaFireExtractor.getVideoFromUrl(server.url)
                "fireload" -> fireLoadExtractor.getVideoFromUrl(server.url)
                "vidhide" -> vidhideExtractor.videosFromUrl(server.url) // streamHideVidExtractor
                "mega" -> megacloudExtractor.getVideosFromUrl(server.url, "Megacloud", "Megacloud")
                "vip" -> universalExtractor.videosFromUrl(
                    server.url.replace("/play/", "/m3u8/"),
                    origRequestHeader = headers,
                    prefix = "VIP",
                )
                else -> universalExtractor.videosFromUrl(server.url, headers)
            }
        }
        return allVideos
    }

    private val serverDomainCatalog = listOf(
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "arc" to listOf("arc"),
        "mp4upload" to listOf("mp4upload"),
        "yourupload" to listOf("yourupload", "yupi"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "sendvid" to listOf("sendvid"),
        "mediafire" to listOf("mediafire"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT).orEmpty()

        return sortedWith(
            compareBy<Video> { it.quality.contains(server, ignoreCase = true) }
                .thenBy { it.quality.contains(quality) }
                .thenBy { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        GenreFilter(),
        AnimeFilter.Separator(),
        OrderFilter(),
        AnimeFilter.Separator(),
        StatusOngoingFilter(),
        StatusCompletedFilter(),
        AnimeFilter.Separator(),
        UncensoredFilter(),
    )

    private class StatusOngoingFilter : AnimeFilter.CheckBox("En Emisión")
    private class StatusCompletedFilter : AnimeFilter.CheckBox("Finalizado")
    private class UncensoredFilter : AnimeFilter.CheckBox("Sin Censura")

    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            "<Seleccionar>" to "",
            "Populares" to "popular",
            "Recientes" to "recent",
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            "<Seleccionar>" to "",
            "3D" to "3d",
            "Ahegao" to "ahegao",
            "Anal" to "anal",
            "Casadas" to "casadas",
            "Chikan" to "chikan",
            "Ecchi" to "ecchi",
            "Enfermeras" to "enfermeras",
            "Futanari" to "futanari",
            "Escolares" to "escolares",
            "Gore" to "gore",
            "Hardcore" to "hardcore",
            "Harem" to "harem",
            "Incesto" to "incesto",
            "Juegos Sexuales" to "juegos-sexuales",
            "Milfs" to "milfs",
            "Maids" to "maids",
            "Netorare" to "netorare",
            "Ninfomanía" to "ninfomania",
            "Ninjas" to "ninjas",
            "Orgías" to "orgias",
            "Romance" to "romance",
            "Shota" to "shota",
            "Softcore" to "softcore",
            "Succubus" to "succubus",
            "Teacher" to "teacher",
            "Tentáculos" to "tentaculos",
            "Tetonas" to "tetonas",
            "Vanilla" to "vanilla",
            "Violación" to "violacion",
            "Vírgenes" to "virgenes",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
            "Bondage" to "bondage",
            "Elfas" to "elfas",
            "Petit" to "petit",
            "Threesome" to "threesome",
            "Paizuri" to "paizuri",
            "Gal" to "gal",
            "Oyakodon" to "oyakodon",
        ),
    )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
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
    }
}
