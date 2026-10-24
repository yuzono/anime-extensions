package eu.kanade.tachiyomi.animeextension.en.hentaidb

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HentaiDBFilters {

    // (label, catalog sort key, whether "ascending" is the site's default direction)
    private val SORTS = listOf(
        Triple("Popularity", "pop", false),
        Triple("Title", "title", true),
        Triple("Listed year", "year", false),
        Triple("Episodes", "eps", false),
        Triple("Total runtime", "runtime", false),
        Triple("Episode length", "eplen", false),
        Triple("Quality", "quality", false),
        Triple("Subtitles", "subs", false),
        Triple("Studio", "studio", true),
    )

    class SortFilter(state: Selection = Selection(0, false)) : AnimeFilter.Sort("Sort by", SORTS.map { it.first }.toTypedArray(), state)

    open class ValueSelect(name: String, private val options: List<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options.getOrNull(state)?.second.orEmpty()
    }

    class TypeFilter : ValueSelect("Type", listOf("Any" to "") + HentaiDBCatalog.TYPES.mapIndexed { i, t -> t to "${i + 1}" })
    class StatusFilter : ValueSelect("Status", listOf("Any" to "") + HentaiDBCatalog.STATUSES.mapIndexed { i, t -> t to "${i + 1}" })
    class CutFilter : ValueSelect("Cut", listOf("Any" to "", "Censored" to "1", "Uncensored" to "2"))
    class QualityFilter : ValueSelect("Quality", listOf("Any" to "", "720p or better" to "720", "1080p or better" to "1080", "4K" to "2160"))
    class SubtitleFilter : ValueSelect("Subtitles", listOf("Any" to "", "Any subtitles" to "1", "English subtitles" to "2"))
    class EpisodeCountFilter : ValueSelect("Episodes", listOf("Any" to "", "Single episode" to "1", "2 to 4 episodes" to "2", "5 or more episodes" to "5"))
    class EpisodeLengthFilter : ValueSelect("Episode length", listOf("Any" to "", "Under 10 min" to "short", "10 to 20 min" to "mid", "Over 20 min" to "long"))
    class StudioSelectFilter(studios: List<Pair<String, String>>) : ValueSelect("Studio", listOf("Any" to "") + studios.map { it.second to it.first })

    class DubFilter : AnimeFilter.CheckBox("English dub only")
    class Fps48Filter : AnimeFilter.CheckBox("48 fps copies only")

    class StudioTextFilter : AnimeFilter.Text("Studio name")
    class IncludeTagsTextFilter : AnimeFilter.Text("Include tags (comma separated)")
    class ExcludeTagsTextFilter : AnimeFilter.Text("Exclude tags (comma separated)")

    class TagFilter(name: String) : AnimeFilter.TriState(name)
    class TagGroup(tags: List<String>) : AnimeFilter.Group<TagFilter>("Tags", tags.map(::TagFilter))

    fun getFilterList(catalog: HentaiDBCatalog?): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("Filters also apply to text searches"),
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            CutFilter(),
            QualityFilter(),
            SubtitleFilter(),
            EpisodeCountFilter(),
            EpisodeLengthFilter(),
            DubFilter(),
            Fps48Filter(),
            AnimeFilter.Separator(),
        )
        if (catalog != null) {
            filters += StudioSelectFilter(catalog.studiosAlphabetical)
            filters += TagGroup(catalog.tagsAlphabetical)
        } else {
            filters += AnimeFilter.Header("Browse once, then press Reset to load the studio and tag lists")
            filters += StudioTextFilter()
        }
        filters += AnimeFilter.Separator()
        filters += IncludeTagsTextFilter()
        filters += ExcludeTagsTextFilter()
        return AnimeFilterList(filters)
    }

    /**
     * Applies the filter sheet on top of [query] (the parsed search text). Filters left at their
     * default keep whatever the search text asked for, exactly like the site's search box.
     */
    fun buildQuery(query: HentaiDBCatalog.Query, filters: AnimeFilterList, catalog: HentaiDBCatalog): HentaiDBCatalog.Query {
        fun include(index: Int) {
            query.excludedTags.remove(index)
            if (index !in query.includedTags) query.includedTags += index
        }
        fun exclude(index: Int) {
            query.includedTags.remove(index)
            if (index !in query.excludedTags) query.excludedTags += index
        }
        fun splitTags(text: String) = text.split(',').mapNotNull { catalog.resolveTag(it) }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> filter.state?.let { selection ->
                    val (_, key, defaultAscending) = SORTS.getOrElse(selection.index) { SORTS.first() }
                    val reversed = selection.ascending != defaultAscending
                    if (selection.index != 0 || reversed) {
                        query.sort = key
                        query.reversed = reversed
                    }
                }
                is TypeFilter -> filter.selected.toIntOrNull()?.let { query.type = it }
                is StatusFilter -> filter.selected.toIntOrNull()?.let { query.status = it }
                is CutFilter -> filter.selected.toIntOrNull()?.let { query.cut = it }
                is QualityFilter -> filter.selected.toIntOrNull()?.let { query.minQuality = it }
                is SubtitleFilter -> filter.selected.toIntOrNull()?.let { query.subtitles = it }
                is EpisodeCountFilter -> filter.selected.toIntOrNull()?.let { query.episodes = it }
                is EpisodeLengthFilter -> if (filter.selected.isNotEmpty()) query.length = filter.selected
                is StudioSelectFilter -> if (filter.selected.isNotEmpty()) query.studio = filter.selected
                is StudioTextFilter -> if (filter.state.isNotBlank()) query.studio = catalog.resolveStudio(filter.state) ?: HentaiDBCatalog.fold(filter.state)
                is DubFilter -> if (filter.state) query.dub = true
                is Fps48Filter -> if (filter.state) query.fps48 = true
                is TagGroup -> filter.state.forEach { tag ->
                    val index = catalog.resolveTag(tag.name) ?: return@forEach
                    when {
                        tag.isIncluded() -> include(index)
                        tag.isExcluded() -> exclude(index)
                    }
                }
                is IncludeTagsTextFilter -> splitTags(filter.state).forEach(::include)
                is ExcludeTagsTextFilter -> splitTags(filter.state).forEach(::exclude)
                else -> {}
            }
        }
        return query
    }
}
