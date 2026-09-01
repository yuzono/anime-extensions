package eu.kanade.tachiyomi.animeextension.it.hentaisaturn

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.io.encoding.Base64

class HentaiSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiSaturn"

    override val lang = "it"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        val currentDomain = getString(PREF_DOMAIN, DOMAIN_DEFAULT)!!
        if (currentDomain !in DOMAIN_VALUES) {
            edit()
                .putString(PREF_DOMAIN, DOMAIN_DEFAULT)
                .apply()
        }
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN, DOMAIN_DEFAULT)

    override fun popularAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing/$page")

    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "a.ep-tile"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(
            element.attr("href")
                .replace("episode/", "hentai/")
                .replace("watch/", "stream/"),
        )
        val fallbackName = element.attr("title")
        episode.episode_number = fallbackName.substringAfter("Episodio ").toFloatOrNull() ?: 0f
        episode.name = fallbackName

        val episodeResponse = client.newCall(GET(baseUrl + episode.url)).execute()
        if (!episodeResponse.isSuccessful) {
            episodeResponse.close()
            return episode
        }

        val tvEpisode = episodeResponse.asJsoup()
            .head()
            .selectFirst("script[type=\"application/ld+json\"]:containsData(\"TVEpisode\")")
            ?.data()
            ?.let { runCatching { it.parseAs<TVEpisodeLD>() }.getOrNull() }
        episodeResponse.close()

        if (tvEpisode == null) return episode

        tvEpisode.datePublished
            ?.takeIf { it.isNotEmpty() }
            ?.let { episode.date_upload = dateFormat.tryParse(it) }

        tvEpisode.episodeNumber?.let { episode.episode_number = it }

        val seriesName = tvEpisode.partOfSeries?.name
        episode.name = tvEpisode.name
            ?.let { name -> seriesName?.let { name.substringAfter(it).trim() } ?: name }
            ?.ifEmpty { fallbackName }
            ?: fallbackName

        return episode
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

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

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        if (response.code != 200) {
            return emptyList()
        }

        val token = response.request.url.queryParameter("token") ?: return emptyList()
        val playlistModel = response.parseAs<PlaylistModel>()
        val videoUrl = decodeUrl(playlistModel.d, token)
        if (videoUrl.contains(".mp4")) {
            return listOf(
                Video(
                    videoUrl,
                    "Qualità predefinita",
                    videoUrl,
                ),
            )
        }

        return playlistUtils.extractFromHls(videoUrl)
    }

    private fun decodeUrl(url: String, token: String): String {
        val base = Base64.decode(url).decodeToString()
        val builder = StringBuilder()
        for (i in base.indices) {
            builder.append(base[i].code.xor(token[i % token.length].code).toChar())
        }
        return builder.toString()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val url = episodePage.selectFirst("iframe")!!.attr("src")
        return Request.Builder()
            .url(url.replace("?", "/playlist?"))
            .header("Referer", url)
            .build()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val prQuality = preferences.getString(PREF_QUALITY, QUALITY_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.quality.contains(prQuality) },
        )
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.selectFirst("h3")!!.text()
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun searchAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return GET("$baseUrl/filter/$page?key=$query$parameters")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")!!.text()
        anime.author = document.selectFirst("div a[href*=studios]")?.text()
        val statusText = document.selectFirst("div a[href*=states]")?.text().orEmpty()
        anime.status = parseStatus(statusText)
        if (statusText == "Finito" || statusText == "Droppato") {
            anime.update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        } else {
            anime.update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
        }
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        val alterTitle = document.selectFirst("div.text-center > p.mt-1")?.text().orEmpty()
            .replace(Regex("\\(\\w+\\)"), "").trim()
        val descriptionText = document.selectFirst("section:has(h2) div")?.text()?.trim().orEmpty()
        val typeText = document.selectFirst("div:nth-of-type(1) > dd")?.text()?.trim().orEmpty()
        val releaseText = document.selectFirst("div:nth-of-type(5) > dd")?.text()?.trim().orEmpty()
        val langText = document.selectFirst("div:nth-of-type(3) > dd > a")?.text()?.trim().orEmpty()
        val durationText = document.selectFirst("div:nth-of-type(7) > dd")?.text()?.trim().orEmpty()
        val viewsText = document.selectFirst("div:nth-of-type(8) > dd")?.text()?.trim().orEmpty()
        val voteText = document.selectFirst("#hentai-score")?.text()?.trim().orEmpty()
        val votersText = document.selectFirst("#hentai-votes")?.text()?.trim().orEmpty()

        anime.description = buildString {
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
        statusString.contains("Non rilasciato") -> SAnime.UNKNOWN
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesSelector(): String = "a.group[href]:not(.flex)"

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page")

    override fun latestUpdatesNextPageSelector(): String = "a[rel=\"next\"]"

    // Filters
    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)

    private fun getGenres() = listOf(
        Genre("1", "3D"),
        Genre("2", "Ahegao"),
        Genre("3", "Anal"),
        Genre("4", "BDSM"),
        Genre("5", "Big Boobs"),
        Genre("6", "Blow Job"),
        Genre("7", "Bondage"),
        Genre("8", "Boob Job"),
        Genre("9", "Censored"),
        Genre("10", "Comedy"),
        Genre("11", "Cosplay"),
        Genre("12", "Creampie"),
        Genre("13", "Dark Skin"),
        Genre("14", "Facial"),
        Genre("15", "Fantasy"),
        Genre("16", "Filmed"),
        Genre("17", "Foot Job"),
        Genre("18", "Futanari"),
        Genre("19", "Gangbang"),
        Genre("20", "Glasses"),
        Genre("21", "Hand Job"),
        Genre("22", "Harem"),
        Genre("25", "Horror"),
        Genre("26", "Incest"),
        Genre("27", "Inflation"),
        Genre("29", "Lactation"),
        Genre("30", "Loli"),
        Genre("31", "Maid"),
        Genre("32", "Masturbation"),
        Genre("33", "Milf"),
        Genre("34", "Mind Break"),
        Genre("35", "Mind Control"),
        Genre("36", "Monster"),
        Genre("37", "NTR"),
        Genre("38", "Nurse"),
        Genre("39", "Orgy"),
        Genre("40", "Plot"),
        Genre("41", "POV"),
        Genre("42", "Pregnant"),
        Genre("43", "Public Sex"),
        Genre("44", "Rape"),
        Genre("45", "Reverse Rape"),
        Genre("46", "Rimjob"),
        Genre("48", "Scat"),
        Genre("49", "School Girl"),
        Genre("51", "Shota"),
        Genre("52", "Softcore"),
        Genre("53", "Swimsuit"),
        Genre("54", "Teacher"),
        Genre("55", "Tentacle"),
        Genre("56", "Threesome"),
        Genre("57", "Toys"),
        Genre("58", "Trap"),
        Genre("59", "Tsundere"),
        Genre("60", "Ugly Bastard"),
        Genre("61", "Uncensored"),
        Genre("62", "Vanilla"),
        Genre("63", "Virgin"),
        Genre("64", "Watersports"),
        Genre("65", "X-Ray"),
        Genre("66", "Yaoi"),
        Genre("67", "Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)

    private fun getYears(): List<Year> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (1960..currentYear).map { Year(it.toString()) }
    }

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)

    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Type(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TypeList(types: List<Type>) : AnimeFilter.Group<Type>("Tipo", types)

    private fun getTypes() = listOf(
        Type("3", "OVA"),
        Type("4", "Special"),
        Type("5", "ONA"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(langs: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", langs)

    private fun getLangs() = listOf(
        Lang("jp", "Giapponese"),
        Lang("it", "Italiano"),
        Lang("en", "Inglese"),
        Lang("kr", "Coreano"),
        Lang("ch", "Cinese"),
    )

    internal class Subs(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SubsList(subs: List<Subs>) : AnimeFilter.Group<Subs>("Sottotitoli", subs)

    private fun getSubs() = listOf(
        Subs("0", "Sottotitolato"),
        Subs("1", "Doppiato"),
    )

    internal class Order(val id: String, name: String) : AnimeFilter.CheckBox(name) {
        override fun toString(): String = name
    }

    private class OrderList(sorts: Array<Order>) : AnimeFilter.Select<Order>("Ordina per", sorts)

    private fun getOrder() = arrayOf(
        Order("standard", "Standard"),
        Order("recent", "Ultime aggiunte"),
        Order("az", "Lista A-Z"),
        Order("za", "Lista Z-A"),
        Order("oldest", "Più vecchi (anno)"),
        Order("newest", "Più recenti (anno)"),
        Order("most_viewed", "Più visti"),
        Order("least_viewed", "Meno visti"),
        Order("best_rated", "Meglio valutati"),
        Order("worst_rated", "Peggio valutati"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderList(getOrder()),
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        TypeList(getTypes()),
        LangList(getLangs()),
        SubsList(getSubs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalString = ""
        var variantGenre = 0
        var variantState = 0
        var variantType = 0
        var variantYear = 0
        var variantLang = 0
        var variantSub = 0
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            totalString = totalString + "&categories%5B" + variantGenre.toString() + "%5D=" + genre.id
                            variantGenre++
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { year ->
                        if (year.state) {
                            totalString = totalString + "&years%5B" + variantYear.toString() + "%5D=" + year.id
                            variantYear++
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { state ->
                        if (state.state) {
                            totalString = totalString + "&states%5B" + variantState.toString() + "%5D=" + state.id
                            variantState++
                        }
                    }
                }

                is TypeList -> { // ---Type
                    filter.state.forEach { type ->
                        if (type.state) {
                            totalString = totalString + "&types%5B" + variantType.toString() + "%5D=" + type.id
                            variantType++
                        }
                    }
                }

                is LangList -> { // ---Lang
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            totalString = totalString + "&languages%5B" + variantLang.toString() + "%5D=" + lang.id
                            variantLang++
                        }
                    }
                }

                is SubsList -> { // ---Subs
                    filter.state.forEach { subs ->
                        if (subs.state) {
                            totalString = totalString + "&subtitles%5B" + variantSub.toString() + "%5D=" + subs.id
                            variantSub++
                        }
                    }
                }

                is OrderList -> { // ---Sorts
                    val order = filter.values[filter.state]
                    totalString = totalString + "&sort=" + order.id
                }

                else -> {}
            }
        }
        return totalString
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

        screen.addListPreference(
            key = PREF_DOMAIN,
            title = "Dominio in Uso (Riavvio dell'App Richiesto)",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = DOMAIN_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()

        private const val PREF_DOMAIN = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("hentaisaturn.tv", "hentaisaturn.com")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val DOMAIN_DEFAULT = DOMAIN_VALUES.first()
    }

    @Serializable
    data class PlaylistModel(val d: String, val p: String, val t: String)
}
