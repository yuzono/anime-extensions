package eu.kanade.tachiyomi.animeextension.it.animeunity

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance
import okhttp3.HttpUrl

object AnimeUnityFilters {

    open class QueryPartFilter(
        displayName: String,
        private val param: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun applyTo(builder: HttpUrl.Builder) {
            val value = vals[state].second
            if (value.isNotEmpty()) builder.addQueryParameter(param, value)
        }
    }

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genere",
            AnimeUnityFiltersData.GENRE.map { object : AnimeFilter.CheckBox(it.first) {} },
        ) {
        fun applyTo(builder: HttpUrl.Builder) {
            state.forEachIndexed { index, checkBox ->
                if (checkBox.state) {
                    builder.addQueryParameter("genres[]", AnimeUnityFiltersData.GENRE[index].second)
                }
            }
        }
    }

    class OrderFilter : QueryPartFilter("Ordina per", "sort_by", AnimeUnityFiltersData.ORDER)

    class YearFilter : QueryPartFilter("Anno", "year", AnimeUnityFiltersData.YEAR)

    class StateFilter : QueryPartFilter("Stato", "status", AnimeUnityFiltersData.STATE)

    class TypeFilter : QueryPartFilter("Tipo", "type", AnimeUnityFiltersData.TYPE)

    class SeasonFilter : QueryPartFilter("Stagione", "season", AnimeUnityFiltersData.SEASON)

    class LanguageFilter : QueryPartFilter("Audio", "language", AnimeUnityFiltersData.LANGUAGE)

    val FILTER_LIST get() = AnimeFilterList(
        OrderFilter(),
        GenreFilter(),
        YearFilter(),
        StateFilter(),
        TypeFilter(),
        SeasonFilter(),
        LanguageFilter(),
    )

    internal fun applyTo(builder: HttpUrl.Builder, filters: AnimeFilterList) {
        if (filters.isEmpty()) return
        filters.firstInstance<OrderFilter>().applyTo(builder)
        filters.firstInstance<GenreFilter>().applyTo(builder)
        filters.firstInstance<YearFilter>().applyTo(builder)
        filters.firstInstance<StateFilter>().applyTo(builder)
        filters.firstInstance<TypeFilter>().applyTo(builder)
        filters.firstInstance<SeasonFilter>().applyTo(builder)
        filters.firstInstance<LanguageFilter>().applyTo(builder)
    }

    private object AnimeUnityFiltersData {
        val ANY = Pair("Qualsiasi", "")

        val GENRE = arrayOf(
            "Action" to "1", "Adventure" to "2", "Avant Garde" to "3", "Boys Love" to "4",
            "Comedy" to "5", "Demons" to "6", "Drama" to "7", "Ecchi" to "8", "Fantasy" to "9",
            "Game" to "10", "Girls Love" to "11", "Gore" to "12", "Gourmet" to "13",
            "Harem" to "14", "Hentai" to "15", "Historical" to "16", "Horror" to "17",
            "Isekai" to "18", "Josei" to "19", "Kids" to "20", "Mahou Shoujo" to "21",
            "Martial Arts" to "22", "Mecha" to "23", "Military" to "24", "Music" to "25",
            "Mystery" to "26", "Parody" to "27", "Police" to "28", "Psychological" to "29",
            "Racing" to "30", "Reincarnation" to "31", "Romance" to "32", "Samurai" to "33",
            "School" to "34", "Sci-fi" to "35", "Seinen" to "36", "Shoujo" to "37",
            "Shounen" to "38", "Slice of Life" to "39", "Space" to "40", "Sports" to "41",
            "Super Power" to "42", "Supernatural" to "43", "Survival" to "44",
            "Thriller" to "45", "Vampire" to "46",
        )

        val ORDER = arrayOf(
            ANY,
            Pair("Lista A-Z", "title_az"),
            Pair("Lista Z-A", "title_za"),
            Pair("Ultime aggiunte", "latest"),
            Pair("Più visti", "views"),
            Pair("Popolarità", "members"),
            Pair("Valutazione", "score"),
        )

        val STATE = arrayOf(
            ANY,
            Pair("In corso", "ongoing"),
            Pair("Terminato", "completed"),
            Pair("In uscita", "upcoming"),
            Pair("Droppato", "dropped"),
        )

        val TYPE = arrayOf(
            ANY,
            Pair("TV", "tv"),
            Pair("TV Short", "tv_short"),
            Pair("Movie", "movie"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
        )

        val SEASON = arrayOf(
            ANY,
            Pair("Inverno", "winter"),
            Pair("Primavera", "spring"),
            Pair("Estate", "summer"),
            Pair("Autunno", "fall"),
        )

        val LANGUAGE = arrayOf(
            ANY,
            Pair("Italiano", "ita"),
            Pair("Giapponese", "jpn"),
            Pair("Cinese", "chn"),
            Pair("Coreano", "kor"),
            Pair("Inglese", "eng"),
        )

        val YEAR = arrayOf(ANY) + (2027 downTo 1970).map {
            Pair(it.toString(), it.toString())
        }
    }
}
