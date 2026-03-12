package eu.kanade.tachiyomi.multisrc.sudatchi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object SudatchiFilters {

    interface QueryParameterFilter {
        fun toQueryParameter(): Pair<String, String?>
    }

    private class Checkbox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private class CheckboxList(name: String, private val paramName: String, private val pairs: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { Checkbox(it.first) }),
        QueryParameterFilter {
        override fun toQueryParameter() = Pair(
            paramName,
            state.asSequence()
                .filter { it.state }
                .map { checkbox -> pairs.find { it.first == checkbox.name }!!.second }
                .filter(String::isNotBlank)
                .joinToString(","),
        )
    }

    private class SelectList(name: String, private val paramName: String, private val pairs: List<Pair<String, String>>, state: Int = 0) :
        AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray(), state = state),
        QueryParameterFilter {
        override fun toQueryParameter() = Pair(
            paramName,
            pairs[state].second.takeUnless { it.isBlank() },
        )
    }

    fun getFilterList(): AnimeFilterList {
        val yearList = buildList {
            add(FilterItemDto("", "<select>"))
            addAll(
                (currentYear downTo 1960).map {
                    FilterItemDto(it.toString(), it.toString())
                },
            )
        }.toList()

        return AnimeFilterList(
            CheckboxList("Genres", "genres", genreList.toPairList()),
            SelectList("Status", "status", statusList.toPairList()),
            SelectList("Format", "format", formatList.toPairList()),
            SelectList("Year", "year", yearList.toPairList()),
            SelectList("Sort", "sort", sortList.toPairList()),
        )
    }

    fun getPopularFilterList(): AnimeFilterList = AnimeFilterList(
        SelectList("Sort", "sort", sortList.toPairList(), 0),
    )

    fun getTrendingFilterList(): AnimeFilterList = AnimeFilterList(
        SelectList("Sort", "sort", sortList.toPairList(), 2),
    )

    private fun List<FilterItemDto>.toPairList() = map { Pair(it.name, it.id) }

    private val currentYear by lazy {
        Calendar.getInstance().get(Calendar.YEAR)
    }

    data class FilterItemDto(
        val id: String,
        val name: String,
    )

    private val genreList = listOf(
        FilterItemDto("Action", "Action"),
        FilterItemDto("Adventure", "Adventure"),
        FilterItemDto("Comedy", "Comedy"),
        FilterItemDto("Drama", "Drama"),
        FilterItemDto("Ecchi", "Ecchi"),
        FilterItemDto("Fantasy", "Fantasy"),
        FilterItemDto("Horror", "Horror"),
        FilterItemDto("Mahou Shoujo", "Mahou Shoujo"),
        FilterItemDto("Mecha", "Mecha"),
        FilterItemDto("Music", "Music"),
        FilterItemDto("Mystery", "Mystery"),
        FilterItemDto("Psychological", "Psychological"),
        FilterItemDto("Romance", "Romance"),
        FilterItemDto("Sci-Fi", "Sci-Fi"),
        FilterItemDto("Slice of Life", "Slice of Life"),
        FilterItemDto("Sports", "Sports"),
        FilterItemDto("Supernatural", "Supernatural"),
        FilterItemDto("Thriller", "Thriller"),
    )

    private val formatList = listOf(
        FilterItemDto("", "<select>"),
        FilterItemDto("TV", "TV Series"),
        FilterItemDto("MOVIE", "Movie"),
        FilterItemDto("OVA", "OVA"),
        FilterItemDto("ONA", "ONA"),
        FilterItemDto("TV_SHORT", "TV Short"),
        FilterItemDto("SPECIAL", "Special"),
    )

    private val statusList = listOf(
        FilterItemDto("", "<select>"),
        FilterItemDto("RELEASING", "Currently Airing"),
        FilterItemDto("FINISHED", "Completed"),
        FilterItemDto("NOT_YET_RELEASED", "Upcoming"),
        FilterItemDto("CANCELLED", "Cancelled"),
    )

    private val sortList = listOf(
        FilterItemDto("POPULARITY_DESC", "Most Popular"),
        FilterItemDto("SCORE_DESC", "Highest Rated"),
        FilterItemDto("TRENDING_DESC", "Trending"),
        FilterItemDto("START_DATE_DESC", "Newest"),
        FilterItemDto("TITLE_ROMAJI", "A-Z"),
        FilterItemDto("EPISODES_DESC", "Most Episodes"),
    )
}
