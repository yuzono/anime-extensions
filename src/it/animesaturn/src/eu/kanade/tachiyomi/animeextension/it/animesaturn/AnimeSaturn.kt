package eu.kanade.tachiyomi.animeextension.it.animesaturn

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
abstract class AnimeSaturn :
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
            Log.w("AnimeSaturn", "Failed to fetch the latest episode date", e)
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
                .replace("episode/", "anime/")
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
                hosterName = "AnimeSaturn Server",
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
        var studio = document.selectFirst("div a[href*=studios] span.text-brand")?.text().orEmpty()
        if (studio.isEmpty()) {
            studio = document.select("div a[href*=studios]").joinToString { it.text() }
        }
        anime.author = studio
        val statusText = document.selectFirst("div a[href*=states] span.text-brand")?.text().orEmpty()
        anime.status = parseStatus(statusText)
        if (anime.status == SAnime.COMPLETED || anime.status == SAnime.CANCELLED) {
            anime.update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        }
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        var alterTitle = document.selectFirst(".ag-head > .mt-1")?.text().orEmpty()
        if (anime.title == alterTitle) {
            alterTitle = ""
        }
        alterTitle = alterTitle.replace(alterTitleRegex, "")
        val descriptionText = document.selectFirst(".text-pretty")?.text().orEmpty()
        val typeText = document.selectFirst("a:nth-of-type(1) > .flex > .font-medium")?.text().orEmpty()
        val releaseSeasonText = document.selectFirst("a:nth-of-type(2) > .flex > .font-medium")?.text().orEmpty()
        val releaseDateText = document.selectFirst("div:nth-of-type(2) > .font-medium")?.text().orEmpty()
        val langText = document.selectFirst("a:nth-of-type(3) .font-medium")?.text().orEmpty()
        val durationText = document.selectFirst("div:nth-of-type(4) > .font-medium")?.text().orEmpty()
        val viewsText = document.selectFirst("div:nth-of-type(6) > .font-medium")?.text().orEmpty()
        val voteText = document.selectFirst("#anime-score")?.text().orEmpty()
        val votersText = document.selectFirst("#anime-votes")?.text().orEmpty()

        anime.description = buildString {
            if (statusText.isNotEmpty() && statusText !in arrayOf("In corso", "Finito", "Droppato")) append("Attenzione: ${statusText}\n\n")
            if (anime.title.lowercase() != alterTitle.lowercase() && alterTitle.isNotEmpty()) append("Titolo Alternativo: ${alterTitle}\n\n")
            if (langText.isNotEmpty()) append("Lingua: ${langText}\n\n")
            if (descriptionText.isNotEmpty()) append("Descrizione: ${descriptionText}\n\n")
            if (typeText.isNotEmpty()) append("Tipo: ${typeText}\n\n")
            if (releaseSeasonText.isNotEmpty()) append("Stagione: ${releaseSeasonText}\n\n")
            if (releaseDateText.isNotEmpty()) append("Uscita: ${releaseDateText}\n\n")
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
    private class FilterCheckbox(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class CheckboxGroup(
        val key: String,
        title: String,
        options: List<FilterCheckbox>,
    ) : AnimeFilter.Group<FilterCheckbox>(title, options)

    private class FilterSelect(val id: String, private val name: String) {
        override fun toString(): String = name
    }

    private class SelectGroup(
        val key: String,
        title: String,
        options: Array<FilterSelect>,
    ) : AnimeFilter.Select<FilterSelect>(title, options)

    private fun getGenres() = listOf(
        FilterCheckbox("3", "Arti Marziali"),
        FilterCheckbox("5", "Avanguardia"),
        FilterCheckbox("2", "Avventura"),
        FilterCheckbox("1", "Azione"),
        FilterCheckbox("47", "Bambini"),
        FilterCheckbox("4", "Commedia"),
        FilterCheckbox("6", "Demoni"),
        FilterCheckbox("7", "Drammatico"),
        FilterCheckbox("8", "Ecchi"),
        FilterCheckbox("9", "Fantasy"),
        FilterCheckbox("10", "Gioco"),
        FilterCheckbox("11", "Harem"),
        FilterCheckbox("43", "Hentai"),
        FilterCheckbox("13", "Horror"),
        FilterCheckbox("49", "Isekai"),
        FilterCheckbox("14", "Josei"),
        FilterCheckbox("16", "Magia"),
        FilterCheckbox("18", "Mecha"),
        FilterCheckbox("19", "Militari"),
        FilterCheckbox("21", "Mistero"),
        FilterCheckbox("20", "Musicale"),
        FilterCheckbox("22", "Parodia"),
        FilterCheckbox("23", "Polizia"),
        FilterCheckbox("24", "Psicologico"),
        FilterCheckbox("46", "Romantico"),
        FilterCheckbox("26", "Samurai"),
        FilterCheckbox("28", "Sci-Fi"),
        FilterCheckbox("27", "Scolastico"),
        FilterCheckbox("29", "Seinen"),
        FilterCheckbox("25", "Sentimentale"),
        FilterCheckbox("30", "Shoujo"),
        FilterCheckbox("31", "Shoujo Ai"),
        FilterCheckbox("32", "Shounen"),
        FilterCheckbox("33", "Shounen Ai"),
        FilterCheckbox("34", "Slice of Life"),
        FilterCheckbox("37", "Soprannaturale"),
        FilterCheckbox("35", "Spazio"),
        FilterCheckbox("36", "Sport"),
        FilterCheckbox("12", "Storico"),
        FilterCheckbox("38", "Superpoteri"),
        FilterCheckbox("39", "Thriller"),
        FilterCheckbox("40", "Vampiri"),
        FilterCheckbox("48", "Veicoli"),
        FilterCheckbox("41", "Yaoi"),
        FilterCheckbox("42", "Yuri"),
    )

    private fun getYears() = run {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        (1960..currentYear).map { FilterCheckbox(it.toString(), it.toString()) }
    }

    private fun getStates() = listOf(
        FilterCheckbox("0", "In corso"),
        FilterCheckbox("1", "Finito"),
        FilterCheckbox("2", "Non rilasciato"),
        FilterCheckbox("3", "Droppato"),
    )

    private fun getTypes() = listOf(
        FilterCheckbox("1", "TV"),
        FilterCheckbox("2", "Movie"),
        FilterCheckbox("3", "OVA"),
        FilterCheckbox("4", "Special"),
        FilterCheckbox("5", "ONA"),
    )

    private fun getLangs() = listOf(
        FilterCheckbox("jp", "Giapponese"),
        FilterCheckbox("it", "Italiano"),
        FilterCheckbox("fr", "Francese"),
        FilterCheckbox("en", "Inglese"),
        FilterCheckbox("kr", "Coreano"),
        FilterCheckbox("ch", "Cinese"),
    )

    private fun getSubs() = arrayOf(
        FilterSelect("", "Tutti"),
        FilterSelect("0", "Sottotitolato"),
        FilterSelect("1", "Doppiato"),
    )

    private fun getReleaseSeasons() = listOf(
        FilterCheckbox("spring", "Primavera"),
        FilterCheckbox("summer", "Estate"),
        FilterCheckbox("fall", "Autunno"),
        FilterCheckbox("winter", "Inverno"),
        FilterCheckbox("unknown", "Sconosciuta"),
    )

    private fun getOrder() = arrayOf(
        FilterSelect("standard", "Standard"),
        FilterSelect("recent", "Ultime aggiunte"),
        FilterSelect("az", "Lista A-Z"),
        FilterSelect("za", "Lista Z-A"),
        FilterSelect("oldest", "Più vecchi (anno)"),
        FilterSelect("newest", "Più recenti (anno)"),
        FilterSelect("most_viewed", "Più visti"),
        FilterSelect("least_viewed", "Meno visti"),
        FilterSelect("best_rated", "Meglio valutati"),
        FilterSelect("worst_rated", "Peggio valutati"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SelectGroup("sort", "Ordina per", getOrder()),
        CheckboxGroup("categories", "Generi", getGenres()),
        CheckboxGroup("years", "Anno di Uscita", getYears()),
        CheckboxGroup("seasons", "Stagione di Uscita", getReleaseSeasons()),
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
