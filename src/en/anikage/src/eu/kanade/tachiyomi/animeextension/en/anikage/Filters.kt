package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import okhttp3.Headers
import okhttp3.OkHttpClient

object Filters {

    private const val PREF_GENRES = "pref_dynamic_genres"
    private const val PREF_TAGS = "pref_dynamic_tags"
    private const val PREF_FORMATS = "pref_dynamic_formats"
    private const val PREF_STATUSES = "pref_dynamic_statuses"
    private const val PREF_SEASONS = "pref_dynamic_seasons"
    private const val PREF_SORTS = "pref_dynamic_sorts"

    private val APP_REGEX by lazy { Regex("""/_app/immutable/entry/(app\.[a-zA-Z0-9_\-]+\.js)|entry/(app\.[a-zA-Z0-9_\-]+\.js)""") }
    private val BROWSE_NODE_REGEX by lazy { Regex(""""/\(media\)/browse":\[(\d+)""") }
    private val BIFE_CHUNK_REGEX by lazy { Regex("""chunks/([a-zA-Z0-9_\-]*BIFE[a-zA-Z0-9_\-]*\.js)""") }
    private val CHUNK_IMPORT_REGEX by lazy { Regex("""from"\.\./chunks/([^"]+)"""") }

    private val ARRAY_REGEX by lazy { Regex("""\[([^\[\]]+)\]""") }
    private val LABEL_VALUE_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"']""") }
    private val GENRE_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']genres[`"']""") }
    private val TAG_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']tags[`"']""") }

    private val SORT_MAP = mapOf(
        "POPULARITY_DESC" to "popularity",
        "SCORE_DESC" to "score",
        "TRENDING_DESC" to "trending",
        "FAVOURITES_DESC" to "favourites",
        "START_DATE_DESC" to "year",
        "ID_DESC" to "updated",
        "TITLE_ENGLISH" to "title",
    )

    suspend fun fetchFilters(client: OkHttpClient, baseUrl: String, preferences: SharedPreferences, headers: Headers) {
        try {
            val fetchHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build()

            val html = client.get("$baseUrl/browse", fetchHeaders).bodyString()
            val appFile = APP_REGEX.find(html)?.let { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            } ?: return

            val appJs = client.get("$baseUrl/_app/immutable/entry/$appFile", fetchHeaders).bodyString()
            val browseNodeId = BROWSE_NODE_REGEX.find(appJs)?.groupValues?.get(1) ?: return
            val nodeFile = Regex("""nodes/$browseNodeId\.([a-zA-Z0-9_\-]+)\.js""").find(appJs)?.value ?: return

            val nodeJs = client.get("$baseUrl/_app/immutable/$nodeFile", fetchHeaders).bodyString()
            var chunkName = BIFE_CHUNK_REGEX.find(nodeJs)?.groupValues?.get(1)
            if (chunkName == null) {
                val matches = CHUNK_IMPORT_REGEX.findAll(nodeJs)
                for (match in matches) {
                    val candidate = match.groupValues[1]
                    val content = client.get("$baseUrl/_app/immutable/chunks/$candidate", fetchHeaders).bodyString()
                    if (content.contains("genres") && content.contains("tags")) {
                        chunkName = candidate
                        break
                    }
                }
            }
            if (chunkName == null) return

            val chunkJs = client.get("$baseUrl/_app/immutable/chunks/$chunkName", fetchHeaders).bodyString()

            val genres = GENRE_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()
            val tags = TAG_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()

            var formats = emptyList<Pair<String, String>>()
            var seasons = emptyList<Pair<String, String>>()
            var statuses = emptyList<Pair<String, String>>()
            var sorts = emptyList<Pair<String, String>>()

            val arrays = ARRAY_REGEX.findAll(chunkJs)
            for (arr in arrays) {
                val items = LABEL_VALUE_REGEX.findAll(arr.groupValues[1]).map {
                    it.groupValues[1] to it.groupValues[2]
                }.toList()
                if (items.isEmpty()) continue
                val values = items.map { it.second }.toSet()

                if ("WINTER" in values && "SUMMER" in values) {
                    seasons = items
                } else if ("MOVIE" in values && "TV" in values) {
                    formats = items
                } else if ("FINISHED" in values && "RELEASING" in values) {
                    statuses = items
                } else if ("POPULARITY_DESC" in values) {
                    sorts = items.map { (label, value) -> label to (SORT_MAP[value] ?: value.lowercase()) }
                }
            }

            val editor = preferences.edit()
            if (genres.isNotEmpty()) editor.putString(PREF_GENRES, genres.joinToString("\n"))
            if (tags.isNotEmpty()) editor.putString(PREF_TAGS, tags.joinToString("\n"))
            if (formats.isNotEmpty()) editor.putString(PREF_FORMATS, encodePairs(formats))
            if (seasons.isNotEmpty()) editor.putString(PREF_SEASONS, encodePairs(seasons))
            if (statuses.isNotEmpty()) editor.putString(PREF_STATUSES, encodePairs(statuses))
            if (sorts.isNotEmpty()) editor.putString(PREF_SORTS, encodePairs(sorts))
            editor.apply()
        } catch (_: Exception) {
            // Keep using existing cached
        }
    }

    private fun encodePairs(pairs: List<Pair<String, String>>): String = pairs.joinToString("\n") { "${it.first}|${it.second}" }

    private fun decodePairs(raw: String?): List<Pair<String, String>> = raw?.split("\n")?.mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size == 2) parts[0] to parts[1] else null
    }.orEmpty()

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class SortFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Sort by", vals)
    class FormatFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Format", vals)
    class StatusFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Status", vals)
    class SeasonFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Season", vals)
    class YearFilter : AnimeFilter.Text("Year")

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<String>) : AnimeFilter.Group<GenreCheckBox>("Genres", genres.map { GenreCheckBox(it) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.name }
    }

    class TagCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
    class TagFilter(tags: List<String>) : AnimeFilter.Group<TagCheckBox>("Tags", tags.map { TagCheckBox(it) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.name }
    }

    fun build(preferences: SharedPreferences? = null): AnimeFilterList {
        val cachedSorts = decodePairs(preferences?.getString(PREF_SORTS, null))
        val cachedFormats = decodePairs(preferences?.getString(PREF_FORMATS, null))
        val cachedStatuses = decodePairs(preferences?.getString(PREF_STATUSES, null))
        val cachedSeasons = decodePairs(preferences?.getString(PREF_SEASONS, null))

        val cachedGenres = preferences?.getString(PREF_GENRES, null)
            ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

        val cachedTags = preferences?.getString(PREF_TAGS, null)
            ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("Filters can be combined with a text search"),
        )

        if (cachedSorts.isNotEmpty()) {
            filters.add(SortFilter(cachedSorts.toTypedArray()))
        }
        if (cachedFormats.isNotEmpty()) {
            filters.add(FormatFilter((listOf(Pair("Any", "")) + cachedFormats).toTypedArray()))
        }
        if (cachedStatuses.isNotEmpty()) {
            filters.add(StatusFilter((listOf(Pair("Any", "")) + cachedStatuses).toTypedArray()))
        }
        if (cachedSeasons.isNotEmpty()) {
            filters.add(SeasonFilter((listOf(Pair("Any", "")) + cachedSeasons).toTypedArray()))
        }
        filters.add(YearFilter())
        if (cachedGenres.isNotEmpty()) {
            filters.add(GenreFilter(cachedGenres))
        }
        if (cachedTags.isNotEmpty()) {
            filters.add(TagFilter(cachedTags))
        }

        if (cachedSorts.isEmpty() && cachedGenres.isEmpty()) {
            filters.add(AnimeFilter.Header("Filters are loading... Reopen filters once loaded."))
        }

        return AnimeFilterList(filters)
    }
}
