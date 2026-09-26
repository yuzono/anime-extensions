package eu.kanade.tachiyomi.animeextension.es.animemovil

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstanceOrNull

object Filters {

    internal class GenreFilter : QueryPartFilter("Géneros", Data.GENRE)
    internal class TypeFilter : QueryPartFilter("Tipos", Data.TYPE)
    internal class StatusFilter : QueryPartFilter("Estados", Data.STATUS)

    val FILTER_LIST: AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora los filtros"),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
        val type: String = "",
        val status: String = "",
    )

    internal open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    internal object Data {
        val GENRE = arrayOf(
            Pair("Seleccionar", ""),
            Pair("3D", "3d"),
            Pair("Acción", "accion"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Antropomórfico", "antropomorfico"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Bondage", "bondage"),
            Pair("Carreras", "carreras"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Deportes", "deportes"),
            Pair("Detectives", "detectives"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Elenco Adulto", "elenco-adulto"),
            Pair("Elfas", "elfas"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Futanari", "futanari"),
            Pair("Gal", "gal"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Hardcore", "hardcore"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Idols (Hombre)", "idols-hombre"),
            Pair("Idols (Mujer)", "idols-mujer"),
            Pair("Incesto", "incesto"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Juegos Estrategia", "juegos-estrategia"),
            Pair("Juegos Sexuales", "juegos-sexuales"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Maids", "maids"),
            Pair("Mecha", "mecha"),
            Pair("Milfs", "milfs"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Mitología", "mitologia"),
            Pair("Música", "musica"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomania", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgias", "orgias"),
            Pair("Oyakodon", "oyakodon"),
            Pair("Paizuri", "paizuri"),
            Pair("Parodia", "parodia"),
            Pair("Petit", "petit"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la Vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shota", "shota"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Softcore", "softcore"),
            Pair("Succubus", "succubus"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Teacher", "teacher"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Terror", "terror"),
            Pair("Tetonas", "tetonas"),
            Pair("Threesome", "threesome"),
            Pair("Vampiros", "vampiros"),
            Pair("Vanilla", "vanilla"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes", "virgenes"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val TYPE = arrayOf(
            Pair("Seleccionar", ""),
            Pair("TV Anime", "TV Anime"),
            Pair("Película", "Película"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Especial", "Especial"),
        )

        val STATUS = arrayOf(
            Pair("Seleccionar", ""),
            Pair("Finalizado", "finished"),
            Pair("En emisión", "airing"),
            Pair("Próximamente", "upcoming"),
        )
    }
}

internal fun AnimeFilterList.getSearchParameters(): Filters.FilterSearchParams = Filters.FilterSearchParams(
    genre = firstInstanceOrNull<Filters.GenreFilter>()?.toQueryPart().orEmpty(),
    type = firstInstanceOrNull<Filters.TypeFilter>()?.toQueryPart().orEmpty(),
    status = firstInstanceOrNull<Filters.StatusFilter>()?.toQueryPart().orEmpty(),
)
