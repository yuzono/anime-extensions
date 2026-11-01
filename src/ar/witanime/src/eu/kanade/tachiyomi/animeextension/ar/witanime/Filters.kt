package eu.kanade.tachiyomi.animeextension.ar.witanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class UriPartFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter : AnimeFilter.Group<CheckBoxVal>("التصنيفات", GENRES.map { CheckBoxVal(it.first, false) }) {
        fun getSelectedValues(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) GENRES[index].second else null
        }
    }

    class StatusFilter : UriPartFilter("الحالة", STATUSES)
    class TypeFilter : UriPartFilter("النوع", TYPES)
    class SortFilter : UriPartFilter("الترتيب", SORTS)
    class SeasonFilter : UriPartFilter("الموسم", SEASONS)
    class YearFilter : AnimeFilter.Text("السنة")

    fun build(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("استخدم التصفية عند ترك حقل البحث فارغاً"),
        StatusFilter(),
        TypeFilter(),
        SortFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
    )

    private val STATUSES = arrayOf(
        Pair("الكل", ""),
        Pair("يعرض الان", "ongoing"),
        Pair("مكتمل", "completed"),
        Pair("لم يعرض بعد", "upcoming"),
    )

    private val TYPES = arrayOf(
        Pair("الكل", ""),
        Pair("TV", "TV"),
        Pair("Movie", "Movie"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA"),
        Pair("Special", "Special"),
        Pair("TV Short", "TV Short"),
        Pair("Music", "Music"),
    )

    private val SORTS = arrayOf(
        Pair("الافتراضي", ""),
        Pair("الأعلى تقييماً", "Top Rated"),
        Pair("الأكثر مشاهدة", "Most Viewed"),
        Pair("أ-ي", "A-Z"),
        Pair("ي-أ", "Z-A"),
    )

    private val SEASONS = arrayOf(
        Pair("الكل", ""),
        Pair("ربيع", "spring"),
        Pair("صيف", "summer"),
        Pair("خريف", "fall"),
        Pair("شتاء", "winter"),
    )

    private val GENRES = listOf(
        Pair("أكشن", "action"),
        Pair("مغامرات", "adventure"),
        Pair("كوميديا", "comedy"),
        Pair("دراما", "drama"),
        Pair("خيال", "fantasy"),
        Pair("سحر", "magic"),
        Pair("غموض", "mystery"),
        Pair("نفسي", "psychological"),
        Pair("رومانسي", "romance"),
        Pair("خيال علمي", "sci-fi"),
        Pair("شريحة من الحياة", "slice-of-life"),
        Pair("رياضي", "sports"),
        Pair("خارق للطبيعة", "supernatural"),
        Pair("إثارة", "thriller"),
        Pair("شونين", "shounen"),
        Pair("شوجو", "shoujo"),
        Pair("سينين", "seinen"),
        Pair("جوسي", "josei"),
        Pair("مدرسي", "school"),
        Pair("موسيقى", "music"),
        Pair("شياطين", "demons"),
        Pair("مصاصي دماء", "vampires"),
        Pair("تاريخي", "historical"),
        Pair("ساموراي", "samurai"),
        Pair("فضاء", "space"),
        Pair("ميكا", "mecha"),
        Pair("عسكري", "military"),
        Pair("قوى خارقة", "super-power"),
        Pair("ألعاب", "game"),
        Pair("حريم", "harem"),
        Pair("إيتشي", "ecchi"),
        Pair("ايسيكاي", "isekai"),
        Pair("وحوش", "monsters"),
        Pair("بوليسي", "police"),
    )
}
