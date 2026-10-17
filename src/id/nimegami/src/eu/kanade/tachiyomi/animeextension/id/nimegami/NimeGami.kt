package eu.kanade.tachiyomi.animeextension.id.nimegami

import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NimeGami : AnimeHttpSource() {

    override val name = "NimeGami"

    override val baseUrl = "https://nimegami.id"

    override val lang = "id"

    override val supportsLatest = true

    // The site's Brotli responses fail to decode in the app; ask for gzip, which the app's interceptors still decode.
    override val client = network.client.newBuilder()
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Accept-Encoding", "gzip").build())
        }
        .build()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = catalogRequest(page, "popular")

    override fun popularAnimeParse(response: Response) = catalogParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = catalogRequest(page, "updated")

    override fun latestUpdatesParse(response: Response) = catalogParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.firstOrNull()?.takeIf(String::isNotBlank)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$id/", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = catalogRequest(page, "updated", query)

    override fun searchAnimeParse(response: Response) = catalogParse(response)

    private fun catalogRequest(page: Int, sort: String, query: String = ""): Request {
        val url = "$baseUrl/anime/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("sort", sort)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.anime-card > a.poster-link").map { link ->
            SAnime.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                val poster = link.selectFirst("img")
                title = poster?.attr("alt")?.takeIf(String::isNotBlank) ?: link.attr("aria-label").removePrefix("Lihat ")
                thumbnail_url = poster?.imageUrl()
            }
        }
        val hasNextPage = document.selectFirst("a.page-arrow[aria-label=Halaman berikutnya]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("header.post-header h1")!!.text()
            thumbnail_url = document.selectFirst("div.detail-poster img")?.imageUrl()
            genre = document.select("div.post-genres a").joinToString { it.text() }
            artist = document.getInfo("Studio")
            status = when (document.getInfo("Status")?.lowercase()) {
                "ongoing" -> SAnime.ONGOING
                "completed", "finished", "tamat" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                document.select("div.synopsis-block > p").eachText().forEach { append("$it\n") }
                listOf("Judul alternatif", "Tipe", "Musim", "Mulai tayang", "Durasi").forEach { label ->
                    document.getInfo(label)?.let { append("\n$label: $it") }
                }
            }.trim()
        }
    }

    private fun Document.getInfo(label: String) = selectFirst("dl div:has(> dt:containsOwn($label)) > dd")?.text()
        ?.takeUnless { it == "Belum tersedia" }

    private fun Element.imageUrl(): String? {
        val src = attr("srcset").substringBefore(" ").ifBlank { attr("src") }.ifBlank { return null }
        val image = baseUrl.toHttpUrl().resolve(src) ?: return null
        return image.queryParameter("url")?.let { baseUrl.toHttpUrl().resolve(it)?.toString() } ?: image.toString()
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val data = fetchPlayerData(anime.url)
        val numbers = (data.links.mapNotNull { it.episode } + data.episodeInfo.map { it.number }).distinct().sortedDescending()
        return numbers.map { number ->
            SEpisode.create().apply {
                url = "/${data.slug}/#episode_$number"
                episode_number = number.toFloat()
                name = if (data.movie && numbers.size == 1) "Movie" else "Episode $number"
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode) = baseUrl + episode.url

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val number = episode.url.substringAfter("#episode_", "").toIntOrNull()
            ?: throw Exception("Refresh the episode list to play this episode")
        return fetchPlayerData(episode.url.substringBefore("#")).links
            .filter { it.episode == number && it.mode == "embed" && it.playUrl != null && SUPPORTED_HOSTS.any(it.host::contains) }
            .map { link ->
                Hoster(
                    hosterUrl = link.playUrl!!,
                    hosterName = listOfNotNull(link.label, link.quality).joinToString(" - "),
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return when {
            "mp4upload" in url -> Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = "${hoster.hosterName} - ")
            "vidhide" in url -> VidHideExtractor(client, headers).videosFromUrl(url) { resolution -> "${hoster.hosterName} ($resolution)" }
            else -> {
                val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
                val videoUrl = document.selectFirst("main#player[data-src]")?.absUrl("data-src")
                    ?: document.selectFirst("video[src], video source[src]")?.absUrl("src")
                    ?: return emptyList()
                listOf(Video(videoUrl = videoUrl, videoTitle = hoster.hosterName, headers = headers))
            }
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedByDescending { hoster ->
        QUALITY_REGEX.find(hoster.hosterName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private suspend fun fetchPlayerData(animeUrl: String): PlayerData = client.newCall(GET(baseUrl + animeUrl, headers))
        .awaitSuccess()
        .useAsJsoup()
        .extractNextJs<PlayerData>()
        ?: throw Exception("No episodes found")

    @Serializable
    class PlayerData(
        val links: List<LinkDto>,
        val slug: String,
        val movie: Boolean = false,
        val episodeInfo: List<EpisodeInfoDto> = emptyList(),
    )

    @Serializable
    class LinkDto(
        val playUrl: String? = null,
        val mode: String? = null,
        val label: String? = null,
        val host: String = "",
        val quality: String? = null,
        val episode: Int? = null,
    )

    @Serializable
    class EpisodeInfoDto(val number: Int)

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val SUPPORTED_HOSTS = listOf("berkasdrive", "workers.dev", "mp4upload", "vidhide")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
    }
}
