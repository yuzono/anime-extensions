package eu.kanade.tachiyomi.animeextension.en.kickassanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KickAssAnimeFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String = this.filterIsInstance<R>().joinToString("") {
        (it as QueryPartFilter).toQueryPart()
    }

    class GenreFilter :
        CheckBoxFilterList(
            "Genre",
            KickAssAnimeFiltersData.GENRE.map { CheckBoxVal(it.first, false) },
        )

    class YearFilter : QueryPartFilter("Year", KickAssAnimeFiltersData.YEAR)
    class StatusFilter : QueryPartFilter("Status", KickAssAnimeFiltersData.STATUS)
    class TypeFilter : QueryPartFilter("Type", KickAssAnimeFiltersData.TYPE)
    class SubPageFilter : QueryPartFilter("Sub-page", KickAssAnimeFiltersData.SUBPAGE)

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        TypeFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("NOTE: Overrides & ignores search and other filters"),
        SubPageFilter(),
    )

    data class FilterSearchParams(
        val filters: String = "",
        val subPage: String = "",
    )

    private fun getJsonList(listString: String, name: String): String {
        if (listString.isEmpty()) return ""
        return "\"$name\":[$listString]"
    }

    private fun getJsonItem(item: String, name: String): String {
        if (item.isEmpty()) return ""
        return "\"$name\":$item"
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        val genre = filters.filterIsInstance<GenreFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    KickAssAnimeFiltersData.GENRE.find { it.first == format.name }!!.second
                } else {
                    null
                }
            }.joinToString(",") { "\"$it\"" }

        val year = filters.asQueryPart<YearFilter>()
        val status = filters.asQueryPart<StatusFilter>()
        val type = filters.asQueryPart<TypeFilter>()

        val filtersQuery = "{${
            listOf(
                getJsonList(genre, "genres"),
                getJsonItem(year, "year"),
                getJsonItem(status, "status"),
                getJsonItem(type, "type"),
            ).filter { it.isNotEmpty() }.joinToString(",")
        }}"
        return FilterSearchParams(
            filtersQuery,
            filters.asQueryPart<SubPageFilter>(),
        )
    }

    private object KickAssAnimeFiltersData {
        val GENRE = arrayOf( // Updated filter array to match current website.
            Pair("Action", "Action"),
            Pair("Adult Cast", "Adult Cast"),
            Pair("Adventure", "Adventure"),
            Pair("Anthropomorphic", "Anthropomorphic"),
            Pair("Avant Garde", "Avant Garde"),
            Pair("Award Winning", "Award Winning"),
            Pair("Boys Love", "Boys Love"),
            Pair("CGDCT", "CGDCT"),
            Pair("Childcare", "Childcare"),
            Pair("Combat Sports", "Combat Sports"),
            Pair("Comedy", "Comedy"),
            Pair("Crossdressing", "Crossdressing"),
            Pair("Delinquents", "Delinquents"),
            Pair("Detective", "Detective"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Educational", "Educational"),
            Pair("Erotica", "Erotica"),
            Pair("Fantasy", "Fantasy"),
            Pair("Gag Humor", "Gag Humor"),
            Pair("Girls Love", "Girls Love"),
            Pair("Gore", "Gore"),
            Pair("Gourmet", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("High Stakes Game", "High Stakes Game"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Idols (Female)", "Idols (Female)"),
            Pair("Idols (Male)", "Idols (Male)"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Love Polygon", "Love Polygon"),
            Pair("Magical Sex Shift", "Magical Sex Shift"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Medical", "Medical"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Mythology", "Mythology"),
            Pair("Organized Crime", "Organized Crime"),
            Pair("Otaku Culture", "Otaku Culture"),
            Pair("Parody", "Parody"),
            Pair("Performing Arts", "Performing Arts"),
            Pair("Pets", "Pets"),
            Pair("Psychological", "Psychological"),
            Pair("Racing", "Racing"),
            Pair("Reincarnation", "Reincarnation"),
            Pair("Reverse Harem", "Reverse Harem"),
            Pair("Romance", "Romance"),
            Pair("Romantic Subtext", "Romantic Subtext"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Showbiz", "Showbiz"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Strategy Game", "Strategy Game"),
            Pair("Super Power", "Super Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Survival", "Survival"),
            Pair("Suspense", "Suspense"),
            Pair("Team Sports", "Team Sports"),
            Pair("Time Travel", "Time Travel"),
            Pair("Urban Fantasy", "Urban Fantasy"),
            Pair("Vampire", "Vampire"),
            Pair("Video Game", "Video Game"),
            Pair("Villainess", "Villainess"),
            Pair("Visual Arts", "Visual Arts"),
            Pair("Workplace", "Workplace"),
        )

        val YEAR = arrayOf( // Updated year array to match current website descending instead of ascending.
            Pair("All", ""),
            Pair("2027", "2027"), // For later/upcoming releases as there are none on website now
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
            Pair("1996", "1996"),
            Pair("1995", "1995"),
            Pair("1994", "1994"),
            Pair("1993", "1993"),
            Pair("1992", "1992"),
            Pair("1991", "1991"),
            Pair("1990", "1990"),
            Pair("1989", "1989"),
            Pair("1988", "1988"),
            Pair("1987", "1987"),
            Pair("1986", "1986"),
            Pair("1985", "1985"),
            Pair("1984", "1984"),
            Pair("1983", "1983"),
            Pair("1982", "1982"),
            Pair("1981", "1981"),
            Pair("1980", "1980"),
            Pair("1979", "1979"),
            Pair("1978", "1978"),
            Pair("1977", "1977"),
            Pair("1974", "1974"),
            Pair("1972", "1972"),
            Pair("1971", "1971"),
            Pair("1967", "1967"), // No more releases before 1967.
        )

        val STATUS = arrayOf(
            Pair("All", ""),
            Pair("Finished Airing", "\"finished\""),
            Pair("Currently Airing", "\"airing\""),
        )

        val TYPE = arrayOf(
            Pair("All", ""),
            Pair("TV", "\"tv\""),
            Pair("Movie", "\"movie\""), // Updated type to match website.
            Pair("ONA", "\"ona\""),
            Pair("OVA", "\"ova\""),
            Pair("SPECIAL", "\"special\""),
            Pair("TV_SPECIAL", "\"tv_special\""),
        )

        val SUBPAGE = arrayOf(
            Pair("<Select>", ""),
            Pair("Trending", "show/trending"),
            Pair("Anime", "anime"),
            Pair("Recently Added", "show/recent"),
            Pair("Popular Shows", "show/popular"),
        )
    }
}
