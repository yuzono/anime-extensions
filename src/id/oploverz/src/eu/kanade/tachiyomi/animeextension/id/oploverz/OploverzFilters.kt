package eu.kanade.tachiyomi.animeextension.id.oploverz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstanceOrNull

object OploverzFilters {
    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genre", GENRE_LIST.map { GenreCheckBox(it.first) })

    val FILTER_LIST get() = AnimeFilterList(GenreFilter())

    fun getGenreParam(filters: AnimeFilterList): String = filters.firstInstanceOrNull<GenreFilter>()
        ?.state
        ?.filter { it.state }
        ?.mapNotNull { checkbox -> GENRE_LIST.find { it.first == checkbox.name }?.second }
        ?.joinToString(",")
        ?: ""

    private val GENRE_LIST = arrayOf(
        Pair("+18", "18"),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Cars", "cars"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Demons", "demons"),
        Pair("Donghua", "donghua"),
        Pair("Drama", "drama"),
        Pair("Drive", "drive"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Game", "game"),
        Pair("Gore", "gore"),
        Pair("Gourmet", "gourmet"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Isekai", "isekai"),
        Pair("Josei", "josei"),
        Pair("Live Action", "live-action"),
        Pair("Magic", "magic"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mecha", "mecha"),
        Pair("Medical", "medical"),
        Pair("Military", "military"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Mythology", "mythology"),
        Pair("OLM", "olm"),
        Pair("Parody", "parody"),
        Pair("Police", "police"),
        Pair("Psychological", "psychological"),
        Pair("Racing", "racing"),
        Pair("Reincarnation", "reincarnation"),
        Pair("Romance", "romance"),
        Pair("Samurai", "samurai"),
        Pair("School", "school"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Showbiz", "showbiz"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Space", "space"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Super Power", "super-power"),
        Pair("Survival", "survival"),
        Pair("Suspense", "suspense"),
        Pair("Thriller", "thriller"),
        Pair("Vampire", "vampire"),
        Pair("Zero-G", "zero-g"),
    )
}
