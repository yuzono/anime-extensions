package eu.kanade.tachiyomi.animeextension.ru.yummyani

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object YummyaniFilters {

    open class TriStateFilterList(name: String, values: List<AnimeFilter.TriState>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)
    class TriFilterVal(name: String) : AnimeFilter.TriState(name)

    class GenresFilter : TriStateFilterList("Жанр", YummyaniFiltersData.GENRES.map { TriFilterVal(it.first) })

    private inline fun <reified R> AnimeFilterList.getFirst(): R = first { it is R } as R

    private inline fun <reified R> AnimeFilterList.parseTriFilter(options: Array<Pair<String, String>>): IncludeExcludeParams = (getFirst<R>() as TriStateFilterList).state
        .filterNot { it.isIgnored() }
        .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
        .groupBy { it.first }
        .let { dict ->
            val included = dict[AnimeFilter.TriState.STATE_INCLUDE]?.map { it.second }.orEmpty()
            val excluded = dict[AnimeFilter.TriState.STATE_EXCLUDE]?.map { it.second }.orEmpty()
            IncludeExcludeParams(included, excluded)
        }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class KindFilter : CheckBoxFilterList("Тип аниме", YummyaniFiltersData.KINDS.map { CheckBoxVal(it.first) })
    class StatusFilter : CheckBoxFilterList("Статус тайтла", YummyaniFiltersData.STATUSES.map { CheckBoxVal(it.first) })
    class SeasonFilter : CheckBoxFilterList("Сезон", YummyaniFiltersData.SEASONS.map { CheckBoxVal(it.first) })

    private inline fun <reified R> AnimeFilterList.parseCheckbox(options: Array<Pair<String, String>>): List<String> = (getFirst<R>() as CheckBoxFilterList).state
        .asSequence()
        .filter { it.state }
        .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
        .toList()

    class SortFilter :
        AnimeFilter.Sort(
            "Сортировать по",
            YummyaniFiltersData.ORDERS.map { it.first }.toTypedArray(),
            Selection(0, false),
        )

    val FILTER_LIST get() = AnimeFilterList(
        GenresFilter(),
        KindFilter(),
        StatusFilter(),
        SeasonFilter(),
        SortFilter(),
    )

    data class IncludeExcludeParams(
        val include: List<String> = emptyList(),
        var exclude: List<String> = emptyList(),
    )

    data class FilterSearchParams(
        val genres: List<String> = emptyList(),
        val kind: String? = null,
        val status: String? = null,
        val season: String? = null,
        val rating: Int? = null,
        val sortOrder: String = YummyaniFiltersData.ORDERS[0].second,
        val sortDirection: String = "desc",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val sortFilter = filters.getFirst<SortFilter>()
        val sortDirection = if (sortFilter.state?.ascending == true) "asc" else "desc"

        val sortOrder = sortFilter.state?.let {
            YummyaniFiltersData.ORDERS[it.index].second
        } ?: ""

        val genresParams = filters.parseTriFilter<GenresFilter>(YummyaniFiltersData.GENRES)
        val kindList = filters.parseCheckbox<KindFilter>(YummyaniFiltersData.KINDS)
        val statusList = filters.parseCheckbox<StatusFilter>(YummyaniFiltersData.STATUSES)
        val seasonList = filters.parseCheckbox<SeasonFilter>(YummyaniFiltersData.SEASONS)

        return FilterSearchParams(
            genres = genresParams.include,
            kind = kindList.firstOrNull(),
            status = statusList.firstOrNull(),
            season = seasonList.firstOrNull(),
            sortOrder = sortOrder,
            sortDirection = sortDirection,
        )
    }

    private object YummyaniFiltersData {
        val GENRES = arrayOf(
            Pair("Боевик", "action"),
            Pair("Комедия", "comedy"),
            Pair("Фэнтези", "fantasy"),
            Pair("Романтика", "romance"),
            Pair("Драма", "drama"),
            Pair("Приключения", "adventure"),
            Pair("Мистика", "mystery"),
            Pair("Ужасы", "horror"),
            Pair("Триллер", "thriller"),
            Pair("Сэйнэн", "seinen"),
            Pair("Сёнэн", "shonen"),
            Pair("Сёдзё", "shojo"),
            Pair("Экшен", "action"),
            Pair("Психология", "psychological"),
            Pair("Фантастика", "scifi"),
            Pair("Спорт", "sports"),
            Pair("Музыка", "music"),
            Pair("Школа", "school"),
            Pair("Повседневность", "slice_of_life"),
            Pair("Исторический", "historical"),
            Pair("Магия", "magic"),
            Pair("Меха", "mecha"),
            Pair("Сверхъестественное", "supernatural"),
            Pair("Гарем", "harem"),
            Pair("Демоны", "demons"),
            Pair("Самураи", "samurai"),
            Pair("Эротика", "ecchi"),
            Pair("Игра", "game"),
            Pair("Военный", "military"),
            Pair("Вампиры", "vampires"),
            Pair("Юмор", "humor"),
            Pair("Боевые искусства", "martial_arts"),
        )

        val KINDS = arrayOf(
            Pair("TV сериал", "tv"),
            Pair("Фильм", "movie"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Спешл", "special"),
            Pair("Клип", "music"),
        )

        val STATUSES = arrayOf(
            Pair("Онгоинг", "1"),
            Pair("Завершён", "2"),
            Pair("Анонс", "3"),
            Pair("Приостановлен", "4"),
        )

        val SEASONS = arrayOf(
            Pair("Зима 2024", "winter_2024"),
            Pair("Весна 2024", "spring_2024"),
            Pair("Лето 2024", "summer_2024"),
            Pair("Осень 2024", "fall_2024"),
            Pair("Зима 2023", "winter_2023"),
            Pair("Весна 2023", "spring_2023"),
            Pair("Лето 2023", "summer_2023"),
            Pair("Осень 2023", "fall_2023"),
        )

        val ORDERS = arrayOf(
            Pair("Популярности", "rating"),
            Pair("Рейтингу", "score"),
            Pair("Просмотрам", "views"),
            Pair("Дате добавления", "created_at"),
            Pair("Названию", "name"),
        )
    }
}
