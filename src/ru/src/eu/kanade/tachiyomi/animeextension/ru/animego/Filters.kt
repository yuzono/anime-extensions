package eu.kanade.tachiyomi.animeextension.ru.animego

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstance
import java.net.URLEncoder

object Filters {
    fun buildPath(filters: AnimeFilterList): String? {
        if (filters.isEmpty()) return null

        val segments = mutableListOf<Pair<String, String>>()

        val genres = filters.firstInstance<GenreFilter>().state
            .filter { it.state }
            .map { GenreFilter.OPTIONS[it.name].orEmpty() }
            .filter(String::isNotBlank)
        if (genres.isNotEmpty()) segments += "genre" to genres.joinToString(",")

        val types = filters.firstInstance<TypeFilter>().state
            .filter { it.state }
            .map { TypeFilter.OPTIONS[it.name].orEmpty() }
            .filter(String::isNotBlank)
        if (types.isNotEmpty()) segments += "o.cat" to types.joinToString(",")

        val statuses = filters.firstInstance<StatusFilter>().state
            .filter { it.state }
            .map { StatusFilter.OPTIONS[it.name].orEmpty() }
            .filter(String::isNotBlank)
        if (statuses.isNotEmpty()) segments += "status" to statuses.joinToString(",")

        val voice = filters.firstInstance<VoiceFilter>().value.trim()
        if (voice.isNotBlank()) segments += "voice" to voice

        parseRange(filters.firstInstance<YearFilter>().state)?.let {
            segments += "r.year" to it
        }
        parseRange(filters.firstInstance<ScoreFilter>().state)?.let {
            segments += "r.sm" to it
        }

        if (segments.isEmpty()) return null

        segments += "sort" to "tags"
        segments += "order" to "desc"

        val path = segments.joinToString("/") { (key, value) ->
            "${encodePathValue(key)}=${encodePathValue(value)}"
        }
        return "/f/$path/"
    }

    private fun parseRange(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val parts = text.split('-', ';', ' ', ',')
            .map(String::trim)
            .filter(String::isNotBlank)
        if (parts.size < 2) return null
        return "${parts[0]};${parts[1]}"
    }

    private fun encodePathValue(value: String): String = URLEncoder.encode(value, "UTF-8")

    open class GroupFilter(name: String, values: List<AnimeFilter.CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    class CheckBoxVal(name: String) : AnimeFilter.CheckBox(name)
    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value: String
            get() = options.getOrNull(state)?.second.orEmpty()
    }

    class GenreFilter : GroupFilter("Жанры", OPTIONS.keys.map(::CheckBoxVal)) {
        companion object {
            val OPTIONS = linkedMapOf(
                "Приключения" to "Adventure",
                "Комедия" to "Comedy",
                "Экшен" to "Action",
                "Фэнтези" to "Fantasy",
                "Драма" to "Drama",
                "Детектив" to "Detective",
                "Этти" to "Ecchi",
                "Игры" to "Game",
                "Кулинария" to "Cooking",
                "Исторический" to "Historical",
                "Гарем" to "Harem",
                "Магия" to "Magic",
                "Боевые искусства" to "Martial Arts",
                "Исекай" to "Isekai",
                "Ужасы" to "Horror",
                "Махо-сёдзё" to "Mahou Shoujo",
                "Айдолы" to "Idols",
                "Лоли" to "Loli",
                "Меха" to "Mecha",
                "Романтика" to "Romance",
                "Фантастика" to "Sci-Fi",
                "Школа" to "School",
                "Повседневность" to "Slice of Life",
                "Сверхъестественное" to "Supernatural",
                "Музыка" to "Music",
                "Мистика" to "Mystery",
                "Военное" to "Military",
                "Выживание" to "Survival",
                "Спорт" to "Sports",
                "Психологическое" to "Psychological",
                "Реинкарнация" to "Reincarnation",
                "Реверс-гарем" to "Reverse Harem",
                "Гонки" to "Racing",
                "Триллер" to "Suspense",
                "Королевская битва" to "Royal Battle",
                "Вестерн" to "Western",
            )
        }
    }

    class TypeFilter : GroupFilter("Тип", OPTIONS.keys.map(::CheckBoxVal)) {
        companion object {
            val OPTIONS = linkedMapOf(
                "Фильм" to "1",
                "TV Сериал" to "2",
                "OVA" to "3",
                "ONA" to "4",
                "Спешл" to "5",
            )
        }
    }

    class StatusFilter : GroupFilter("Статус", OPTIONS.keys.map(::CheckBoxVal)) {
        companion object {
            val OPTIONS = linkedMapOf(
                "Онгоинг" to "ongoing",
                "Вышел" to "released",
                "Анонс" to "announce",
            )
        }
    }

    class VoiceFilter :
        SelectFilter(
            "Озвучка",
            listOf(
                "Любая" to "",
                "2x2" to "2x2",
                "3df voice" to "3df voice",
                "2D-DUB" to "2D-DUB",
                "Dream Cast" to "Dream Cast",
                "#студияБУБНЯЖА" to "#студияБУБНЯЖА",
                "27Gang" to "27Gang",
                "DreamyVoice" to "DreamyVoice",
                "4Anime" to "4Anime",
                "AEROChannelEkat" to "AEROChannelEkat",
                "Akame" to "Akame",
                "absurd95" to "absurd95",
                "ADStudio" to "ADStudio",
                "AiLiberty" to "AiLiberty",
                "Akari Group" to "Akari Group",
                "Akikomi" to "Akikomi",
                "Alusar" to "Alusar",
                "AlexFilm" to "AlexFilm",
                "Akimbo Production" to "Akimbo Production",
                "AlFair Studio" to "AlFair Studio",
                "Aleister" to "Aleister",
                "AlphaProject" to "AlphaProject",
                "Alternative Media Voice" to "Alternative Media Voice",
                "Alternative Production" to "Alternative Production",
                "Amazing Dubbing" to "Amazing Dubbing",
                "Amaivon" to "Amaivon",
                "Amateur \"Ibra\"" to "Amateur \"Ibra\"",
                "Amber" to "Amber",
                "Amedia.online" to "Amedia.online",
                "Ancord" to "Ancord",
                "Amediateka" to "Amediateka",
            ),
        )
    class YearFilter : AnimeFilter.Text("Диапазон годов (например: 2000-2026)")
    class ScoreFilter : AnimeFilter.Text("Диапазон рейтинга Shiki (например: 6-10)")

    val FILTERS get() = AnimeFilterList(
        AnimeFilter.Header("Текстовый поиск игнорирует фильтры"),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        VoiceFilter(),
        YearFilter(),
        ScoreFilter(),
    )
}
