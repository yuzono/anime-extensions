package eu.kanade.tachiyomi.animeextension.fr.animesama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private class TextVal(name: String) : AnimeFilter.Text(name)

    open class CheckBoxFilterList(name: String, values: List<AnimeFilter.CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private inline fun <reified R> AnimeFilterList.getFirst(): R = this.filterIsInstance<R>().first()

    private inline fun <reified R> AnimeFilterList.parseCheckbox(): List<String> = (this.getFirst<R>() as CheckBoxFilterList).state
        .filter { it.state }
        .map { it.name }

    class TypeFilter : CheckBoxFilterList("Type", TYPES.map { CheckBoxVal(it) })

    class LanguageFilter : CheckBoxFilterList("Langue", LANGUAGES.map { CheckBoxVal(it) })

    class StatusFilter : CheckBoxFilterList("Statut", STATUS.map { CheckBoxVal(it) })

    class GenreFilter : CheckBoxFilterList("Genre", GENRES.map { CheckBoxVal(it) })

    class YearFilter : AnimeFilter.Group<AnimeFilter.Text>("Année", listOf(TextVal("De"), TextVal("À")))

    class SortFilter :
        AnimeFilter.Select<String>(
            "Trier par",
            arrayOf("Défaut", "Titre (A → Z)", "Derniers ajouts", "Sortie récente", "Sortie ancienne", "Nombre d'épisodes"),
        )

    val FILTER_LIST get() = AnimeFilterList(
        TypeFilter(),
        LanguageFilter(),
        StatusFilter(),
        GenreFilter(),
        YearFilter(),
        SortFilter(),
    )

    class SearchFilters(
        val types: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val yearMin: String = "",
        val yearMax: String = "",
        val sort: String = "",
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        if (filters.isEmpty()) return SearchFilters()
        val years = filters.getFirst<YearFilter>().state.map { it.state.trim() }
        return SearchFilters(
            filters.parseCheckbox<TypeFilter>(),
            filters.parseCheckbox<LanguageFilter>(),
            filters.parseCheckbox<StatusFilter>(),
            filters.parseCheckbox<GenreFilter>(),
            years.getOrElse(0) { "" },
            years.getOrElse(1) { "" },
            SORT_VALUES.getOrElse(filters.getFirst<SortFilter>().state) { "" },
        )
    }

    private val TYPES = listOf("Anime", "Film", "Autres")

    private val LANGUAGES = listOf("VOSTFR", "VF", "VASTFR", "VCN")

    private val STATUS = listOf("En cours", "Terminé")

    private val SORT_VALUES = arrayOf("", "titre", "ajout", "recent", "ancien", "episodes")

    // Scans/Webtoon entries are manga and have no episodes, which we don't want to show
    private val GENRES = listOf(
        "Action",
        "Adolescence",
        "Aliens / Extra-terrestres",
        "Amitié",
        "Amour",
        "Apocalypse",
        "Art",
        "Arts martiaux",
        "Assassinat",
        "Autre monde",
        "Aventure",
        "Combats",
        "Comédie",
        "Crime",
        "Cyberpunk",
        "Danse",
        "Démons",
        "Détective",
        "Donghua",
        "Dragon",
        "Drame",
        "Ecchi",
        "Ecole",
        "Elfe",
        "Enquête",
        "Famille",
        "Fantastique",
        "Fantasy",
        "Fantômes",
        "Futur",
        "Gastronomie",
        "Ghibli",
        "Guerre",
        "Harcèlement",
        "Harem",
        "Harem inversé",
        "Histoire",
        "Historique",
        "Homosexualité",
        "Horreur",
        "Isekai",
        "Jeunesse",
        "Jeux",
        "Jeux vidéo",
        "Josei",
        "Journalisme",
        "Kaï",
        "LGBT+",
        "Mafia",
        "Magical girl",
        "Magie",
        "Maladie",
        "Mariage",
        "Mature",
        "Mechas",
        "Médiéval",
        "Militaire",
        "Monde virtuel",
        "Monstres",
        "Musique",
        "Mystère",
        "Nekketsu",
        "Ninjas",
        "Nostalgie",
        "Paranormal",
        "Philosophie",
        "Pirates",
        "Police",
        "Politique",
        "Post-apocalyptique",
        "Pouvoirs psychiques",
        "Préhistoire",
        "Prison",
        "Psychologique",
        "Quotidien",
        "Religion",
        "Réincarnation / Transmigration",
        "Romance",
        "Samouraïs",
        "School Life",
        "Science-Fantasy",
        "Science-fiction",
        "Scientifique",
        "Seinen",
        "Shôjo",
        "Shôjo-Ai",
        "Shônen",
        "Shônen-Ai",
        "Slice of Life",
        "Société",
        "Sport",
        "Super pouvoirs",
        "Super-héros",
        "Surnaturel",
        "Survie",
        "Survival game",
        "Technologies",
        "Thriller",
        "Tournois",
        "Travail",
        "Vampires",
        "Vengeance",
        "Voyage",
        "Voyage temporel",
        "Webcomic",
        "Yakuza",
        "Yaoi",
        "Yokai",
        "Yuri",
    )
}
