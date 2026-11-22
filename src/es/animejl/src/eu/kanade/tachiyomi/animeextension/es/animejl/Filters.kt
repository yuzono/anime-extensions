package eu.kanade.tachiyomi.animeextension.es.animejl

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstanceOrNull
import java.util.Calendar

object Filters {
    fun getQueryParameters(filters: AnimeFilterList): List<Pair<String, String>> = buildList {
        filters.firstInstanceOrNull<GenresFilter>()?.let { addAll(it.toQueryParams("genre")) }
        filters.firstInstanceOrNull<YearsFilter>()?.let { addAll(it.toQueryParams("year")) }
        filters.firstInstanceOrNull<TypesFilter>()?.let { addAll(it.toQueryParams("tipo")) }
        filters.firstInstanceOrNull<StateFilter>()?.let { addAll(it.toQueryParams("estado")) }
        filters.firstInstanceOrNull<SortFilter>()?.toQueryParam("order")?.let { add(it) }
    }

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
            GenresFilter(),
            YearsFilter(),
            TypesFilter(),
            StateFilter(),
            SortFilter(),
        )

    open class QueryPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        state: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun toQueryParam(name: String): Pair<String, String>? = vals[state].second.takeIf { it.isNotEmpty() }?.let { name to it }
    }

    class CheckBoxVal(name: String, val value: String) : AnimeFilter.CheckBox(name)

    open class CheckBoxFilterList(name: String, values: List<CheckBoxVal>) : AnimeFilter.Group<CheckBoxVal>(name, values) {
        fun toQueryParams(name: String): List<Pair<String, String>> = state.filter { checkbox -> checkbox.state }.map { "$name[]" to it.value }
    }

    class GenresFilter : CheckBoxFilterList("Género", FilterData.GENRES.map { CheckBoxVal(it.first, it.second) })

    class YearsFilter : CheckBoxFilterList("Año", FilterData.YEARS.map { CheckBoxVal(it.first, it.second) })

    class TypesFilter : CheckBoxFilterList("Tipo", FilterData.TYPES.map { CheckBoxVal(it.first, it.second) })

    class StateFilter : CheckBoxFilterList("Estado", FilterData.STATE.map { CheckBoxVal(it.first, it.second) })

    // Matches the popular listing, which is ordered by rating
    class SortFilter :
        QueryPartFilter(
            "Orden",
            FilterData.SORT,
            FilterData.SORT.indexOfFirst { it.second == "rating" },
        )

    private object FilterData {
        val YEARS = (Calendar.getInstance().get(Calendar.YEAR) downTo 1970)
            .map { it.toString() to it.toString() }
            .toTypedArray()

        val TYPES = arrayOf(
            "Anime" to "1",
            "Ova" to "2",
            "Película" to "3",
            "Donghua" to "7",
        )

        val STATE = arrayOf(
            "En emisión" to "0",
            "Finalizado" to "1",
            "Estreno" to "2",
        )

        val SORT = arrayOf(
            "Por Defecto" to "created",
            "Recientemente Actualizados" to "updated",
            "Nombre A-Z" to "titleaz",
            "Nombre Z-A" to "titleza",
            "Calificación" to "rating",
            "Vistas" to "views",
        )

        val GENRES = arrayOf(
            "Acción" to "1",
            "Artes Marciales" to "2",
            "Aventuras" to "3",
            "Ciencia Ficción" to "33",
            "Comedia" to "9",
            "Cultivación" to "71",
            "Demencia" to "40",
            "Demonios" to "42",
            "Deportes" to "27",
            "Donghua" to "50",
            "Drama" to "10",
            "Ecchi" to "25",
            "Escolares" to "22",
            "Espacial" to "48",
            "Fantasía" to "6",
            "Gore" to "67",
            "Harem" to "32",
            "Hentai" to "31",
            "Histórico" to "43",
            "Horror" to "39",
            "Isekai" to "45",
            "Josei" to "70",
            "Juegos" to "11",
            "Latino / Castellano" to "46",
            "Magia" to "38",
            "Mecha" to "41",
            "Militar" to "44",
            "Misterio" to "26",
            "Mitología" to "73",
            "Música" to "28",
            "Parodia" to "13",
            "Policía" to "51",
            "Psicológico" to "29",
            "Recuentos de la vida" to "23",
            "Reencarnación" to "72",
            "Romance" to "12",
            "Samurai" to "69",
            "Seinen" to "24",
            "Shoujo" to "36",
            "Shounen" to "4",
            "Sin Censura" to "68",
            "Sobrenatural" to "7",
            "Superpoderes" to "5",
            "Suspenso" to "21",
            "Terror" to "20",
            "Vampiros" to "49",
            "Venganza" to "74",
            "Video Games" to "78",
            "Wuxia" to "75",
            "Xianxia" to "76",
            "Xuanhuan" to "77",
            "Yaoi" to "53",
            "Yuri" to "52",
        )
    }
}
