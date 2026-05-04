package eu.kanade.tachiyomi.animeextension.en.kickassanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

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

        private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        // Updated years to match current website; updates dynamically
        val YEAR = arrayOf(Pair("All", "")) + (currentYear downTo 1967).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

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
