package eu.kanade.tachiyomi.animeextension.en.xanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    open class SelectFilter(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values) {
        fun getValue(map: Map<String, String>): String = map[values[state]] ?: ""
    }

    open class TextFilter(name: String, state: String) : AnimeFilter.Text(name, state)
    open class TriStateFilter(name: String) : AnimeFilter.TriState(name)
    open class GroupFilter<V : AnimeFilter<*>>(name: String, state: List<V>) : AnimeFilter.Group<V>(name, state)

    class SortFilter : SelectFilter("Sort By", SORT_MAP.keys.toTypedArray())
    class TypeFilter : SelectFilter("Type", TYPE_MAP.keys.toTypedArray())
    class StatusFilter : SelectFilter("Status", STATUS_MAP.keys.toTypedArray())
    class AudioFilter : SelectFilter("Audio", AUDIO_MAP.keys.toTypedArray())
    class EpCountFilter : SelectFilter("Episode Count", EP_COUNT_MAP.keys.toTypedArray())
    class SeasonFilter : SelectFilter("Season", SEASON_MAP.keys.toTypedArray())

    class GenreGroup : GroupFilter<TristateGenre>("Genres", GENRE_MAP.keys.sortedBy { it.lowercase() }.map { TristateGenre(it) })
    class TristateGenre(name: String) : TriStateFilter(name)

    class YearFromFilter : TextFilter("Year From e.g. 1901", "")
    class YearToFilter : TextFilter("Year To e.g. ${Calendar.getInstance().get(Calendar.YEAR) + 2}", "")

    val SORT_MAP = mapOf(
        "Score" to "field_score",
        "Newest" to "field_date_create",
        "Updated" to "field_update",
        "Popular" to "field_popularity",
        "Year" to "field_year",
        "A-Z" to "field_title",
    )

    val TYPE_MAP = mapOf(
        "Any" to "",
        "TV" to "TV",
        "Movie" to "Movie",
        "OVA" to "OVA",
        "Special" to "Special",
        "ONA" to "ONA",
        "Music" to "Music",
    )

    val STATUS_MAP = mapOf(
        "Any" to "",
        "Finished Airing" to "finished_airing",
        "Currently Airing" to "currently_airing",
        "Not Yet Aired" to "not_yet_aired",
    )

    val AUDIO_MAP = mapOf(
        "Any" to "",
        "Sub" to "sub",
        "Raw" to "raw",
        "Dub" to "dub",
    )

    val EP_COUNT_MAP = mapOf(
        "Any" to "",
        "1+" to "1", "10+" to "10", "20+" to "20", "30+" to "30", "40+" to "40", "50+" to "50",
        "60+" to "60", "70+" to "70", "80+" to "80", "90+" to "90", "100+" to "100",
        "200+" to "200", "300+" to "300", "299~200" to "200-299", "199~100" to "100-199",
        "99~90" to "90-99", "89~80" to "80-89", "79~70" to "70-79", "69~60" to "60-69",
        "59~50" to "50-59", "49~40" to "40-49", "39~30" to "30-39", "29~20" to "20-29",
        "19~10" to "10-19", "9~1" to "1-9", "0" to "0",
    )

    val SEASON_MAP = mapOf(
        "Any" to "",
        "Spring" to "Spring",
        "Summer" to "Summer",
        "Fall" to "Fall",
        "Winter" to "Winter",
    )
}
