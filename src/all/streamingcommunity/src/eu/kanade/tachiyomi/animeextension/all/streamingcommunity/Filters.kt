package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import aniyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import okhttp3.HttpUrl
import java.util.Calendar

internal object Filters {

    class FeaturedFilter(intl: Intl, val uri: String = "g") :
        UriPartFilter(
            intl["featured"],
            arrayOf(
                Pair("<select>", ""),
                Pair(intl["action"], "Action"),
                Pair(intl["adventure"], "Adventure"),
                Pair(intl["animation"], "Animation"),
                Pair(intl["comedy"], "Comedy"),
                Pair(intl["crime"], "Crime"),
                Pair(intl["documentary"], "Documentary"),
                Pair(intl["drama"], "Drama"),
                Pair(intl["family"], "Family"),
                Pair(intl["fantasy"], "Fantasy"),
                Pair(intl["history"], "History"),
                Pair(intl["horror"], "Horror"),
                Pair(intl["korean_drama"], "Korean drama"),
                Pair(intl["music"], "Music"),
                Pair(intl["mystery"], "Mystery"),
                Pair(intl["news"], "News"),
                Pair(intl["romance"], "Romance"),
                Pair(intl["science_fiction"], "Science Fiction"),
                Pair(intl["thriller"], "Thriller"),
                Pair(intl["tvmovie"], "TV Movie"),
                Pair(intl["war"], "War"),
                Pair(intl["western"], "Western"),
            ),
        )

    class GenresFilter(intl: Intl, uri: String = "genre[]") :
        UriMultiSelectFilter(
            intl["genres"],
            uri,
            arrayOf(
                Pair("<select>", ""),
                Pair(intl["action"], "4"),
                Pair(intl["action_adventure"], "13"),
                Pair(intl["adventure"], "11"),
                Pair(intl["animation"], "19"),
                Pair(intl["comedy"], "12"),
                Pair(intl["crime"], "2"),
                Pair(intl["documentary"], "24"),
                Pair(intl["drama"], "1"),
                Pair(intl["family"], "16"),
                Pair(intl["fantasy"], "8"),
                Pair(intl["history"], "22"),
                Pair(intl["horror"], "7"),
                Pair(intl["kids"], "25"),
                Pair(intl["korean_drama"], "26"),
                Pair(intl["music"], "14"),
                Pair(intl["mystery"], "6"),
                Pair(intl["news"], "37"),
                Pair(intl["reality"], "18"),
                Pair(intl["romance"], "15"),
                Pair(intl["scifi_fantasy"], "3"),
                Pair(intl["science_fiction"], "10"),
                Pair(intl["soap"], "23"),
                Pair(intl["thriller"], "5"),
                Pair(intl["tvmovie"], "21"),
                Pair(intl["war"], "9"),
                Pair(intl["war_politics"], "17"),
                Pair(intl["western"], "20"),
            ),
        )

    class SortFilter(intl: Intl, val uri: String = "sort") :
        UriPartFilter(
            intl["sort_by"],
            arrayOf(
                Pair(intl["release_date"], ""),
                Pair(intl["last_air_date"], "last_air_date"),
                Pair(intl["date_added"], "created_at"),
                Pair(intl["score"], "score"),
                Pair(intl["views"], "views"),
                Pair(intl["name"], "name"),
            ),
        )

    class YearFilter(intl: Intl, val uri: String = "year") :
        UriPartFilter(
            intl["year"],
            arrayOf(Pair("<select>", "")) +
                (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).map {
                    Pair(it.toString(), it.toString())
                } +
                arrayOf(

                    Pair("1990", "1990"),
                    Pair("1980", "1980"),
                    Pair("1970", "1970"),
                    Pair("1960", "1960"),
                    Pair("1950", "1950"),
                    Pair("1940", "1940"),
                    Pair("1930", "1930"),
                    Pair("1920", "1920"),
                    Pair("1910", "1910"),
                ),
        )

    class ScoreFilter(intl: Intl, val uri: String = "score") :
        UriPartFilter(
            intl["score"],
            arrayOf(
                Pair("<select>", ""),
                Pair("1", "1"),
                Pair("2", "2"),
                Pair("3", "3"),
                Pair("4", "4"),
                Pair("5", "5"),
                Pair("6", "6"),
                Pair("7", "7"),
                Pair("8", "8"),
                Pair("9", "9"),
                Pair("10", "10"),
            ),
        )

    class ServiceFilter(intl: Intl, val uri: String = "service") :
        UriPartFilter(
            intl["service"],
            arrayOf(
                Pair("<select>", ""),
                Pair("Netflix", "netflix"),
                Pair("PrimeVideo", "prime"),
                Pair("Disney+", "disney"),
                Pair("AppleTV+", "apple"),
                Pair("NowTV", "now"),
            ),
        )

    class QualityFilter(intl: Intl, val uri: String = "quality") :
        UriPartFilter(
            intl["quality"],
            arrayOf(
                Pair("<select>", ""),
                Pair("HD", "HD"),
                Pair("SD", "SD"),
                Pair("TS", "TS"),
                Pair("CAM", "CAM"),
            ),
        )

    class AgeFilter(intl: Intl, val uri: String = "age") :
        UriPartFilter(
            intl["age"],
            arrayOf(
                Pair("<select>", ""),
                Pair("7+", "7"),
                Pair("12+", "12"),
                Pair("14+", "14"),
                Pair("16+", "16"),
                Pair("18+", "18"),
            ),
        )

    sealed class UriMultiSelectFilter(
        name: String,
        private val param: String,
        vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }) {
        fun addToUri(builder: HttpUrl.Builder) {
            val checked = state.filter { it.state }
            checked.forEach {
                builder.addQueryParameter(param, it.value)
            }
        }
    }
    class UriMultiSelectOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isEmpty() = vals[state].second == ""
        fun isDefault() = state == 0
    }
}
