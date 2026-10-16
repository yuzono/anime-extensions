package eu.kanade.tachiyomi.animeextension.id.minioppai

import aniyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.id.minioppai.extractors.MiniOppaiExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MiniOppai :
    AnimeStream(
        "id",
        "MiniOppai",
        "https://minioppai.org",
    ) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    override val animeListUrl = "$baseUrl/anime"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale(lang))
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/page/$page/?order=popular")

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/page/$page/?order=update")

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst(".epl-num")!!.text().let {
            val num = it.substringAfterLast(" ")
            episode_number = num.toFloatOrNull() ?: 0F
            name = when {
                it.contains("OVA", true) -> "OVA $num"
                else -> "Episode $num"
            }
        }
        element.selectFirst(".epl-sub")?.text()?.let { scanlator = it }
        date_upload = element.selectFirst(".epl-date")?.text().let { dateFormatter.tryParse(it) }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "gdriveplayer" in url -> {
                val playerUrl = buildString {
                    val data = url.toHttpUrl().queryParameter("data")
                        ?: return emptyList()
                    if (data.startsWith("//")) append("https:")
                    append(data)
                }
                GdrivePlayerExtractor(client).videosFromUrl(playerUrl, name, headers)
            }

            url.toHttpUrlOrNull()?.host in PLAYER_HOSTS ->
                MiniOppaiExtractor(client).videosFromUrl(url, headers)

            else -> emptyList()
        }
    }

    // =========================== Anime Details ============================
    override fun getAnimeDescription(document: Document) = document.select("div.entry-content > p").eachText().joinToString("\n")

    override fun animeDetailsParse(document: Document) = super.animeDetailsParse(document).apply {
        title = title.substringBefore("Episode").substringBefore(" OVA ")
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = MiniOppaiFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val multiString = listOf(
                params.genres,
                params.countries,
                params.qualities,
                params.year,
                params.status,
            ).filter(String::isNotBlank).joinToString("&").let {
                when {
                    it.isBlank() -> it
                    else -> "&$it"
                }
            }

            GET("$animeListUrl/page/$page/?order=${params.order}$multiString")
        }
    }

    // ============================== Filters ===============================
    override val fetchFilters = false
    override fun getFilterList() = MiniOppaiFilters.FILTER_LIST

    companion object {
        private val PLAYER_HOSTS = setOf("paistream.my.id", "streampai.my.id")
    }
}
