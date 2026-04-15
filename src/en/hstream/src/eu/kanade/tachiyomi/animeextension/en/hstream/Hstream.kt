package eu.kanade.tachiyomi.animeextension.en.hstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream :
	ParsedAnimeHttpSource(),
	ConfigurableAnimeSource {

	override val name = "Hstream"

	override val baseUrl = "https://hstream.moe"

	override val lang = "en"

	override val supportsLatest = true

	private val json: Json by injectLazy()

	// URLs from the old extension are invalid now, so we're bumping this to
	// make aniyomi interpret it as a new source, forcing old users to migrate.
	override val versionId = 2

	private val preferences by getPreferencesLazy()

	// ============================== Popular ===============================
	override fun popularAnimeRequest(page: Int) = GET("$baseUrl/search?order=view-count&page=$page")

	override fun popularAnimeSelector() = "div.items-center div.w-full > a"

override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val href = element.attr("href")
        setUrlWithoutDomain(getSeriesBaseUrl(href))
        title = element.selectFirst("img")?.attr("alt")
            ?.replace(SERIES_REGEX, "")
            ?: "Unknown"
        thumbnail_url = "$baseUrl/images$url/cover-ep-1.webp"
    }

	override fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.title }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animeList, hasNextPage)
    }

    /**
     * Parse search results without deduplication.
     * Returns raw entries where each episode appears as a separate result.
     */
    private fun parseSearchResultsRaw(document: Document): AnimesPage {
        val animeList = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animeList, hasNextPage)
    }

	// =============================== Latest ===============================
	override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search?order=recently-uploaded&page=$page")

	override fun latestUpdatesSelector() = popularAnimeSelector()

	override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

	override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

	override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

	// =============================== Search ===============================
	override fun getFilterList() = HstreamFilters.FILTER_LIST

	override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
		val id = query.removePrefix(PREFIX_SEARCH)
		client.newCall(GET("$baseUrl/hentai/$id"))
			.awaitSuccess()
			.use(::searchAnimeByIdParse)
	} else {
		super.getSearchAnime(page, query, filters)
	}

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.encodedPath)
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

	override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
		val params = HstreamFilters.getSearchParameters(filters)

		val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
			if (query.isNotBlank()) addQueryParameter("search", query)
			addQueryParameter("page", page.toString())
			addQueryParameter("order", params.order)
			params.genres.forEachIndexed { index, genre -> addQueryParameter("tags[$index]", genre) }
			params.blacklisted.forEach { addQueryParameter("blacklist[]", it) }
			params.studios.forEach { addQueryParameter("studios[]", it) }
		}.build()

		return GET(url.toString())
	}

	override fun searchAnimeSelector() = popularAnimeSelector()

	override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

	override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val groupSeries = preferences.getBoolean(PREF_GROUP_SERIES_KEY, PREF_GROUP_SERIES_DEFAULT)
        return if (groupSeries) {
            // Group episodes under one series entry (deduplicated)
            popularAnimeParse(response)
        } else {
            // Each episode is a separate entry (raw results)
            parseSearchResultsRaw(response.asJsoup())
        }
    }

	// =========================== Anime Details ============================
	override fun animeDetailsParse(document: Document) = SAnime.create().apply {
		status = SAnime.COMPLETED

		// Safe nullable access - always enabled (Law of Early Exit)
		val floatleft = document.selectFirst("div.relative > div.justify-between > div")
		val titleElement = floatleft?.selectFirst("div > h1")

		title = titleElement?.text() ?: ""
		artist = floatleft?.select("div > a:nth-of-type(3)")?.text() ?: ""

		thumbnail_url = document.selectFirst("div.float-left > img.object-cover")?.absUrl("src")

		// Improved parsing with better selectors and null safety - always enabled
		genre = document.select("ul.list-none > li > a, div.flex-wrap a[href*='/tags/']").eachText().joinToString()

		description = document.selectFirst("div.relative > p.leading-tight, div.prose p")?.text()
			?: document.selectFirst("div[class*='description']")?.text()
			?: ""
	}

	// ============================== Episodes ==============================
	override fun episodeListRequest(anime: SAnime): Request {
		val seriesUrl = getSeriesBaseUrl(anime.url)
		return GET("$baseUrl$seriesUrl-1/")
	}

    override fun episodeListParse(response: Response): List<SEpisode> {
        val currentUrl = response.request.url.encodedPath
        val seriesSlug = getSeriesBaseUrl(currentUrl).removePrefix("/hentai/")
        val groupSeries = preferences.getBoolean(PREF_GROUP_SERIES_KEY, PREF_GROUP_SERIES_DEFAULT)

        // When grouping is OFF, each entry is a single episode - no series page fetch needed
        if (!groupSeries) {
            return parseEpisodesFromEpisodePage(response.asJsoup(), currentUrl)
        }

        // When grouping is ON, try fetching the series page for episode list (1 request instead of up to 50)
        return try {
            val seriesResponse = client.newCall(GET("$baseUrl/hentai/$seriesSlug/")).execute()
            if (seriesResponse.isSuccessful) {
                parseEpisodesFromSeriesPage(seriesResponse.asJsoup())
            } else {
                seriesResponse.close()
                parseEpisodesFromEpisodePage(response.asJsoup(), currentUrl)
            }
        } catch (e: Exception) {
            // Fallback on any error
            parseEpisodesFromEpisodePage(response.asJsoup(), currentUrl)
        }
    }

	private fun parseEpisodesFromSeriesPage(doc: Document): List<SEpisode> {
		val episodes = mutableListOf<SEpisode>()

		// Find episode links in the grid - look for div.grid containing episode links
		doc.select("div.grid > div > a").forEach { link ->
			val href = link.attr("href")
			val epNum = SERIES_EPISODE_REGEX.find(href)?.let { match ->
				match.groupValues[1].toIntOrNull()
			} ?: return@forEach

			episodes.add(SEpisode.create().apply {
				setUrlWithoutDomain(href)
				episode_number = epNum.toFloat()
				name = "Episode $epNum"
			})
		}

		// Sort by episode number (ascending)
		val sortedEpisodes = episodes.sortedBy { it.episode_number }

		// Apply episode reversal based on preference (default: newest first)
		val reverseEpisodes = preferences.getBoolean(PREF_REVERSE_EPISODES_KEY, PREF_REVERSE_EPISODES_DEFAULT)
		return if (reverseEpisodes) sortedEpisodes.reversed() else sortedEpisodes
	}

	private fun parseEpisodesFromEpisodePage(doc: Document, currentUrl: String): List<SEpisode> {
		// Extract episode number from current URL
		val epNum = SERIES_EPISODE_REGEX.find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
		val seriesPath = getSeriesBaseUrl(currentUrl)

		return listOf(SEpisode.create().apply {
			setUrlWithoutDomain("$seriesPath-$epNum/")
			episode_number = epNum.toFloat()
			name = "Episode $epNum"
			date_upload = doc.selectFirst("a:has(i.fa-upload)")?.ownText().toDate()
		})
	}

	override fun episodeListSelector(): String = throw UnsupportedOperationException()

	override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

