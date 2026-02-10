package eu.kanade.tachiyomi.animeextension.es.jkanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

interface UriPartFilterInterface {
    fun toQueryParam(): Pair<String, String>?
}

open class SelectFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {

    fun toValue() = vals[state].second
}

open class UriPartSelectFilter(
    displayName: String,
    val keyName: String,
    vals: Array<Pair<String, String>>,
    val includeZero: Boolean = false,
) : SelectFilter(displayName, vals),
    UriPartFilterInterface {

    override fun toQueryParam(): Pair<String, String>? {
        val value = toValue()
        return value.takeIf { it.isNotBlank() && (includeZero || state != 0) }?.let { keyName to it }
    }
}

class GenreFilter : UriPartSelectFilter("Género", "genero", VerAnimesFiltersData.GENRES)
class LetterFilter : UriPartSelectFilter("Letra", "letra", VerAnimesFiltersData.LETTER)
class DemographyFilter : UriPartSelectFilter("Demografía", "demografia", VerAnimesFiltersData.DEMOGRAPHY)
class CategoryFilter : UriPartSelectFilter("Categoría", "categoria", VerAnimesFiltersData.CATEGORY)
class TypeFilter : UriPartSelectFilter("Tipo", "tipo", VerAnimesFiltersData.TYPES)
class StateFilter : UriPartSelectFilter("Estado", "estado", VerAnimesFiltersData.STATE)
class YearFilter : UriPartSelectFilter("Año", "fecha", VerAnimesFiltersData.YEARS)
class SeasonFilter : UriPartSelectFilter("Temporada", "temporada", VerAnimesFiltersData.SEASONS)
class OrderByFilter : UriPartSelectFilter("Ordenar Por", "filtro", VerAnimesFiltersData.SORT_BY)
class SortModifiers : UriPartSelectFilter("Orden", "orden", VerAnimesFiltersData.SORT, includeZero = true)

class DayFilter :
    SelectFilter(
        "Dia de emisión",
        VerAnimesFiltersData.DAYS,
    )

private object VerAnimesFiltersData {
    val GENRES = arrayOf(
        Pair("<Seleccionar>", ""),
        Pair("Accion", "accion"),
        Pair("Aventura", "aventura"),
        Pair("Autos", "autos"),
        Pair("Comedia", "comedia"),
        Pair("Dementia", "dementia"),
        Pair("Demonios", "demonios"),
        Pair("Misterio", "misterio"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasia", "fantasia"),
        Pair("Juegos", "juegos"),
        Pair("Hentai", "hentai"),
        Pair("Historico", "historico"),
        Pair("Terror", "terror"),
        Pair("Niños", "nios"),
        Pair("Magia", "magia"),
        Pair("Artes Marciales", "artes-marciales"),
        Pair("Mecha", "mecha"),
        Pair("Musica", "musica"),
        Pair("Parodia", "parodia"),
        Pair("Samurai", "samurai"),
        Pair("Romance", "romance"),
        Pair("Colegial", "colegial"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Space", "space"),
        Pair("Deportes", "deportes"),
        Pair("Super Poderes", "super-poderes"),
        Pair("Vampiros", "vampiros"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri"),
        Pair("Harem", "harem"),
        Pair("Cosas de la vida", "cosas-de-la-vida"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Militar", "militar"),
        Pair("Policial", "policial"),
        Pair("Psicologico", "psicologico"),
        Pair("Thriller", "thriller"),
        Pair("Seinen", "seinen"),
        Pair("Josei", "josei"),
        Pair("Español Latino", "latino"),
        Pair("Isekai", "isekai"),
    )

    val DAYS = arrayOf(
        Pair("<Seleccionar>", ""),
        Pair("Lunes", "Lunes"),
        Pair("Martes", "Martes"),
        Pair("Miércoles", "Miércoles"),
        Pair("Jueves", "Jueves"),
        Pair("Viernes", "Viernes"),
        Pair("Sábado", "Sábado"),
        Pair("Domingo", "Domingo"),
    )

    val LETTER = arrayOf(Pair("Todos", "")) + ('A'..'Z').map { Pair("$it", "$it") }.toTypedArray()

    val DEMOGRAPHY = arrayOf(
        Pair("Todos", ""),
        Pair("Niños", "nios"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Seinen", "seinen"),
        Pair("Josei", "josei"),
    )

    val CATEGORY = arrayOf(
        Pair("Todos", ""),
        Pair("Donghua", "donghua"),
        Pair("Latino", "latino"),
    )

    val TYPES = arrayOf(
        Pair("<Seleccionar>", ""),
        Pair("Animes", "animes"),
        Pair("Películas", "peliculas"),
        Pair("Especiales", "especiales"),
        Pair("OVAS", "ovas"),
        Pair("ONAS", "onas"),
    )

    val STATE = arrayOf(
        Pair("<Cualquiera>", ""),
        Pair("En Emisión", "emision"),
        Pair("Finalizado", "finalizados"),
        Pair("Por Estrenar", "estrenos"),
    )

    val YEARS = arrayOf(Pair("Todos", "")) + (1981..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

    val SEASONS = arrayOf(
        Pair("<Cualquiera>", ""),
        Pair("Primavera", "primavera"),
        Pair("Verano", "verano"),
        Pair("Otoño", "otoño"),
        Pair("Invierno", "invierno"),
    )

    val SORT_BY = arrayOf(
        Pair("Por fecha", ""),
        Pair("Por nombre", "nombre"),
        Pair("Por popularidad", "popularidad"),
    )

    val SORT = arrayOf(
        Pair("Descendente", ""),
        Pair("Ascendente", "asc"),
    )
}
