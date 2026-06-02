package eu.kanade.tachiyomi.animeextension.pt.animeq

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay.UriPartFilter

object AnimeQFilters {
    class AudioFilter :
        UriPartFilter(
            "Áudio",
            arrayOf(
                Pair("Todos", ""),
                Pair("Dublado", "tipo/dublado"),
                Pair("Legendado", "tipo/legendado"),
            ),
        )

    abstract class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected
            get() = options[state].second
    }

    class OrderByFilter :
        SelectFilter(
            "Ordenar Por",
            arrayOf(
                Pair("Data de Criação", "date"),
                Pair("Data de Modificação", "modified"),
                Pair("Título", "title"),
            ),
        )

    class OrderFilter :
        SelectFilter(
            "Ordem",
            arrayOf(
                Pair("Descendente", "desc"),
                Pair("Ascendente", "asc"),
            ),
        )

    data class FilterSearchParams(
        val orderBy: String? = null,
        val order: String? = null,
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        val orderByFilter = filters.find { it is OrderByFilter } as? OrderByFilter
        val orderFilter = filters.find { it is OrderFilter } as? OrderFilter
        return FilterSearchParams(
            orderBy = orderByFilter?.selected,
            order = orderFilter?.selected,
        )
    }
}
