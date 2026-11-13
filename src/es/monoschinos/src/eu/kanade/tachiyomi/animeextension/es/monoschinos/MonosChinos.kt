package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

@Source
abstract class MonosChinos :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf(
            "Voe",
            "StreamWish",
            "Okru",
            "Uqload",
            "Filemoon",
            "DoodStream",
            "MixDrop",
            "Streamtape",
            "Mp4Upload",
            "LuluStream",
        )

        private val PREF_SERVER_ALIASES: Map<String, List<String>> = mapOf(
            "Voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
            "StreamWish" to listOf("streamwish", "wish", "swdyu", "iplayerhls", "strwish", "wishembed", "kswplayer", "swhoi", "multimovies", "uqloads", "neko-stream", "streamgg"),
            "Okru" to listOf("okru", "ok.ru"),
            "Uqload" to listOf("uqload"),
            "Filemoon" to listOf("filemoon", "bysekoze", "moonplayer", "files.im", "moviesm4u", "filemoon.sx"),
            "DoodStream" to listOf("doodstream", "dood", "ds2play", "ds2video", "dvsplay", "dvsplayer", "playmogo", "dooood", "d000d", "d0000d", "dooodster", "doods"),
            "MixDrop" to listOf("mixdrop", "mxdrop"),
            "Streamtape" to listOf("streamtape", "stape", "stp", "shavetape"),
            "Mp4Upload" to listOf("mp4upload"),
            "LuluStream" to listOf("lulustream", "lulu", "luluvdo"),
        )

        private val EPISODE_SLUG_REGEX = Regex("-episodio-(\\d+|[\\d.]+)$")
        private val SUB_ES_REGEX = Regex("-sub-espanol$")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val NON_ALNUM = Regex("[^a-z0-9]")
        private val EP_NUM_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        private val EXACT_ONLY_ALIASES = setOf("upload")
    }

    // ====================== POPULAR ======================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val elements = document.select("a[href*='/anime/']")
            .filter { it.selectFirst("h3, h2, .title") != null }
        val nextPage = document.selectFirst("a[rel='next']") != null

        val animeList = elements.mapNotNull { element ->
            SAnime.create().apply {
                title = element.selectFirst("h3, h2, .title")?.text() ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    // ====================== LATEST EPISODES ======================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val episodeItems = document.select("section:has(h2:contains(Últimos capítulos)) a[href*='/ver/']")

        val animeList = episodeItems.mapNotNull { a ->
            val episodeUrl = a.attr("abs:href")
            val episodeSlug = episodeUrl.toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
                ?: return@mapNotNull null
            val animeSlugBase = episodeSlug.replace(EPISODE_SLUG_REGEX, "")
            val animeUrl = "/anime/$animeSlugBase-sub-espanol"

            val title = a.selectFirst("h3, h2, .title, .card-title")?.text()
                ?: return@mapNotNull null

            val episodeNumber = a.select("span, div")
                .firstOrNull { it.text().lowercase().startsWith("ep") }
                ?.text()
                ?.let { EP_NUM_REGEX.find(it)?.groupValues?.get(1) }
                ?: ""

            SAnime.create().apply {
                this.title = if (episodeNumber.isNotBlank()) {
                    "$title - Episodio $episodeNumber"
                } else {
                    title
                }
                setUrlWithoutDomain(animeUrl)

                description = a.select("span, div").lastOrNull {
                    it.text().isNotEmpty() && !it.text().lowercase().contains("ep")
                }?.text()
                thumbnail_url = a.selectFirst("img")?.getImageUrl()
            }
        }

        val nextPage = document.selectFirst("a[rel='next']") != null
        return AnimesPage(animeList, nextPage)
    }

    // ====================== SEARCH ======================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> {
                val url = "$baseUrl/buscar".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("p", page.toString())
                    .build()
                GET(url, headers)
            }
            params.filter.isNotBlank() -> {
                val builder = "$baseUrl/animes".toHttpUrl().newBuilder()
                    .addQueryParameter("p", page.toString())
                Filters.applyTo(builder, filters)
                GET(builder.build(), headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====================== DETAIL ======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()
                ?: throw Exception("MonosChinos: missing <h1> title on ${document.location()}")

            thumbnail_url = document.select("img").firstOrNull { img ->
                val src = img.attr("abs:src").lowercase()
                src.contains("portada") || src.contains("poster")
            }?.getImageUrl() ?: document.selectFirst("img:not([src*='logo'])")?.getImageUrl()

            description = document.selectFirst("h3:contains(Sobre la serie)")?.nextElementSibling()?.text()
                ?: document.selectFirst("p:contains(Sinopsis completa)")?.nextElementSibling()?.text()
                ?: document.select("p").firstOrNull { it.text().length > 50 }?.text()

            genre = document.select("a[href*='/genero/']").joinToString { it.text() }

            val statusText = (
                document.selectFirst("span:contains(Estado)")?.nextElementSibling()?.text()
                    ?: document.selectFirst("h3:contains(Estado)")?.nextElementSibling()?.text()
                    ?: document.select("span, div").firstOrNull {
                        it.text() in listOf("Estreno", "En emisión", "Finalizado")
                    }?.text()
                    ?: "Desconocido"
                ).lowercase()

            status = when {
                statusText.contains("estreno") || statusText.contains("en emisión") -> SAnime.ONGOING
                statusText.contains("finalizado") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ====================== EPISODES ======================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("Use the suspend getEpisodeList() override")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url}"
        val document = client.get(animeUrl, headers).asJsoup()

        val rawAjax = document.selectFirst("section.caplist")?.attr("data-ajax")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val baseHttp = baseUrl.toHttpUrl()
        val ajaxUrl = if (rawAjax.startsWith("http")) {
            rawAjax.toHttpUrlOrNull()
        } else {
            animeUrl.toHttpUrl().resolve(rawAjax)
        } ?: return emptyList()

        if (ajaxUrl.host != baseHttp.host || ajaxUrl.scheme != baseHttp.scheme) return emptyList()

        val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""

        val animeSlug = animeUrl.toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
            ?: anime.url.substringAfter("/anime/").substringBefore("?").substringBefore("#")

        val episodeSlug = document.selectFirst("a[href^='/ver/']")?.attr("href")
            ?.substringAfter("/ver/")
            ?.substringBefore("-episodio-")
            ?: animeSlug.replace(SUB_ES_REGEX, "")

        if (episodeSlug.isBlank()) return emptyList()

        val episodes = mutableListOf<SEpisode>()
        var currentPage = 1
        var hasMore = true
        val maxPages = 200

        while (hasMore && currentPage <= maxPages) {
            val paginatedUrl = if (currentPage == 1) {
                ajaxUrl.toString()
            } else {
                val separator = if (ajaxUrl.toString().contains("?")) "&" else "?"
                "$ajaxUrl${separator}page=$currentPage"
            }

            val formBody = FormBody.Builder()
                .add("_token", csrfToken)
                .build()

            val requestHeaders = headers.newBuilder()
                .set("Referer", animeUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            val json = try {
                val request = Request.Builder()
                    .url(paginatedUrl)
                    .post(formBody)
                    .headers(requestHeaders)
                    .build()
                client.newCall(request).awaitSuccess().parseAs<EpisodesDto>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw Exception(
                    "MonosChinos: failed to load episode page $currentPage for $animeUrl",
                    e,
                )
            }

            for (obj in json.eps) {
                val numStr = obj.numStr
                if (numStr.isBlank()) continue
                val episodeNumber = numStr.toFloatOrNull() ?: continue
                val urlNumber = if (episodeNumber % 1 == 0f) {
                    episodeNumber.toInt().toString()
                } else {
                    numStr
                }
                episodes.add(
                    SEpisode.create().apply {
                        name = if (episodeNumber % 1 == 0f) {
                            "Episodio ${episodeNumber.toInt()}"
                        } else {
                            "Episodio $numStr"
                        }
                        episode_number = episodeNumber
                        setUrlWithoutDomain("/ver/$episodeSlug-episodio-$urlNumber")
                    },
                )
            }

            val perpage = json.perpage ?: 0
            if (perpage == 0 || json.eps.size < perpage) {
                hasMore = false
            } else {
                currentPage++
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ====================== HOSTERS/VIDEOS ======================

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val serverButtons = document.select("button.play-video[data-player]")
        return serverButtons.mapNotNull { button ->
            val encoded = button.attr("data-player")
            if (encoded.isBlank()) return@mapNotNull null
            val decodedUrl = try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null

            if (!isSafeHosterUrl(decodedUrl)) return@mapNotNull null

            val serverName = button.attr("data-server").takeIf { it.isNotBlank() }
                ?: button.text().takeIf { it.isNotBlank() }
                ?: ""

            Hoster(
                hosterUrl = decodedUrl,
                hosterName = serverName,
                internalData = serverName,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        serverVideoResolver(hoster.hosterUrl, hoster.internalData)

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending<Hoster> { matchesPref(it.hosterName, server) },
        )
    }

    // ====================== EXTRACTORS ======================

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "filemoon.sx", "bysekoze"),
        "uqload" to listOf("uqload", "upload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "kswplayer", "swhoi", "multimovies", "uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d", "dooodster", "dvsplayer", "dvsplay", "playmogo"),
        "mixdrop" to listOf("mixdrop", "mxdrop"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "lulu" to listOf("luluvdo", "lulu", "lulustream"),
    )

    private val doodstreamDomains = listOf(
        "dvsplay.com", "doodstream.com", "dooodster.com", "dood.to", "dooood.com",
        "doods.pro", "d000d.com", "ds2play.com", "ds2video.com", "d0000d.com",
        "playmogo.com", "dvsplayer.com",
    )

    private var cachedDoodDomain: String? = null

    private suspend fun serverVideoResolver(url: String, serverName: String = ""): List<Video> {
        val source = url.lowercase()
        val serverKey = serverName.lowercase().replace(NON_ALNUM, "")

        val exactMatched = if (serverKey.isEmpty()) {
            null
        } else {
            conventions.firstOrNull { (_, aliases) ->
                aliases.any { alias -> alias.lowercase().replace(NON_ALNUM, "") == serverKey }
            }?.first
        }

        val nameMatched = exactMatched ?: if (serverKey.isEmpty()) {
            null
        } else {
            conventions.firstOrNull { (key, aliases) ->
                val k = key.lowercase()
                serverKey.contains(k) || k.contains(serverKey) ||
                    aliases
                        .filterNot { it.lowercase() in EXACT_ONLY_ALIASES }
                        .any { serverKey.contains(it.lowercase().replace(NON_ALNUM, "")) }
            }?.first
        }

        val sourceMatched = nameMatched ?: conventions.firstOrNull { (_, aliases) ->
            aliases
                .filterNot { it.lowercase() in EXACT_ONLY_ALIASES }
                .any { it in source }
        }?.first

        val effectiveMatched = sourceMatched ?: when {
            serverKey.contains("dood") -> "doodstream"
            serverKey.contains("filemoon") -> "filemoon"
            serverKey.contains("lulu") -> "lulu"
            serverKey.contains("mxdrop") -> "mixdrop"
            else -> null
        }

        if (effectiveMatched == "doodstream") {
            val candidates = buildDoodCandidates(url)
            for (candidate in candidates) {
                val videos = tryDoodExtract(candidate)
                if (videos.isNotEmpty()) {
                    candidate.toHttpUrlOrNull()?.host?.let { cachedDoodDomain = it }
                    return videos
                }
            }
            return emptyList()
        }

        return when (effectiveMatched) {
            "voe" -> voeExtractor.videosFromUrl(url)
            "okru" -> okruExtractor.videosFromUrl(url)
            "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            "uqload" -> uqloadExtractor.videosFromUrl(url)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
            "streamwish" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            "mixdrop" -> mixdropExtractor.videosFromUrl(url)
            "streamtape" -> streamTapeExtractor.videosFromUrl(url)
            "lulu" -> luluExtractor.videosFromUrl(url, prefix = "LuluStream:")
            else -> if (isSafeHosterUrl(url)) universalExtractor.videosFromUrl(url, headers) else emptyList()
        }
    }

    private fun buildDoodCandidates(url: String): List<String> {
        val original = url.toHttpUrlOrNull() ?: return listOf(url)
        val path = original.encodedPath
        val query = original.encodedQuery
        val originalHost = original.host

        val ordered = buildList {
            add(originalHost)
            cachedDoodDomain?.takeIf { it != originalHost }?.let { add(it) }
            addAll(doodstreamDomains.filter { it != originalHost && it != cachedDoodDomain })
        }

        return ordered.map { host ->
            if (query != null) "https://$host$path?$query" else "https://$host$path"
        }
    }

    private suspend fun tryDoodExtract(url: String): List<Video> = try {
        doodExtractor.videosFromUrl(url, "DoodStream:")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // ====================== ORDER ======================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy<Video>(
                { matchesPref(it.videoTitle, server) },
                { it.videoTitle.contains(quality) },
                { QUALITY_REGEX.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ).reversed(),
        )
    }

    // ====================== ASSISTANTS ======================

    private fun isSafeHosterUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.scheme != "http" && u.scheme != "https") return false

        val host = u.host.lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host == "::1" || host == "0.0.0.0") return false

        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10 || a == 127 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) || (a == 169 && b == 254)
            ) {
                return false
            }
        }
        return true
    }

    private fun matchesPref(text: String, pref: String): Boolean {
        val t = text.lowercase().replace(NON_ALNUM, "")
        if (t.isEmpty()) return false
        val keys: List<String> = PREF_SERVER_ALIASES[pref] ?: listOf(pref.lowercase())
        return keys.any { key ->
            val k = key.lowercase().replace(NON_ALNUM, "")
            when {
                k.isEmpty() -> false
                k in EXACT_ONLY_ALIASES -> t == k
                else -> t.contains(k) || k.contains(t)
            }
        }
    }

    private fun Element.getImageUrl(): String? {
        val candidates = listOf("data-src", "data-lazy-src", "srcset", "src")
        return candidates.mapNotNull { name ->
            when (name) {
                "srcset" -> this.attr("abs:srcset").substringBefore(" ")
                else -> this.attr("abs:$name")
            }
        }.firstOrNull { url ->
            url.isNotBlank() && !url.contains("anime.png") && !url.contains("logo")
        }
    }

    // ====================== FILTERS ======================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // ====================== PREFERENCES ======================

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