// ============================ Video Links =============================
	override fun videoListParse(response: Response): List<Video> {
		val doc = response.asJsoup()

val token = client.cookieJar.loadForRequest(response.request.url)
            .firstOrNull { it.name.equals("XSRF-TOKEN") }?.value
            ?: throw Exception("XSRF-TOKEN cookie not found")

        val episodeId = doc.selectFirst("input#e_id")?.attr("value")
            ?: throw Exception("Episode ID not found on page")

		val newHeaders = headersBuilder().apply {
			set("Referer", doc.location())
			set("Origin", baseUrl)
			set("X-Requested-With", "XMLHttpRequest")
			set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
		}.build()

		val body = json.encodeToString(EpisodeRequest(episodeId)).toRequestBody("application/json".toMediaType())
		val data = client.newCall(POST("$baseUrl/player/api", newHeaders, body)).execute()
			.parseAs<PlayerApiResponse>()

		val urlBase = data.stream_domains.random() + "/" + data.stream_url
		val subtitleList = listOf(Track("$urlBase/eng.ass", "English"))

		val resolutions = listOfNotNull("720", "1080", if (data.resolution == "4k") "2160" else null)
		return resolutions.map { resolution ->
			val url = urlBase + getVideoUrlPath(data.legacy != 0, resolution)
			Video(url, "${resolution}p", url, subtitleTracks = subtitleList)
		}
	}

	private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String = if (isLegacy) {
		if (resolution.equals("720")) {
			"/x264.720p.mp4"
		} else {
			"/av1.$resolution.webm"
		}
	} else {
		"/$resolution/manifest.mpd"
	}

	@Serializable
	data class EpisodeRequest(val episode_id: String)

	@Serializable
	data class PlayerApiResponse(
		val legacy: Int = 0,
		val resolution: String = "4k",
		val stream_url: String,
		val stream_domains: List<String>,
	)

	override fun videoListSelector(): String = throw UnsupportedOperationException()

	override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

	override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

	// ============================== Settings ==============================
	override fun setupPreferenceScreen(screen: PreferenceScreen) {
		ListPreference(screen.context).apply {
			key = PREF_QUALITY_KEY
			title = PREF_QUALITY_TITLE
			entries = PREF_QUALITY_ENTRIES
			entryValues = PREF_QUALITY_VALUES
			setDefaultValue(PREF_QUALITY_DEFAULT)
			summary = "%s"

setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putString(key, newValue as String).apply()
            true
        }
		}.also(screen::addPreference)

	screen.addSwitchPreference(
		key = PREF_REVERSE_EPISODES_KEY,
		title = "Reverse episode order",
		summary = "Show newest episodes first",
		defaultValue = PREF_REVERSE_EPISODES_DEFAULT,
	)

        screen.addSwitchPreference(
            key = PREF_GROUP_SERIES_KEY,
            title = "Group series episodes",
            summary = "ON: Episodes grouped under one series entry (recommended).\nOFF: Each episode appears as a separate search result.",
            defaultValue = PREF_GROUP_SERIES_DEFAULT,
        )
	}

	// ============================= Utilities ==============================
	private fun getSeriesBaseUrl(url: String): String = url.replace(SERIES_REGEX, "").trimEnd('/')

	private fun String?.toDate(): Long = runCatching { DATE_FORMATTER.parse(orEmpty().trim(' ', '|'))?.time }
		.getOrNull() ?: 0L

override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

	companion object {
		private val DATE_FORMATTER by lazy {
			SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
		}

		const val PREFIX_SEARCH = "id:"

		private val SERIES_REGEX by lazy { Regex("""\s*-\s*\d+/?$""") }
		private val SERIES_EPISODE_REGEX by lazy { Regex("""-(\d+)/?$""") }
		private const val PREF_QUALITY_KEY = "pref_quality_key"
		private const val PREF_QUALITY_TITLE = "Preferred quality"
		private const val PREF_QUALITY_DEFAULT = "720p"
		private val PREF_QUALITY_ENTRIES = arrayOf("720p (HD)", "1080p (FULLHD)", "2160p (4K)")
		private val PREF_QUALITY_VALUES = arrayOf("720p", "1080p", "2160p")
	private const val PREF_REVERSE_EPISODES_KEY = "pref_reverse_episodes_key"
	private const val PREF_REVERSE_EPISODES_DEFAULT = true
	private const val PREF_GROUP_SERIES_KEY = "pref_group_series_key"
	private const val PREF_GROUP_SERIES_DEFAULT = true
	}
}
