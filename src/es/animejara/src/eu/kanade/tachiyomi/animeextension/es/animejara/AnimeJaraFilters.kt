package eu.kanade.tachiyomi.animeextension.es.animejara

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeJaraFilters {
    class SelectFilter(name: String, private val param: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toPair() = param to vals[state].second
    }

    private val ALL = "Todos" to ""

    private val GENRES = listOf(
        "Accion", "Amor", "Artes marciales", "Aventura", "Carreras", "Ciencia ficcion", "Comedia", "Comidas",
        "Crimen", "Demonios", "Deportes", "Drama", "Ecchi", "Escolar", "Espacial", "Espadachin", "Familia",
        "Fantasia", "Gore", "Harem", "Historico", "Isekai", "Josei", "Juegos", "Magia", "Mecha", "Militar",
        "Misterio", "Musica", "Parodia", "Psicologico", "Recuerdos", "Robots", "Romance", "Samurai", "Seinen",
        "Shoujo", "Shounen", "Sobrenatural", "Studio ghibli", "Superpoderes", "Suspenso", "Terror", "Vampiros",
        "Yaoi", "Yuri", "Zombies",
    )

    fun getFilterList() = AnimeFilterList(
        SelectFilter("Género", "tag", arrayOf(ALL) + GENRES.map { it to it }),
        SelectFilter("Tipo", "tipo", arrayOf(ALL, "Serie" to "serie", "Película" to "pelicula")),
        SelectFilter("Estado", "estado", arrayOf(ALL, "En Emisión" to "Emision", "Finalizado" to "Finalizado")),
        SelectFilter("Idioma", "idioma", arrayOf(ALL, "Latino" to "latino", "Japonés" to "japones", "Castellano" to "castellano")),
    )

    fun params(filters: AnimeFilterList) = filters.filterIsInstance<SelectFilter>().map { it.toPair() }.filter { it.second.isNotEmpty() }
}
