package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

object Filters {

    private const val PREF_GENRES = "pref_dynamic_genres"
    private const val PREF_STATUSES = "pref_dynamic_statuses"
    private const val PREF_TYPES = "pref_dynamic_types"
    private const val PREF_SEASONS = "pref_dynamic_seasons"
    private const val PREF_SORTS = "pref_dynamic_sorts"
    private const val PREF_LAST_FETCH = "pref_dynamic_filters_last_fetch"
    private const val TTL = 86400000L // 24 hours

    @Volatile
    private var isFetching = false

    private fun hostKey(prefix: String, baseUrl: String): String {
        val host = runCatching { baseUrl.toHttpUrl().host }.getOrDefault("witanime")
        return "${prefix}_$host"
    }

    suspend fun fetchFilters(client: OkHttpClient, baseUrl: String, preferences: SharedPreferences) {
        val lastFetchKey = hostKey(PREF_LAST_FETCH, baseUrl)
        val lastFetch = preferences.getLong(lastFetchKey, 0L)
        val genresKey = hostKey(PREF_GENRES, baseUrl)
        val cachedGenres = preferences.getString(genresKey, null)
        if (System.currentTimeMillis() - lastFetch < TTL && !cachedGenres.isNullOrBlank()) {
            return
        }

        if (isFetching) return
        isFetching = true

        try {
            val headers = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .set("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(GET("$baseUrl/browse", headers)).execute()
            if (!response.isSuccessful) {
                response.close()
                return
            }

            val doc = response.asJsoup()

            val genres = mutableListOf<Pair<String, String>>()
            val statuses = mutableListOf<Pair<String, String>>()
            val types = mutableListOf<Pair<String, String>>()
            val seasons = mutableListOf<Pair<String, String>>()
            val sorts = mutableListOf<Pair<String, String>>()

            doc.select("label").forEach { label ->
                val inp = label.selectFirst("input") ?: return@forEach
                val value = inp.attr("value").trim()
                val name = label.text().trim()
                if (value.isBlank() || name.isBlank() || name == "الكل" || value == "on") return@forEach

                val wireModel = inp.attr("wire:model").ifBlank { inp.attr("wire:model.live") }
                val wireKey = label.attr("wire:key")

                when {
                    inp.attr("type") == "checkbox" -> genres.add(name to value)
                    wireModel.contains("Status", ignoreCase = true) || wireKey.contains("filter-status-") -> statuses.add(name to value)
                    wireModel.contains("Type", ignoreCase = true) || wireKey.contains("filter-type-") -> types.add(name to value)
                    wireModel.contains("Season", ignoreCase = true) || wireKey.contains("filter-season-") -> seasons.add(name to value)
                    wireModel.contains("sort", ignoreCase = true) || wireKey.contains("filter-sort-") -> sorts.add(name to value)
                }
            }

            if (genres.isEmpty() && statuses.isEmpty()) {
                return
            }

            val editor = preferences.edit()
            editor.putString(genresKey, encodePairs(genres.distinctBy { it.second }))
            editor.putString(hostKey(PREF_STATUSES, baseUrl), encodePairs(statuses.distinctBy { it.second }))
            editor.putString(hostKey(PREF_TYPES, baseUrl), encodePairs(types.distinctBy { it.second }))
            editor.putString(hostKey(PREF_SEASONS, baseUrl), encodePairs(seasons.distinctBy { it.second }))
            editor.putString(hostKey(PREF_SORTS, baseUrl), encodePairs(sorts.distinctBy { it.second }))
            editor.putLong(lastFetchKey, System.currentTimeMillis())
            editor.apply()
        } catch (_: Exception) {
            // Error during fetch
        } finally {
            isFetching = false
        }
    }

    private fun encodePairs(pairs: List<Pair<String, String>>): String = pairs.joinToString("\n") { "${it.first}|${it.second}" }

    private fun decodePairs(raw: String?): List<Pair<String, String>> = raw?.split("\n")?.mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size == 2) parts[0] to parts[1] else null
    }.orEmpty()

    open class UriPartFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<CheckBoxVal>("التصنيفات", genres.map { CheckBoxVal(it.first, false) }) {
        private val values = genres.map { it.second }
        fun getSelectedValues(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) values[index] else null
        }
    }

    class StatusFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الحالة", vals)
    class TypeFilter(vals: Array<Pair<String, String>>) : UriPartFilter("النوع", vals)
    class SortFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الترتيب", vals)
    class SeasonFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الموسم", vals)
    class YearFilter : AnimeFilter.Text("السنة")

    fun build(preferences: SharedPreferences? = null, baseUrl: String = "", onEmptyCache: (() -> Unit)? = null): AnimeFilterList {
        val cachedGenres = decodePairs(preferences?.getString(hostKey(PREF_GENRES, baseUrl), null))
        val cachedStatuses = decodePairs(preferences?.getString(hostKey(PREF_STATUSES, baseUrl), null))
        val cachedTypes = decodePairs(preferences?.getString(hostKey(PREF_TYPES, baseUrl), null))
        val cachedSeasons = decodePairs(preferences?.getString(hostKey(PREF_SEASONS, baseUrl), null))
        val cachedSorts = decodePairs(preferences?.getString(hostKey(PREF_SORTS, baseUrl), null))

        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("استخدم التصفية عند ترك حقل البحث فارغاً"),
        )

        if (cachedStatuses.isNotEmpty()) {
            filters.add(StatusFilter((listOf(Pair("الكل", "")) + cachedStatuses).toTypedArray()))
        }
        if (cachedTypes.isNotEmpty()) {
            filters.add(TypeFilter((listOf(Pair("الكل", "")) + cachedTypes).toTypedArray()))
        }
        if (cachedSorts.isNotEmpty()) {
            filters.add(SortFilter((listOf(Pair("الافتراضي", "")) + cachedSorts).toTypedArray()))
        }
        if (cachedSeasons.isNotEmpty()) {
            filters.add(SeasonFilter((listOf(Pair("الكل", "")) + cachedSeasons).toTypedArray()))
        }
        filters.add(YearFilter())
        if (cachedGenres.isNotEmpty()) {
            filters.add(GenreFilter(cachedGenres))
        }

        if (cachedGenres.isEmpty() && cachedStatuses.isEmpty()) {
            onEmptyCache?.invoke()
            filters.add(AnimeFilter.Header("جاري تحميل التصنيفات... أعد فتح الفلاتر بعد لحظات."))
        }

        return AnimeFilterList(filters)
    }
}
