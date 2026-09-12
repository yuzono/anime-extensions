package eu.kanade.tachiyomi.animeextension.en.animetake

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance
import java.util.Calendar

object Filters {
    open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> = (firstInstance<R>() as CheckBoxFilterList).state
        .filter { it.state }
        .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
        .filter(String::isNotBlank)

    internal class LetterFilter : CheckBoxFilterList("Letter", FiltersData.LETTER)
    internal class GenresFilter : CheckBoxFilterList("Genre", FiltersData.GENRE)
    internal class ScoreFilter : CheckBoxFilterList("Score", FiltersData.SCORE)
    internal class YearFilter : CheckBoxFilterList("Year", FiltersData.YEAR)
    internal class RatingFilter : CheckBoxFilterList("Rating", FiltersData.RATING)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Note: Ignores search"),
            LetterFilter(),
            GenresFilter(),
            ScoreFilter(),
            YearFilter(),
            RatingFilter(),
        )

    internal data class FilterSearchParams(
        val letters: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val score: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val ratings: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<LetterFilter>(FiltersData.LETTER),
            filters.parseCheckbox<GenresFilter>(FiltersData.GENRE),
            filters.parseCheckbox<ScoreFilter>(FiltersData.SCORE),
            filters.parseCheckbox<YearFilter>(FiltersData.YEAR),
            filters.parseCheckbox<RatingFilter>(FiltersData.RATING),
        )
    }

    private object FiltersData {
        val LETTER = ('A'..'Z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRE = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Chinese", "Chinese"),
            Pair("Comedy", "Comedy"),
            Pair("Detective", "Detective"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Gourmet", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("High Stakes Game", "High+Stakes+Game"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial+Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Mythology", "Mythology"),
            Pair("Parody", "Parody"),
            Pair("Psychological", "Psychological"),
            Pair("Racing", "Racing"),
            Pair("Reincarnation", "Reincarnation"),
            Pair("Romance", "Romance"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo+Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen+Ai"),
            Pair("Slice of Life", "Slice+of+Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Strategy Game", "Strategy+Game"),
            Pair("Super Power", "Super+Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Survival", "Survival"),
            Pair("Suspense", "Suspense"),
            Pair("Team Sports", "Team+Sports"),
            Pair("Time Travel", "Time+Travel"),
            Pair("Vampire", "Vampire"),
            Pair("Video Game", "Video+Game"),
        )

        val SCORE = arrayOf(
            Pair("Masterpiece (9+)", "masterpiece"),
            Pair("Fantastic (8+)", "fantastic"),
            Pair("Very Good+(7+)", "verygood"),
            Pair("Fine (6+)", "fine"),
            Pair("Average (5+)", "average"),
            Pair("Bad (4+)", "bad"),
            Pair("Very Bad (3+)", "verybad"),
            Pair("Unwatchable (2+)", "unwatchable"),
        )

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val YEAR = (
            (currentYear downTo 2000).map { Pair(it.toString(), it.toString()) } + arrayOf(
                Pair("1990-1999", "1990"),
                Pair("1980-1989", "1980"),
                Pair("1970-1979", "1970"),
            )
            ).toTypedArray()

        val RATING = arrayOf(
            Pair("G - All Ages", "allages"),
            Pair("PG 13 - Teens 13 and Older", "pg13"),
            Pair("R - 17+, Violence & Profanity", "r17"),
            Pair("R+ - Profanity & Mild Nudity", "rplus"),
        )
    }
}
