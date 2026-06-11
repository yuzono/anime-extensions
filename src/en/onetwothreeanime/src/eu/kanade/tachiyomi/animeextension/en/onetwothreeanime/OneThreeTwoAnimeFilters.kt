package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object OneThreeTwoAnimeFilters {

    // ------------------------------------------------------------------ //
    //  Primitives                                                         //
    // ------------------------------------------------------------------ //

    /** A single named checkbox value. */
    class CheckBoxOption(displayName: String, val queryValue: String, state: Boolean = false) : AnimeFilter.CheckBox(displayName, state)

    open class CheckBoxGroup(name: String, val queryParam: String, items: List<CheckBoxOption>) : AnimeFilter.Group<CheckBoxOption>(name, items) {

        fun checkedPairs(): List<Pair<String, String>> = state.filter { it.state }.map { queryParam to it.queryValue }
    }

    open class RadioGroup(name: String, val queryParam: String, val options: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {

        fun selectedPair(): Pair<String, String>? = options[state].second.takeIf { it.isNotBlank() }?.let { queryParam to it }
    }

    // ------------------------------------------------------------------ //
    //  Concrete filter classes                                            //
    // ------------------------------------------------------------------ //

    class GenreFilter :
        CheckBoxGroup(
            "Genre",
            "genre[]",
            FiltersData.GENRES.map { CheckBoxOption(it.first, it.second) },
        )

    class CountryFilter :
        CheckBoxGroup(
            "Country",
            "country[]",
            FiltersData.COUNTRIES.map { CheckBoxOption(it.first, it.second) },
        )

    class SeasonFilter :
        CheckBoxGroup(
            "Season",
            "season[]",
            FiltersData.SEASONS.map { CheckBoxOption(it.first, it.second) },
        )

    class YearFilter :
        CheckBoxGroup(
            "Year",
            "year[]",
            FiltersData.YEARS.map { CheckBoxOption(it, it) },
        )

    class TypeFilter :
        CheckBoxGroup(
            "Type",
            "type[]",
            FiltersData.TYPES.map { CheckBoxOption(it.first, it.second) },
        )

    class StatusFilter :
        RadioGroup(
            "Status",
            "status[]",
            FiltersData.STATUSES,
        )

    class LanguageFilter :
        CheckBoxGroup(
            "Language",
            "language[]",
            FiltersData.LANGUAGES.map { CheckBoxOption(it.first, it.second) },
        )

    class SortFilter :
        RadioGroup(
            "Sort",
            "sort",
            FiltersData.SORT_OPTIONS,
        )

    // ------------------------------------------------------------------ //
    //  FilterList builder                                                 //
    // ------------------------------------------------------------------ //

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            GenreFilter(),
            CountryFilter(),
            SeasonFilter(),
            YearFilter(),
            TypeFilter(),
            StatusFilter(),
            LanguageFilter(),
            SortFilter(),
        )

    // ------------------------------------------------------------------ //
    //  Parsed parameters – returned to searchAnimeRequest                //
    // ------------------------------------------------------------------ //

    data class FilterParams(
        val queryPairs: List<Pair<String, String>>,
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterParams {
        val pairs = mutableListOf<Pair<String, String>>()

        filters.forEach { filter ->
            when (filter) {
                is CheckBoxGroup -> pairs.addAll(filter.checkedPairs())
                is RadioGroup -> filter.selectedPair()?.let { pairs.add(it) }
                else -> { /* ignore separators / headers */ }
            }
        }

        return FilterParams(pairs)
    }

    // ------------------------------------------------------------------ //
    //  Static data                                                        //
    // ------------------------------------------------------------------ //

    private object FiltersData {
        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Cars", "cars"),
            Pair("Comedy", "comedy"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-Life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val COUNTRIES = arrayOf(
            Pair("Japan", "j"),
            Pair("China", "c"),
        )

        val SEASONS = arrayOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
        )

        val YEARS: Array<String> = (2026 downTo 1958).map { it.toString() }.toTypedArray()

        val TYPES = arrayOf(
            Pair("Movie", "movies"),
            Pair("TV Series", "tv-series"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
        )
        val STATUSES = arrayOf(
            Pair("All", ""),
            Pair("Airing", "ongoing"),
            Pair("Finished", "completed"),
            Pair("Upcoming", "upcoming"),
        )

        val LANGUAGES = arrayOf(
            Pair("Subbed", "s"),
            Pair("Dubbed", "d"),
        )

        val SORT_OPTIONS = arrayOf(
            Pair("Default", ""),
            Pair("Name A-Z", "title_asc"),
            Pair("Name Z-A", "title_dsc"),
        )
    }
}
