package eu.kanade.tachiyomi.animeextension.en.dramafull

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class DramaFull :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "DramaFull"

    override val baseUrl = "https://dramafull.cc"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiJson = Json(from = json) {
        encodeDefaults = true
        explicitNulls = false
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val payload = getFilterPayload(page = page, sort = DramaFullFilters.SORT_MOST_WATCHED)
        val body = payload.toRequestBody(apiJson)
        return POST("$baseUrl/api/filter", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<FilterResponse>(json)
        val animes = parsed.data.map { it.toSAnime(baseUrl) }
        val hasNextPage = parsed.nextPageUrl != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val payload = getFilterPayload(page = page, sort = DramaFullFilters.SORT_RECENTLY_UPDATED)
        val body = payload.toRequestBody(apiJson)
        return POST("$baseUrl/api/filter", headers, body)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val payload = getFilterPayload(
            page = page,
            query = query,
            filters = filters,
            sort = if (query.isNotBlank()) {
                DramaFullFilters.SORT_NAME_AZ
            } else {
                DramaFullFilters.SORT_RECENTLY_ADDED
            },
        )
        val body = payload.toRequestBody(apiJson)
        return POST("$baseUrl/api/filter", headers, body)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun getFilterPayload(
        page: Int,
        query: String = "",
        filters: AnimeFilterList = AnimeFilterList(),
        sort: Int,
    ): FilterPayload {
        val rating = filters.firstInstanceOrNull<DramaFullFilters.AdultFilter>()?.getValue() ?: preferences.defaultAdultRating
        return FilterPayload(
            page = page,
            keyword = query.trim(),
            type = filters.firstInstanceOrNull<DramaFullFilters.TypeFilter>()?.getValue() ?: -1,
            country = filters.firstInstanceOrNull<DramaFullFilters.CountryFilter>()?.getValue() ?: -1,
            sort = filters.firstInstanceOrNull<DramaFullFilters.SortFilter>()?.getValue().takeIf { it != DramaFullFilters.SORT_DEFAULT } ?: sort,
            genres = listOf(0) + (
                filters.firstInstanceOrNull<DramaFullFilters.GenreFilter>()
                    ?.state
                    ?.filter { it.state }
                    ?.map { it.id }
                    ?: emptyList()
                ),
            adult = rating != DramaFullFilters.RATING_NON_ADULT,
            adultOnly = rating == DramaFullFilters.RATING_ADULT_ONLY,
        )
    }

    // ============================== Details ===============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.film-name")?.text()!!
            thumbnail_url = document.selectFirst("div.cover-image")
                ?.attr("style")
                ?.substringAfter("url('", "")
                ?.substringBefore("')", "")
                ?.let { UrlUtils.fixUrl(it, baseUrl) }

            genre = document.select("div.genre-list a.genre").joinToString { it.text() }

            // Status: A movie has NO "div#episode-section"
            val isMovie = document.selectFirst("div#episode-section") == null
            status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING

            val descriptionText = document.selectFirst("p.summary-content")?.text()
            val releaseDate = document.selectFirst("div.released-at")
                ?.text()
                ?.substringAfter("Released at:")
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val altName = document.selectFirst("h2.fst-italic")?.text()
                ?.replace(",", ", ")
            val countries = document.select(".info-wrapper .country-list img")
                .mapNotNull {
                    it.attr("alt")
                        .split("-")
                        .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                        .takeIf(String::isNotBlank)
                }
                .joinToString()

            description = listOfNotNull(
                descriptionText?.takeIf(String::isNotBlank),
                releaseDate?.let { "**Released at:** $it" },
                altName?.takeIf(String::isNotBlank)?.let { "**Alternative Name(s):**\n$it" },
                countries.takeIf(String::isNotBlank)?.let { "**Country(s):** $it" },
            ).joinToString("\n\n")
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select("div#latest-release div.flw-item")
            .mapNotNull { element ->
                val a = element.selectFirst("a.film-poster-ahref") ?: return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(a.attr("href"))
                    title = a.attr("title")
                    thumbnail_url = element.selectFirst("img.film-poster-img")?.let {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }?.let { UrlUtils.fixUrl(it, baseUrl) }
                }
            }
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        DramaFullFilters.TypeFilter(),
        DramaFullFilters.CountryFilter(),
        DramaFullFilters.SortFilter(),
        DramaFullFilters.AdultFilter(
            DramaFullFilters.ADULT_FILTER.map { it.second }
                .indexOf(preferences.defaultAdultRating).takeIf { it != -1 } ?: 1, // Default: 18+ included
        ),
        AnimeFilter.Separator(),
        DramaFullFilters.GenreFilter(),
        AnimeFilter.Separator(),
    )

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Try to parse as TV Show first
        val episodeList = document.select("div#episode-section div.episode-item a.btn-play")
        if (episodeList.isNotEmpty()) {
            return episodeList.mapNotNull { element ->
                val epText = element.text()
                val epNumStr = epText.substringBefore("(").trim()
                val epNum = epNumStr.toFloatOrNull()

                SEpisode.create().apply {
                    setUrlWithoutDomain(element.attr("href"))
                    name = "Episode $epNumStr"
                    epNum?.let { episode_number = it }
                    scanlator = epText.substringAfter("(").substringBefore(")").trim() // "SUB" or "RAW"
                }
            }.reversed()
        }

        // Fallback to Movie
        val movieLink = document.selectFirst("div.last-episode a[href*=\"/watch/\"]")
            ?: document.selectFirst("a.btn-play[href*=\"/watch/\"]") // Fallback selector

        if (movieLink != null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(movieLink.attr("href"))
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }
        return emptyList()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // Get watch page
        val watchPage = client.newCall(videoListRequest(episode)).awaitSuccess().asJsoup()

        // Get backend player URL
        val backendUrl = watchPage.selectFirst("div#base-url[baseUrl]")
            ?.attr("baseUrl")
            ?: throw Exception("Could not find backend URL")

        val backendPage = client.newCall(GET(backendUrl, headers)).awaitSuccess().body.string()

        // Get signed API URL (and clean it)
        val signedUrl = SIGNED_URL_REGEX.find(backendPage)?.destructured?.let { (url) ->
            url.replace("\\/", "/") // Fix escaped slashes
        } ?: throw Exception("Could not find signed URL. Cloudflare?")

        // Get video JSON (use the cleaned, absolute signedUrl)
        val videoData = client.newCall(GET(signedUrl, headers)).awaitSuccess()
            .parseAs<VideoResponse>(json)

        // Extract subtitles
        val subtitles = videoData.sub?.let { subElement ->
            if (subElement is JsonObject) {
                try {
                    val subtitleMap = json.decodeFromJsonElement<Map<String, List<String>>>(subElement)
                    subtitleMap.values.flatten().distinct().map { subUrl ->
                        Track(UrlUtils.fixUrl(subUrl, baseUrl), "English")
                    }
                } catch (_: Exception) {
                    emptyList() // Failed to parse, treat as no subs
                }
            } else {
                emptyList() // If a JsonArray (like "[]") or something else
            }
        } ?: emptyList()

        // Extract videos
        val videoList = videoData.videoSource.mapNotNull { (quality, videoUrl) ->
            if (videoUrl.isNullOrBlank()) return@mapNotNull null
            Video(videoUrl, "DramaFull ${quality}p", videoUrl, subtitleTracks = subtitles)
        }

        return videoList.sortedByDescending {
            QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    // ============================= Utilities ==============================

    /** Value of the adult rating filter, not the index. */
    private val SharedPreferences.defaultAdultRating
        get() = getString(PREF_DEFAULT_RATING, DramaFullFilters.RATING_ADULT_INCLUDED.toString())
            ?.toIntOrNull() ?: DramaFullFilters.RATING_ADULT_INCLUDED

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DEFAULT_RATING,
            title = "Default Adult Content Filter",
            entries = DramaFullFilters.ADULT_FILTER.map { it.first },
            entryValues = DramaFullFilters.ADULT_FILTER.map { it.second.toString() },
            default = DramaFullFilters.RATING_ADULT_INCLUDED.toString(),
            summary = "%s",
        )
    }

    @Serializable
    private data class FilterPayload(
        val page: Int,
        val type: Int = -1,
        val country: Int = -1,
        val sort: Int,
        val adult: Boolean = true,
        val adultOnly: Boolean = false,
        val ignoreWatched: Boolean = false,
        val genres: List<Int> = listOf(0),
        val keyword: String = "",
    )

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    companion object {
        private val SIGNED_URL_REGEX = Regex("""window\.signedUrl\s*=\s*"([^"]+)"""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_DEFAULT_RATING = "pref_default_rating"
    }
}
