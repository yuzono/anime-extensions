package eu.kanade.tachiyomi.animeextension.en.anihq

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.get
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicBoolean

class Filters(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {
    interface QueryParameterFilter {
        fun toQueryParameter(): Pair<String, List<String>>
    }

    @Serializable
    class OptionDto(val name: String, val value: String)

    @Serializable
    class SectionDto(
        val title: String,
        val param: String,
        val options: List<OptionDto> = emptyList(),
    )

    @Volatile
    private var memo: List<SectionDto>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    fun getFilterList(): AnimeFilterList {
        val cached = loadCached()
        if (cached.isNullOrEmpty() || isStale()) refreshAsync()

        return if (cached.isNullOrEmpty()) {
            AnimeFilterList(
                AnimeFilter.Header("Filters are fetched in the background — press 'Reset' in a moment."),
                SortFilter(),
                OrderFilter(),
            )
        } else {
            AnimeFilterList(
                SortFilter(),
                OrderFilter(),
                *cached.map { section ->
                    CheckboxList(section.title, section.param, section.options.map { it.name to it.value })
                }.toTypedArray(),
            )
        }
    }

    private fun loadCached(): List<SectionDto>? {
        memo?.let { return it }
        val raw = preferences.getString(PREF_CACHE_KEY, null) ?: return null
        return runCatching { Json.decodeFromString<List<SectionDto>>(raw) }
            .getOrNull()?.also { memo = it }
    }

    private fun isStale(): Boolean = System.currentTimeMillis() - preferences.getLong(PREF_FETCHED_AT_KEY, 0L) >= TTL_MS

    private fun refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                client.get("$baseUrl/search/").useAsJsoup().parseSections()
            }.onSuccess { fresh ->
                if (fresh.isNotEmpty()) {
                    preferences.edit()
                        .putString(PREF_CACHE_KEY, Json.encodeToString(fresh))
                        .putLong(PREF_FETCHED_AT_KEY, System.currentTimeMillis())
                        .apply()
                    memo = fresh
                }
            }
            refreshing.set(false)
        }
    }

    class SortFilter :
        AnimeFilter.Select<String>(
            "Sort by",
            arrayOf("Default", "Title", "Release Date", "Rating", "Popularity", "Favorite", "Updated"),
        ),
        QueryParameterFilter {
        override fun toQueryParameter(): Pair<String, List<String>> = "orderby" to listOf(FORM_VALUES[state]).filter(String::isNotBlank)

        companion object {
            val FORM_VALUES = arrayOf("popular", "title", "date", "rating", "popular", "favorite", "updated")
        }
    }

    class OrderFilter :
        AnimeFilter.Select<String>(
            "Order",
            arrayOf("Descending", "Ascending"),
        ),
        QueryParameterFilter {
        override fun toQueryParameter(): Pair<String, List<String>> = "order" to listOf(if (state == 0) "DESC" else "ASC")
    }

    private fun Document.parseSections(): List<SectionDto> = listOf(
        "Genre" to "#genre-content",
        "Status" to "#status-content",
        "Type" to "#type-content",
        "Studio" to "#studio-content",
        "Producer" to "#producer-content",
        "Season" to "#season-content",
        "Premiered" to "#premiered-content",
    ).mapNotNull { (title, selector) ->
        val options = select("$selector input[type='checkbox']").mapNotNull { input ->
            val label = input.nextElementSibling()?.text()?.trim()
                ?.takeUnless(CharSequence::isNullOrBlank) ?: return@mapNotNull null
            val value = input.attr("value").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            OptionDto(label, value)
        }
        if (options.isEmpty()) null else SectionDto(title, "${title.lowercase()}[]", options)
    }

    class Checkbox(name: String) : AnimeFilter.CheckBox(name)

    class CheckboxList(
        name: String,
        private val paramName: String,
        private val pairs: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { Checkbox(it.first) }),
        QueryParameterFilter {
        override fun toQueryParameter(): Pair<String, List<String>> {
            val lookup = pairs.associate { it.first to it.second }
            return paramName to state.asSequence()
                .filter { it.state }
                .mapNotNull { lookup[it.name] }
                .toList()
        }
    }

    companion object {
        private const val PREF_CACHE_KEY = "filters_cache"
        private const val PREF_FETCHED_AT_KEY = "filters_fetched_at"
        private const val TTL_MS = 24 * 60 * 60 * 1000L
    }
}
