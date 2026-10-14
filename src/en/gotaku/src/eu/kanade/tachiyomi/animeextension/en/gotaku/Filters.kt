package eu.kanade.tachiyomi.animeextension.en.gotaku

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object Filters {
    private const val GENRE_PARAM = "genre"
    private const val THEME_PARAM = "tag"

    private val cacheFile = File(applicationContext.filesDir, "gotaku_filters.json")

    class FilterData(
        val genres: List<Pair<String, String>>, // label -> numeric id
        val themes: List<Pair<String, String>>,
        val types: List<Pair<String, String>>,
        val statuses: List<Pair<String, String>>,
        val seasons: List<Pair<String, String>>,
        val ratings: List<Pair<String, String>>,
        val years: List<Pair<String, String>>,
    )

    fun hasCache(): Boolean = cacheFile.exists()

    fun getFilterList(): AnimeFilterList {
        val data = loadCachedFilters() ?: return AnimeFilterList(AnimeFilter.Header("Press 'Reset' to load filters"))
        return buildList(data)
    }

    fun fetchAndCacheFilters(client: OkHttpClient, headers: Headers, apiUrl: String) {
        try {
            val filterClient = client.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            filterClient.newCall(Request.Builder().url("$apiUrl/filters").headers(headers).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val jsonString = response.body.string()
                runCatching { jsonString.parseAs<FiltersResponseDto>() }.getOrNull()?.data ?: return
                if (!cacheFile.exists() || cacheFile.readText() != jsonString) {
                    cacheFile.writeText(jsonString)
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadCachedFilters(): FilterData? {
        if (!cacheFile.exists()) return null
        return try {
            val dto = cacheFile.readText().parseAs<FiltersResponseDto>().data
            FilterData(
                genres = dto.genres.map { it.name to it.id.toString() }.distinct(),
                themes = dto.themes.map { it.name to it.id.toString() }.distinct(),
                types = dto.types.map { it.label to it.value }.distinct(),
                statuses = dto.statuses.map { it.label to it.value }.distinct(),
                seasons = dto.seasons.map { it.label to it.value }.distinct(),
                ratings = dto.ratings.map { it.label to it.value }.distinct(),
                years = dto.years.map { it.label to it.value }.distinct(),
            )
        } catch (_: Exception) {
            cacheFile.delete()
            null
        }
    }

    // ============================== Filters ===============================
    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES, 0) {
        fun getValue() = SORT_VALUES[state]

        companion object {
            private val SORT_ENTRIES = arrayOf(
                "Recently updated",
                "Trending",
                "Highest rated",
                "Most viewed",
                "Newest",
                "Name A–Z",
            )
            private val SORT_VALUES = arrayOf(
                "latest",
                "trending_week",
                "score",
                "views",
                "new_releases",
                "name",
            )
        }
    }

    class TrackFilter : AnimeFilter.Select<String>("Audio", arrayOf("Any", "Sub", "Dub"), 0) {
        fun getValue(): String? = if (state == 0) null else VALUES[state]

        companion object {
            private val VALUES = arrayOf(null, "sub", "dub")
        }
    }

    class SelectFilter(
        name: String,
        val param: String,
        private val optionValues: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, arrayOf("Any") + optionValues.map { it.first }) {
        fun getValue(): String? = if (state == 0) null else optionValues[state - 1].second
    }

    class TriStateVal(name: String, val value: String) : AnimeFilter.TriState(name)

    class TriStateGroup(
        name: String,
        val param: String,
        values: List<Pair<String, String>>,
    ) : AnimeFilter.Group<TriStateVal>(name, values.map { TriStateVal(it.first, it.second) })

    class TagModeFilter :
        AnimeFilter.Select<String>(
            "Tag Match Mode",
            arrayOf("Any (OR)", "All (AND)"),
            1,
        ) {
        fun getValue(): String? = when (state) {
            0 -> "any"
            1 -> null
            else -> null
        }
    }

    private fun buildList(d: FilterData) = AnimeFilterList(
        SortFilter(),
        SelectFilter("Format", "type", d.types),
        SelectFilter("Status", "status", d.statuses),
        SelectFilter("Season", "season", d.seasons),
        SelectFilter("Year", "year", d.years),
        TrackFilter(),
        SelectFilter("Content Rating", "rating", d.ratings),
        TriStateGroup("Genres", GENRE_PARAM, d.genres),
        TriStateGroup("Themes", THEME_PARAM, d.themes),
        TagModeFilter(),
    )
}
