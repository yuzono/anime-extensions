package eu.kanade.tachiyomi.animeextension.pt.animesotaku

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class AnimeCoreFilters(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    private var error = false

    private lateinit var filterList: AnimeFilterList

    interface QueryParameterFilter {
        fun toQueryParameter(): Pair<String, List<String>>
    }

    private class Checkbox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private class CheckboxList(
        name: String,
        private val paramName: String,
        private val pairs: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        pairs.map { Checkbox(it.first) },
    ),
        QueryParameterFilter {
        override fun toQueryParameter(): Pair<String, List<String>> {
            val lookup = pairs.associate { it.first to it.second }
            val selectedValues = state.asSequence()
                .filter { it.state }
                .mapNotNull { checkbox -> lookup[checkbox.name]?.ifBlank { null } }
                .toList()

            return Pair(paramName, selectedValues)
        }
    }

    private class SelectFilter(
        name: String,
        private val paramName: String,
        private val pairs: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        name,
        pairs.map { it.first }.toTypedArray(),
    ),
        QueryParameterFilter {
        override fun toQueryParameter(): Pair<String, List<String>> {
            val selectedValue = pairs[state].second
            return Pair(paramName, listOf(selectedValue))
        }
    }

    fun filterInitialized(): Boolean = this::filterList.isInitialized

    fun getFilterList(): AnimeFilterList = if (error) {
        AnimeFilterList(AnimeFilter.Header("Erro ao buscar os filtros."))
    } else if (filterInitialized()) {
        filterList
    } else {
        AnimeFilterList(AnimeFilter.Header("Use 'Redefinir' para carregar os filtros."))
    }

    fun fetchFilters() {
        if (!filterInitialized()) {
            runCatching {
                error = false
                val document = client.newCall(GET("$baseUrl/busca-detalhada"))
                    .execute().use {
                        it.asJsoup()
                    }
                filterList = filtersParse(document)
            }.onFailure {
                error = true
            }
        }
    }

    private fun filtersParse(document: Document): AnimeFilterList {
        val genresFilters = CheckboxList(
            "Gênero",
            "genre[]",
            document.select("#genre-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val statusFilters = CheckboxList(
            "Status",
            "status[]",
            document.select("#status-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val typeFilters = CheckboxList(
            "Tipo",
            "type[]",
            document.select("#type-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val studioFilters = CheckboxList(
            "Estúdio",
            "studio[]",
            document.select("#studio-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val producerFilters = CheckboxList(
            "Produtor",
            "producer[]",
            document.select("#producer-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val seasonFilters = CheckboxList(
            "Temporada",
            "season[]",
            document.select("#season-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        val premieredFilters = CheckboxList(
            "Estreia",
            "premiered[]",
            document.select("#premiered-content input[type='checkbox']").mapNotNull { input ->
                input.nextElementSibling()?.let {
                    Pair(it.text(), input.attr("value"))
                }
            },
        )

        // Select 1: Ordenar por
        val orderFilter = SelectFilter(
            "Ordenar por",
            "orderby",
            document.select("select[name='orderby'] option").map { option ->
                Pair(option.text().trim().ifEmpty { option.attr("value") }, option.attr("value"))
            },
        )

        // Select 2: Order direction
        val directionFilter = SelectFilter(
            "Ordem",
            "order",
            document.select("select[name='order'] option").map { option ->
                Pair(option.text().trim().ifEmpty { option.attr("value") }, option.attr("value"))
            },
        )

        return AnimeFilterList(
            genresFilters,
            statusFilters,
            typeFilters,
            studioFilters,
            producerFilters,
            seasonFilters,
            premieredFilters,
            AnimeFilter.Separator(),
            orderFilter,
            directionFilter,
        )
    }
}
