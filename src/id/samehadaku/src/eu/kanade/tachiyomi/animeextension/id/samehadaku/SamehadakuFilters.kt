package eu.kanade.tachiyomi.animeextension.id.samehadaku

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SamehadakuFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = "&$name=${vals[state].second}"
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String = (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String = (this.getFirst<R>() as CheckBoxFilterList).state
        .mapNotNull { checkbox ->
            if (checkbox.state) {
                options.find { it.first == checkbox.name }!!.second
            } else {
                null
            }
        }.joinToString("&$name[]=").let {
            if (it.isBlank()) {
                ""
            } else {
                "&$name[]=$it"
            }
        }

    class GenreFilter :
        CheckBoxFilterList(
            "Genre",
            FiltersData.GENRE.map { CheckBoxVal(it.first, false) },
        )

    class TypeFilter : QueryPartFilter("Type", FiltersData.TYPE)

    class StatusFilter : QueryPartFilter("Status", FiltersData.STATUS)

    class OrderFilter : QueryPartFilter("Sort By", FiltersData.ORDER)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Ignored With Text Search!!"),
            GenreFilter(),
            TypeFilter(),
            StatusFilter(),
            OrderFilter(),
        )

    data class FilterSearchParams(
        val filter: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenreFilter>(FiltersData.GENRE, "genre") +
                filters.asQueryPart<TypeFilter>("type") +
                filters.asQueryPart<StatusFilter>("status") +
                filters.asQueryPart<OrderFilter>("order"),
        )
    }

    private object FiltersData {
        val ORDER = arrayOf(
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
        )

        val STATUS = arrayOf(
            Pair("All", ""),
            Pair("Currently Airing", "Currently Airing"),
            Pair("Finished Airing", "Finished Airing"),
        )

        val TYPE = arrayOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Movie", "Movie"),
        )

        val GENRE = arrayOf(
            Pair("Fantasy", "fantasy"),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Shounen", "shounen"),
            Pair("School", "school"),
            Pair("Romance", "romance"),
            Pair("Drama", "drama"),
            Pair("Supernatural", "supernatural"),
            Pair("Isekai", "isekai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Historical", "historical"),
            Pair("Mystery", "mystery"),
            Pair("Super Power", "super-power"),
            Pair("Harem", "harem"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Ecchi", "ecchi"),
            Pair("Sports", "sports"),
        )
    }
}
