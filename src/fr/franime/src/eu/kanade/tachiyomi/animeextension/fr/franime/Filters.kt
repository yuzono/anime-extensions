package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstanceOrNull
import java.util.Calendar

private val THEMES = listOf(
    "Action",
    "Alien",
    "Arts martiaux",
    "Aventure",
    "Comédie",
    "Demon",
    "Drame",
    "Ecchi",
    "École",
    "Fantaisie",
    "Harem",
    "Hentai",
    "Historique",
    "Horreur",
    "Isekai",
    "Josei",
    "Magie",
    "Mecha",
    "Militaire",
    "Musique",
    "Mystère",
    "Parodie",
    "Police",
    "Psychologique",
    "Romance",
    "School Life",
    "Science-fiction",
    "Seinen",
    "Shoujo",
    "Shoujo ai",
    "Shounen",
    "Shounen ai",
    "Slice of Life",
    "Sport",
    "Super pouvoir",
    "Surnaturel",
    "Thriller",
    "Tranche de vie",
    "Vampire",
    "Violence",
    "Yaoi",
    "Yuri",
)

private val FORMATS = listOf("Tout", "Série", "Film", "ONA", "OVA", "Special")
private val STATUSES = listOf("Tout", "Terminé", "En cours")
private val SORTS = listOf("Ressemblance", "Mieux noté", "Moins bien noté", "A-Z", "Z-A")
private val TYPES = listOf("VOSTFR & VF", "VOSTFR", "VF")
private val SEASONS = listOf("Tout", "Hiver", "Printemps", "Été", "Automne")

private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

private val YEARS = listOf("Tout") + (currentYear downTo 1970).map(Int::toString)

private class ThemeCheckBox(name: String) : AnimeFilter.CheckBox(name)

class ThemeFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Thèmes", THEMES.map { ThemeCheckBox(it) })

class FormatFilter : AnimeFilter.Select<String>("Format", FORMATS.toTypedArray()) {
    val value: String? get() = state.takeUnless { it == 0 }?.let { FORMATS[it] }
}

class StatusFilter : AnimeFilter.Select<String>("Statut", STATUSES.toTypedArray()) {
    val value: String? get() = state.takeUnless { it == 0 }?.let { STATUSES[it] }
}

class SortFilter : AnimeFilter.Select<String>("Trier par", SORTS.toTypedArray()) {
    val value: String get() = SORTS[state]
}

class TypeFilter : AnimeFilter.Select<String>("Type", TYPES.toTypedArray()) {
    val value: String? get() = state.takeUnless { it == 0 }?.let { TYPES[it] }
}

class YearFilter : AnimeFilter.Select<String>("Année", YEARS.toTypedArray()) {
    val value: String? get() = state.takeUnless { it == 0 }?.let { YEARS[it] }
}

class SeasonFilter : AnimeFilter.Select<String>("Saison", SEASONS.toTypedArray()) {
    val value: String? get() = state.takeUnless { it == 0 }?.let { SEASONS[it] }
}

class SearchParams(
    val themes: List<String>,
    val format: String?,
    val status: String?,
    val sort: String,
    val type: String?,
    val year: String?,
    val season: String?,
)

object Filters {
    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            ThemeFilter(),
            FormatFilter(),
            StatusFilter(),
            SortFilter(),
            TypeFilter(),
            YearFilter(),
            SeasonFilter(),
        )

    fun get(filters: AnimeFilterList) = SearchParams(
        themes = filters.firstInstanceOrNull<ThemeFilter>()?.state
            ?.filter { it.state }
            ?.map { it.name }
            .orEmpty(),
        format = filters.firstInstanceOrNull<FormatFilter>()?.value,
        status = filters.firstInstanceOrNull<StatusFilter>()?.value,
        sort = filters.firstInstanceOrNull<SortFilter>()?.value ?: SORTS.first(),
        type = filters.firstInstanceOrNull<TypeFilter>()?.value,
        year = filters.firstInstanceOrNull<YearFilter>()?.value,
        season = filters.firstInstanceOrNull<SeasonFilter>()?.value,
    )
}
