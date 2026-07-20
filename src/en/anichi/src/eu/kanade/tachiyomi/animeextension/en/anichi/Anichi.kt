package eu.kanade.tachiyomi.animeextension.en.anichi

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.dto.ResultResponse
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Anichi :
    AnikotoTheme(
        "en",
        "Anichi",
        domainEntries = listOf(
            "anichi.to",
        ),
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"),
    ) {

    private val json: Json by injectLazy()

    private val prefMarkFiller get() = preferences.getBoolean(PREF_MARK_FILLER_KEY, true)
    private val prefHideFiller get() = preferences.getBoolean(PREF_HIDE_FILLER_KEY, false)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val newDocument = resolveSearchAnime(document)
        val titleElement = newDocument.selectFirst("h1.series-title, h1.media-title, h1.title, h2.title, h1")
        val animeId = newDocument.selectFirst("[data-id]")?.attr("data-id")
            ?: newDocument.selectFirst("[data-tip]")?.attr("data-tip")

        return SAnime.create().apply {
            setUrlWithoutDomain(newDocument.location())
            if (!animeId.isNullOrBlank()) url += "#$animeId"
            titleElement?.let { getTitle(it) }?.takeIf { it.isNotEmpty() }?.let { title = it }

            genre = newDocument.select("main a[href*='/genre/'], section a[href*='/genre/'], .series-facts a[href*='/genre/'], div:contains(Genres) > span > a")
                .map { it.text().cleanMeta() }
                .filter { it.isNotBlank() && !it.equals("all", true) }
                .distinct()
                .joinToString()

            author = newDocument.select("main a[href*='/studio/'], .series-fact:contains(Studios) a, .series-fact:contains(Studio) a, div:contains(Studios) > span > a")
                .map { it.text().cleanMeta() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()

            val rawStatus = newDocument.select(".series-pill:contains(Airing), .series-pill:contains(Finished), .series-fact:contains(Status) .series-fact__value, div:contains(Status) > span").text().cleanMeta()
            status = parseStatus(rawStatus)

            description = buildDescription(newDocument, titleElement)

            val posterImg = newDocument.selectFirst(".series-intro__poster img, section#media-info .media-info-poster img, section#w-info div.poster img, div.poster img, img.media-poster")
            posterImg?.let { img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }
                if (url.isNotEmpty()) thumbnail_url = url
            }
        }
    }

    override fun buildDescription(document: Document, titleElement: Element?): String = buildString {
        val enTitle = titleElement?.text()?.cleanMeta()?.takeIf { it.isNotEmpty() }
        val jpTitle = titleElement?.attr("data-jp")?.cleanMeta()?.takeIf { it.isNotEmpty() }

        // 1) Star Rating from MAL
        val malScore = document.select(".series-score b, .media-rating b, div.meta > div:contains(MAL) span").text().cleanMeta().takeIf { it.isNotEmpty() }
        val fancyScore = getFancyScore(malScore)
        if (fancyScore.isNotEmpty()) {
            appendLine(fancyScore).appendLine()
        }

        // 2) Full Untruncated Description / Synopsis
        val fullSynopsis = document.selectFirst("div.series-blurb__full, div.synopsis-full, .synopsis-full")?.text()?.cleanMeta()
            ?: document.selectFirst(synopsisContentSelector)?.text()?.cleanMeta()
        fullSynopsis?.takeIf { it.isNotEmpty() }?.let {
            appendLine(it).appendLine()
        }

        // 3) Meta line (Type, Season, Duration, Rating) - Cleaned single-element extraction
        val type = document.selectFirst(".series-fact:contains(Type) .series-fact__value")?.text()?.cleanMeta()
            ?: document.selectFirst(".series-pill:matches(^TV$|^Movie$|^OVA$|^ONA$|^Special$)")?.text()?.cleanMeta()

        val season = document.selectFirst(".series-fact:contains(Premiered) .series-fact__value, .series-fact:contains(Season) .series-fact__value")?.text()?.cleanMeta()

        val duration = document.selectFirst(".series-fact:contains(Duration) .series-fact__value")?.text()?.cleanMeta()
            ?: document.selectFirst(".series-pill:contains(min)")?.text()?.cleanMeta()

        val rating = document.selectFirst(".series-fact:contains(Rating) .series-fact__value")?.text()?.cleanMeta()
            ?: document.selectFirst(".series-pill:matches(^PG|^R-|^G$|^Rx$)")?.text()?.cleanMeta()

        val metaPills = mutableListOf<String>()
        if (!type.isNullOrBlank()) metaPills.add("Type: $type")
        if (!season.isNullOrBlank()) metaPills.add("Season: $season")
        if (!duration.isNullOrBlank()) metaPills.add("Duration: $duration")
        if (!rating.isNullOrBlank()) metaPills.add("Rating: $rating")

        if (metaPills.isNotEmpty()) {
            appendLine(metaPills.joinToString(" | ")).appendLine()
        }

        // 4) Date Aired
        val aired = document.selectFirst(".series-fact:contains(Aired) .series-fact__value, .meta-row:contains(Aired) .meta-value")?.text()?.cleanMeta()
        if (!aired.isNullOrBlank()) {
            appendLine("Aired: $aired").appendLine()
        }

        // 5) Studio & Producers
        val studios = document.select(".series-fact:contains(Studios) a, div:contains(Studios) > span > a").joinToString { it.text().cleanMeta() }
        val producers = document.select(".series-fact:contains(Producers) a, div:contains(Producers) > span > a").joinToString { it.text().cleanMeta() }
        when {
            studios.isNotEmpty() && producers.isNotEmpty() -> appendLine("**Studio:** $studios (**Producers:** $producers)").appendLine()
            studios.isNotEmpty() -> appendLine("**Studio:** $studios").appendLine()
            producers.isNotEmpty() -> appendLine("**Producers:** $producers").appendLine()
        }

        // 6) Alternative titles
        val altNames = mutableListOf<String>()
        if (useEnglish()) jpTitle?.let { altNames.add(it) } else enTitle?.let { altNames.add(it) }
        document.selectFirst(aliasContainerSelector)?.text()?.cleanMeta()?.takeIf { it.isNotEmpty() }?.let { namesText ->
            altNames.addAll(namesText.split(";").map { it.cleanMeta() }.filter { it.isNotEmpty() && it != jpTitle && it != enTitle })
        }
        if (altNames.isNotEmpty()) {
            appendLine("**Alternative titles:** ${altNames.joinToString()}").appendLine()
        }

        // 7) External Links (MAL, AniList, Kitsu, Trailer)
        val malId = document.selectFirst("[data-mal-id]")?.attr("data-mal-id")
        val anilistId = document.selectFirst("[data-anilist-id]")?.attr("data-anilist-id")
        val kitsuId = document.selectFirst("[data-kitsu-id]")?.attr("data-kitsu-id")
        val trailerUrl = document.selectFirst("a[href*='youtube.com/watch'], a[href*='youtu.be']")?.attr("href")

        val extLinks = mutableListOf<String>()
        malId?.takeIf { it.isNotBlank() && it != "0" }?.let { extLinks.add("[MAL](https://myanimelist.net/anime/$it)") }
        anilistId?.takeIf { it.isNotBlank() && it != "0" }?.let { extLinks.add("[AniList](https://anilist.co/anime/$it)") }
        kitsuId?.takeIf { it.isNotBlank() && it != "0" }?.let { extLinks.add("[Kitsu](https://kitsu.app/anime/$it)") }
        trailerUrl?.takeIf { it.isNotBlank() }?.let { extLinks.add("[Trailer]($it)") }

        if (extLinks.isNotEmpty()) {
            appendLine("**Links:** " + extLinks.joinToString(" | "))
        }
    }.trim()

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer")
        if (referer.isNullOrBlank()) return emptyList()
        val animeUrl = try {
            referer.toHttpUrl().encodedPath
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val bodyString = response.body.string()
            val resultDto = json.decodeFromString<ResultResponse>(bodyString)
            resultDto.toDocument().select(episodeListSelector())
                .mapNotNull { element ->
                    val title = element.parent()?.attr("title") ?: ""
                    val name = element.parent()?.select("span.d-title")?.text().orEmpty().cleanMeta()
                    val isFiller = element.hasClass("filler") ||
                        element.parent()?.hasClass("filler") == true ||
                        element.attr("data-filler") == "1" ||
                        title.contains("filler", true) ||
                        name.contains("filler", true)

                    if (isFiller && prefHideFiller) {
                        null
                    } else {
                        episodeFromElement(element, animeUrl, isFiller)
                    }
                }
                .reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun episodeFromElement(element: Element, animeUrl: String, isFiller: Boolean): SEpisode {
        val title = element.parent()?.attr("title") ?: ""
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toIntOrNull() == 1) "Sub" else ""
        val dub = if (element.attr("data-dub").toIntOrNull() == 1) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.containsMatchIn(title)) "SoftSub" else ""
        val name = element.parent()?.select("span.d-title")?.text().orEmpty().cleanMeta()

        val malId = element.attr("data-mal")
        val slug = element.attr("data-slug")
        val timestamp = element.attr("data-timestamp")

        val epName = "Episode $epNum" + if (name.isNotEmpty() && name != "Episode $epNum") ": $name" else ""

        return SEpisode.create().apply {
            this.name = if (isFiller && prefMarkFiller) "[Filler] $epName" else epName
            this.url = buildString {
                append("$ids&epurl=${EP_URL_SUFFIX_REGEX.replace(animeUrl, "")}/ep-$epNum")
                if (malId.isNotEmpty()) append("&mal=$malId")
                if (slug.isNotEmpty()) append("&slug=$slug")
                if (timestamp.isNotEmpty()) append("&ts=$timestamp")
            }
            episode_number = epNum.toFloatOrNull() ?: 0f
            date_upload = DATE_FORMATTER.tryParse(RELEASE_REGEX.find(title)?.groupValues?.get(1))
            scanlator = listOf(sub, softSub, dub).filter(String::isNotBlank).joinToString()
        }
    }

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLER_KEY
            title = "Mark Filler Episodes"
            summary = "Label filler episodes with [Filler] prefix (requires app restart)"
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_FILLER_KEY
            title = "Hide Filler Episodes"
            summary = "Hide filler episodes from the episode list (requires app restart)"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private fun String.cleanMeta(): String = this
        .replace("&nbsp;", " ")
        .replace("&nbsp", " ")
        .replace("\u00a0", " ")
        .trim()

    private fun SimpleDateFormat.tryParse(date: String?): Long = date?.let {
        try {
            parse(it)?.time
        } catch (_: Exception) {
            null
        }
    } ?: 0L

    companion object {
        private val SOFTSUB_REGEX = Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_REGEX = Regex("""Release: (\d+/\d+/\d+ \d+:\d+)""")
        private val DATE_FORMATTER = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)

        private const val PREF_MARK_FILLER_KEY = "pref_mark_filler"
        private const val PREF_HIDE_FILLER_KEY = "pref_hide_filler"
    }
}
