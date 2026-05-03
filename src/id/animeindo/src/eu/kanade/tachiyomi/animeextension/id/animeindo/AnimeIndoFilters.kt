package eu.kanade.tachiyomi.animeextension.id.animeindo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.asQueryPart
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.parseCheckbox

object AnimeIndoFilters {

    internal class SortFilter(name: String) : QueryPartFilter(name, SORT_LIST)
    internal class TypeFilter(name: String) : QueryPartFilter(name, TYPE_LIST)
    internal class QualityFilter(name: String) : QueryPartFilter(name, QUALITY_LIST)
    internal class ReleaseFilter(name: String) : QueryPartFilter(name, RELEASE_LIST)

    internal class GenreFilter(name: String) : CheckBoxFilterList(name, GENRE_LIST)
    internal class CountryFilter(name: String) : CheckBoxFilterList(name, COUNTRY_LIST)

    internal data class FilterSearchParams(
        val sort: String = "",
        val type: String = "",
        val quality: String = "",
        val release: String = "",
        val genres: String = "",
        val countries: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            sort = filters.asQueryPart<SortFilter>(),
            type = filters.asQueryPart<TypeFilter>(),
            quality = filters.asQueryPart<QualityFilter>(),
            release = filters.asQueryPart<ReleaseFilter>(),
            genres = filters.parseCheckbox<GenreFilter>(GENRE_LIST, "genre"),
            countries = filters.parseCheckbox<CountryFilter>(COUNTRY_LIST, "country"),
        )
    }

    // Hardcoded from the site's filter modal (Livewire-based, not scrapable)
    private val SORT_LIST = arrayOf(
        Pair("Newest", "created_at"),
        Pair("Most Viewed", "view"),
        Pair("Release Date", "release_date"),
        Pair("Top Rated", "like_count"),
        Pair("Name A-Z", "title"),
        Pair("IMDb", "vote_average"),
    )

    private val TYPE_LIST = arrayOf(
        Pair("All", ""),
        Pair("Movie", "movie"),
        Pair("TV Show", "tv"),
    )

    private val QUALITY_LIST = arrayOf(
        Pair("All", ""),
        Pair("4K", "4K"),
        Pair("HD", "HD"),
        Pair("SD", "SD"),
        Pair("CAM", "CAM"),
    )

    private val RELEASE_LIST = arrayOf(
        Pair("All", ""),
        Pair("2026", "2026"),
        Pair("2025", "2025"),
        Pair("2024", "2024"),
        Pair("2023", "2023"),
        Pair("2022", "2022"),
        Pair("2021", "2021"),
        Pair("Older", "2020"),
    )

    private val GENRE_LIST = arrayOf(
        Pair("Action", "1"),
        Pair("Adventure", "2"),
        Pair("Comedy", "4"),
        Pair("Crime", "5"),
        Pair("Drama", "7"),
        Pair("Family", "8"),
        Pair("Fantasy", "9"),
        Pair("History", "10"),
        Pair("Horror", "11"),
        Pair("Music", "12"),
        Pair("Mystery", "13"),
        Pair("Romance", "14"),
        Pair("Science Fiction", "15"),
        Pair("TV Movie", "16"),
        Pair("Thriller", "17"),
        Pair("War", "18"),
        Pair("Western", "19"),
    )

    private val COUNTRY_LIST = arrayOf(
        Pair("Argentina", "10"),
        Pair("Austria", "14"),
        Pair("Belgium", "22"),
        Pair("Brazil", "31"),
        Pair("Canada", "41"),
        Pair("China", "48"),
        Pair("Denmark", "62"),
        Pair("France", "78"),
        Pair("Germany", "86"),
        Pair("Italy", "112"),
        Pair("Japan", "114"),
        Pair("Mexico", "146"),
        Pair("Netherlands", "160"),
        Pair("Norway", "173"),
        Pair("Poland", "187"),
        Pair("Romania", "191"),
        Pair("Russia", "192"),
        Pair("South Korea", "217"),
        Pair("Spain", "218"),
        Pair("Sweden", "224"),
        Pair("Thailand", "231"),
        Pair("Turkey", "238"),
        Pair("United Kingdom", "249"),
        Pair("United States", "250"),
    )
}
