package eu.kanade.tachiyomi.animeextension.it.hentaisaturn

import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.io.encoding.Base64

private const val ANIME_SELECTOR = "a.group[href]:not(.flex)"
private const val NEXT_PAGE_SELECTOR = "a[rel=\"next\"]"
private val alterTitleRegex = Regex("\\s+\\(\\w+\\)")

@Source
abstract class HentaiSaturn :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("a.ep-tile").map { episodeFromElement(it) }.reversed()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = client.newCall(episodeListRequest(anime))
            .awaitSuccess()
            .use(::episodeListParse)

        val latestDate = try {
            episodes.firstOrNull()?.let { getEpisodeDate(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HentaiSaturn", "Failed to fetch the latest episode date", e)
            null
        }
        if (latestDate != null) {
            episodes.forEach { it.date_upload = latestDate }
        }
        return episodes
    }

    fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(
            element.attr("href")
                .replace("episode/", "hentai/")
                .replace("watch/", "stream/"),
        )
        val fallbackName = element.attr("title")
        episode.episode_number = fallbackName.substringAfter("Episodio ").toFloatOrNull() ?: 0f
        episode.name = fallbackName

        return episode
    }

    private suspend fun getEpisodeDate(episode: SEpisode): Long? {
        return client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess()
            .use { response ->
                val tvEpisode = response.asJsoup()
                    .head()
                    .selectFirst("script[type=\"application/ld+json\"]:containsData(\"TVEpisode\")")
                    ?.data()
                    ?.let { runCatching { it.parseAs<TVEpisodeLD>() }.getOrNull() }
                    ?: return null

                tvEpisode.datePublished
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).tryParse(it) }
            }
    }

    @Serializable
    internal class TVEpisodeLD(
        val name: String? = null,
        val episodeNumber: Float? = null,
        val datePublished: String? = null,
        val partOfSeries: TVSeriesLD? = null,
    )

    @Serializable
    internal class TVSeriesLD(
        val name: String? = null,
    )

    override fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val episodePage = response.asJsoup()
        val url = episodePage.selectFirst("iframe")!!.attr("src")
        return listOf(
            Hoster(
                hosterName = "HentaiSaturn Server",
                hosterUrl = url.replace("?", "/playlist?"),
                internalData = url,
            ),
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun decodeUrl(url: String, token: String): String {
        val base = Base64.decode(url).decodeToString()
        return buildString {
            for (i in base.indices) {
                append(base[i].code.xor(token[i % token.length].code).toChar())
            }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val request = Request.Builder()
            .url(hoster.hosterUrl)
            .header("Referer", hoster.internalData)
            .build()
        client.newCall(request).awaitSuccess().use { response ->
            val token = response.request.url.queryParameter("token") ?: return emptyList()
            val playlistModel = response.parseAs<PlaylistModel>()
            val videoUrl = decodeUrl(playlistModel.d, token)

            if (videoUrl.contains(".mp4")) {
                return listOf(
                    Video(
                        videoUrl = videoUrl,
                        videoTitle = "Qualità predefinita",
                    ),
                )
            }
            return playlistUtils.extractFromHls(videoUrl)
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prQuality = preferences.getString(PREF_QUALITY, QUALITY_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.videoTitle.contains(prQuality) },
        )
    }

    fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.selectFirst("h3")!!.text()
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/filter/$page".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
        getSearchParameters(url, filters)
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select(ANIME_SELECTOR).map(::searchAnimeFromElement)
        return AnimesPage(anime, document.selectFirst(NEXT_PAGE_SELECTOR) != null)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")!!.text()
        anime.author = document.selectFirst("div a[href*=studios]")?.text()
        val statusText = document.selectFirst("div a[href*=states]")?.text().orEmpty()
        anime.status = parseStatus(statusText)
        if (anime.status == SAnime.COMPLETED || anime.status == SAnime.CANCELLED) {
            anime.update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        }
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        var alterTitle = document.selectFirst("div.text-center > p.mt-1")?.text().orEmpty()
        if (anime.title == alterTitle) {
            alterTitle = ""
        }
        alterTitle = alterTitle.replace(alterTitleRegex, "")
        val descriptionText = document.selectFirst("section:has(h2) div")?.text().orEmpty()
        val typeText = document.selectFirst("div:nth-of-type(1) > dd")?.text().orEmpty()
        val releaseText = document.selectFirst("div:nth-of-type(5) > dd")?.text().orEmpty()
        val langText = document.selectFirst("div:nth-of-type(3) > dd > a")?.text().orEmpty()
        val durationText = document.selectFirst("div:nth-of-type(7) > dd")?.text().orEmpty()
        val viewsText = document.selectFirst("div:nth-of-type(8) > dd")?.text().orEmpty()
        val voteText = document.selectFirst("#hentai-score")?.text().orEmpty()
        val votersText = document.selectFirst("#hentai-votes")?.text().orEmpty()

        anime.description = buildString {
            if (statusText.isNotEmpty() && statusText !in arrayOf("In corso", "Finito", "Droppato")) append("Attenzione: ${statusText}\n\n")
            if (anime.title.lowercase() != alterTitle.lowercase() && alterTitle.isNotEmpty()) append("Titolo Alternativo: ${alterTitle}\n\n")
            if (langText.isNotEmpty()) append("Lingua: ${langText}\n\n")
            if (descriptionText.isNotEmpty()) append("Descrizione: ${descriptionText}\n\n")
            if (typeText.isNotEmpty()) append("Tipo: ${typeText}\n\n")
            if (releaseText.isNotEmpty()) append("Uscita: ${releaseText}\n\n")
            if (durationText.isNotEmpty()) append("Durata Media: ${durationText}\n\n")
            if (viewsText.isNotEmpty()) append("Visualizzazioni: ${viewsText}\n\n")
            if (voteText.isNotEmpty()) append("Voto: ★$voteText/10 ($votersText)\n\n")
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("In corso") -> SAnime.ONGOING
        statusString.contains("Finito") -> SAnime.COMPLETED
        statusString.contains("Droppato") -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // Filters
    private class CheckboxFilter(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class CheckboxGroup(
        val key: String,
        title: String,
        options: List<CheckboxFilter>,
    ) : AnimeFilter.Group<CheckboxFilter>(title, options)

    private class SelectFilter(val id: String, private val name: String) {
        override fun toString(): String = name
    }

    private class SelectGroup(
        val key: String,
        title: String,
        options: Array<SelectFilter>,
    ) : AnimeFilter.Select<SelectFilter>(title, options)

    private fun getGenres() = listOf(
        CheckboxFilter("1", "3D"),
        CheckboxFilter("2", "Ahegao"),
        CheckboxFilter("3", "Anal"),
        CheckboxFilter("4", "BDSM"),
        CheckboxFilter("5", "Big Boobs"),
        CheckboxFilter("6", "Blow Job"),
        CheckboxFilter("7", "Bondage"),
        CheckboxFilter("8", "Boob Job"),
        CheckboxFilter("9", "Censored"),
        CheckboxFilter("10", "Comedy"),
        CheckboxFilter("11", "Cosplay"),
        CheckboxFilter("12", "Creampie"),
        CheckboxFilter("13", "Dark Skin"),
        CheckboxFilter("14", "Facial"),
        CheckboxFilter("15", "Fantasy"),
        CheckboxFilter("16", "Filmed"),
        CheckboxFilter("17", "Foot Job"),
        CheckboxFilter("18", "Futanari"),
        CheckboxFilter("19", "Gangbang"),
        CheckboxFilter("20", "Glasses"),
        CheckboxFilter("21", "Hand Job"),
        CheckboxFilter("22", "Harem"),
        CheckboxFilter("25", "Horror"),
        CheckboxFilter("26", "Incest"),
        CheckboxFilter("27", "Inflation"),
        CheckboxFilter("29", "Lactation"),
        CheckboxFilter("30", "Loli"),
        CheckboxFilter("31", "Maid"),
        CheckboxFilter("32", "Masturbation"),
        CheckboxFilter("33", "Milf"),
        CheckboxFilter("34", "Mind Break"),
        CheckboxFilter("35", "Mind Control"),
        CheckboxFilter("36", "Monster"),
        CheckboxFilter("37", "NTR"),
        CheckboxFilter("38", "Nurse"),
        CheckboxFilter("39", "Orgy"),
        CheckboxFilter("40", "Plot"),
        CheckboxFilter("41", "POV"),
        CheckboxFilter("42", "Pregnant"),
        CheckboxFilter("43", "Public Sex"),
        CheckboxFilter("44", "Rape"),
        CheckboxFilter("45", "Reverse Rape"),
        CheckboxFilter("46", "Rimjob"),
        CheckboxFilter("48", "Scat"),
        CheckboxFilter("49", "School Girl"),
        CheckboxFilter("51", "Shota"),
        CheckboxFilter("52", "Softcore"),
        CheckboxFilter("53", "Swimsuit"),
        CheckboxFilter("54", "Teacher"),
        CheckboxFilter("55", "Tentacle"),
        CheckboxFilter("56", "Threesome"),
        CheckboxFilter("57", "Toys"),
        CheckboxFilter("58", "Trap"),
        CheckboxFilter("59", "Tsundere"),
        CheckboxFilter("60", "Ugly Bastard"),
        CheckboxFilter("61", "Uncensored"),
        CheckboxFilter("62", "Vanilla"),
        CheckboxFilter("63", "Virgin"),
        CheckboxFilter("64", "Watersports"),
        CheckboxFilter("65", "X-Ray"),
        CheckboxFilter("66", "Yaoi"),
        CheckboxFilter("67", "Yuri"),
    )

    private fun getYears() = run {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        (1960..currentYear).map { CheckboxFilter(it.toString(), it.toString()) }
    }

    private fun getStates() = listOf(
        CheckboxFilter("0", "In corso"),
        CheckboxFilter("1", "Finito"),
        CheckboxFilter("2", "Non rilasciato"),
        CheckboxFilter("3", "Droppato"),
    )

    private fun getTypes() = listOf(
        CheckboxFilter("3", "OVA"),
        CheckboxFilter("4", "Special"),
        CheckboxFilter("5", "ONA"),
    )

    private fun getLangs() = listOf(
        CheckboxFilter("jp", "Giapponese"),
        CheckboxFilter("it", "Italiano"),
        CheckboxFilter("en", "Inglese"),
        CheckboxFilter("kr", "Coreano"),
        CheckboxFilter("ch", "Cinese"),
    )

    private fun getSubs() = arrayOf(
        SelectFilter("", "Tutti"),
        SelectFilter("0", "Sottotitolato"),
        SelectFilter("1", "Doppiato"),
    )

    private fun getOrder() = arrayOf(
        SelectFilter("standard", "Standard"),
        SelectFilter("recent", "Ultime aggiunte"),
        SelectFilter("az", "Lista A-Z"),
        SelectFilter("za", "Lista Z-A"),
        SelectFilter("oldest", "Più vecchi (anno)"),
        SelectFilter("newest", "Più recenti (anno)"),
        SelectFilter("most_viewed", "Più visti"),
        SelectFilter("least_viewed", "Meno visti"),
        SelectFilter("best_rated", "Meglio valutati"),
        SelectFilter("worst_rated", "Peggio valutati"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SelectGroup("sort", "Ordina per", getOrder()),
        CheckboxGroup("categories", "Generi", getGenres()),
        CheckboxGroup("years", "Anno di Uscita", getYears()),
        CheckboxGroup("states", "Stato", getStates()),
        CheckboxGroup("types", "Tipo", getTypes()),
        CheckboxGroup("languages", "Lingua", getLangs()),
        SelectGroup("dub", "Sottotitoli", getSubs()),
    )

    private fun getSearchParameters(url: HttpUrl.Builder, filters: AnimeFilterList) {
        filters.forEach { filter ->
            when (filter) {
                is CheckboxGroup -> {
                    filter.state.filter { it.state }.forEachIndexed { index, option ->
                        url.addQueryParameter("${filter.key}[$index]", option.id)
                    }
                }

                is SelectGroup -> {
                    filter.values[filter.state].id.takeIf { it.isNotEmpty() }?.let {
                        url.addQueryParameter(filter.key, it)
                    }
                }

                else -> Unit
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Qualità Preferita",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()
    }

    @Serializable
    class PlaylistModel(val d: String)
}
