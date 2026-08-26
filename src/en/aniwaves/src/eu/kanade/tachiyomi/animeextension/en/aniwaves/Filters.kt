package eu.kanade.tachiyomi.animeextension.en.aniwaves

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import java.util.Calendar

object Filters {

    internal class SortFilter : QueryPartFilter("Sort order", Data.SORT)
    internal class GenreFilter : CheckBoxFilterList("Genre", Data.GENRE)
    internal class CountryFilter : CheckBoxFilterList("Country", Data.COUNTRY)
    internal class SeasonFilter : CheckBoxFilterList("Season", Data.SEASON)
    internal class YearFilter : CheckBoxFilterList("Year", Data.YEAR)
    internal class TypeFilter : CheckBoxFilterList("Type", Data.TYPE)
    internal class StatusFilter : CheckBoxFilterList("Status", Data.STATUS)
    internal class LanguageFilter : QueryPartFilter("Language", Data.LANGUAGE)
    internal class RatingFilter : CheckBoxFilterList("Rating", Data.RATING)

    val FILTER_LIST: AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        CountryFilter(),
        SeasonFilter(),
        YearFilter(),
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
        RatingFilter(),
    )

    data class FilterSearchParams(
        val sort: String = "",
        val genres: List<String> = emptyList(),
        val countries: List<String> = emptyList(),
        val seasons: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val language: String = "",
        val ratings: List<String> = emptyList(),
    )

    internal open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    internal open class CheckBoxFilterList(
        name: String,
        values: Array<Pair<String, String>>,
    ) : AnimeFilter.Group<CheckBoxVal>(name, values.map { CheckBoxVal(it.first) }) {
        fun selectedValues(vals: Array<Pair<String, String>>) = state
            .filter { it.state }
            .mapNotNull { box -> vals.find { it.first == box.name }?.second }
    }

    internal class CheckBoxVal(name: String) : AnimeFilter.CheckBox(name, false)

    internal object Data {
        val SORT = arrayOf(
            Pair("Default", ""),
            Pair("Recently Updated", "last_updated"),
            Pair("Recently Added", "created_at"),
            Pair("Most Relevant", "most_relevant"),
            Pair("Most Watched", "members"),
            Pair("Most Favourited", "favorites"),
            Pair("Most Viewed", "m_view"),
            Pair("Release Date", "year"),
            Pair("Name A-Z", "title"),
            Pair("Scores", "scored_by"),
            Pair("Popularity", "popularity"),
            Pair("Rank", "rank"),
            Pair("MAL Scores", "score"),
            Pair("Reviews", "reviews"),
            Pair("Rating", "rating"),
            Pair("# of Episodes", "episodes"),
            Pair("# of Episodes Released", "ep_released"),
        )

        val GENRE = arrayOf(
            Pair("Action", "1"),
            Pair("Adult Cast", "15"),
            Pair("Adventure", "10"),
            Pair("Anthropomorphic", "9"),
            Pair("Avant Garde", "396"),
            Pair("Award Winning", "2035"),
            Pair("CGDCT", "20"),
            Pair("Childcare", "863"),
            Pair("Combat Sports", "1143"),
            Pair("Comedy", "4"),
            Pair("Delinquents", "307"),
            Pair("Detective", "593"),
            Pair("Drama", "30"),
            Pair("Ecchi", "77"),
            Pair("Educational", "1571"),
            Pair("Erotica", "4721"),
            Pair("Fantasy", "5"),
            Pair("Gag Humor", "444"),
            Pair("Gore", "190"),
            Pair("Gourmet", "90"),
            Pair("Harem", "78"),
            Pair("High Stakes Game", "395"),
            Pair("Historical", "180"),
            Pair("Horror", "188"),
            Pair("Idols (Female)", "316"),
            Pair("Idols (Male)", "972"),
            Pair("Isekai", "12"),
            Pair("Iyashikei", "133"),
            Pair("Josei", "298"),
            Pair("Kids", "93"),
            Pair("Love Polygon", "1982"),
            Pair("Mahou Shoujo", "241"),
            Pair("Martial Arts", "209"),
            Pair("Mecha", "45"),
            Pair("Medical", "289"),
            Pair("Military", "55"),
            Pair("Music", "317"),
            Pair("Mystery", "272"),
            Pair("Mythology", "24"),
            Pair("Organized Crime", "391"),
            Pair("Otaku Culture", "1567"),
            Pair("Parody", "748"),
            Pair("Performing Arts", "1011"),
            Pair("Pets", "296"),
            Pair("Psychological", "139"),
            Pair("Racing", "1162"),
            Pair("Reincarnation", "13"),
            Pair("Reverse Harem", "1124"),
            Pair("Romance", "23"),
            Pair("Romantic Subtext", "157"),
            Pair("Samurai", "181"),
            Pair("School", "106"),
            Pair("Sci-Fi", "44"),
            Pair("Seinen", "17"),
            Pair("Shoujo", "25"),
            Pair("Shounen", "7"),
            Pair("Showbiz", "3022"),
            Pair("Slice of Life", "8"),
            Pair("Space", "869"),
            Pair("Sports", "96"),
            Pair("Strategy Game", "2"),
            Pair("Super Power", "6"),
            Pair("Supernatural", "19"),
            Pair("Survival", "677"),
            Pair("Suspense", "138"),
            Pair("Team Sports", "463"),
            Pair("Time Travel", "470"),
            Pair("Urban Fantasy", "586648"),
            Pair("Vampire", "934"),
            Pair("Video Game", "94"),
            Pair("Villainess", "359345"),
            Pair("Visual Arts", "780"),
            Pair("Workplace", "16"),
        )

        val COUNTRY = arrayOf(
            Pair("China", "cn"),
            Pair("Japan", "jp"),
            Pair("Korea", "kr"),
        )

        val SEASON = arrayOf(
            Pair("Spring", "spring"),
            Pair("Summer", "summer"),
            Pair("Fall", "fall"),
            Pair("Winter", "winter"),
            Pair("Unknown", "null"),
        )

        val YEAR: Array<Pair<String, String>> = buildList {
            (Calendar.getInstance().get(Calendar.YEAR) + 1 downTo 2004).forEach {
                add(Pair(it.toString(), it.toString()))
            }
            listOf("2000s", "1990s", "1980s", "1970s", "1960s", "1950s", "1940s", "1930s", "1920s").forEach {
                add(Pair(it, it))
            }
        }.toTypedArray()

        val TYPE = arrayOf(
            Pair("Movie", "movie"),
            Pair("TV", "tv"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("TV Special", "tv-special"),
            Pair("CM", "cm"),
            Pair("Music", "music"),
        )

        val STATUS = arrayOf(
            Pair("Currently Airing", "0"),
            Pair("Finished Airing", "1"),
        )

        val LANGUAGE = arrayOf(
            Pair("All", ""),
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
            Pair("Sub & Dub", "subdub"),
        )

        val RATING = arrayOf(
            Pair("G - All Ages", "g"),
            Pair("PG - Children", "pg"),
            Pair("PG-13 - Teens 13 or older", "pg13"),
            Pair("R - 17+ (Violence & Profanity)", "r17"),
            Pair("R+ - Mild Nudity", "r"),
        )
    }
}

internal fun AnimeFilterList.getSearchParameters(): Filters.FilterSearchParams {
    if (isEmpty()) return Filters.FilterSearchParams()
    return Filters.FilterSearchParams(
        sort = firstInstance<Filters.SortFilter>().toQueryPart(),
        genres = firstInstanceOrNull<Filters.GenreFilter>()?.selectedValues(Filters.Data.GENRE).orEmpty(),
        countries = firstInstanceOrNull<Filters.CountryFilter>()?.selectedValues(Filters.Data.COUNTRY).orEmpty(),
        seasons = firstInstanceOrNull<Filters.SeasonFilter>()?.selectedValues(Filters.Data.SEASON).orEmpty(),
        years = firstInstanceOrNull<Filters.YearFilter>()?.selectedValues(Filters.Data.YEAR).orEmpty(),
        types = firstInstanceOrNull<Filters.TypeFilter>()?.selectedValues(Filters.Data.TYPE).orEmpty(),
        statuses = firstInstanceOrNull<Filters.StatusFilter>()?.selectedValues(Filters.Data.STATUS).orEmpty(),
        language = firstInstance<Filters.LanguageFilter>().toQueryPart(),
        ratings = firstInstanceOrNull<Filters.RatingFilter>()?.selectedValues(Filters.Data.RATING).orEmpty(),
    )
}
