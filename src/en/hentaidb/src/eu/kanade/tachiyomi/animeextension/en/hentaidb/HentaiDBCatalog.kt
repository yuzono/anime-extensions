package eu.kanade.tachiyomi.animeextension.en.hentaidb

import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory port of the site's own browse logic (`/js/query.*.js`): the same query syntax,
 * filters, sort orders and search scoring the website applies to its catalog.
 *
 * [collator] must behave like the site's `Intl.Collator('en', {sensitivity: 'base'})`. On Android,
 * [java.text.Collator] is backed by ICU, which is what browsers use for `Intl.Collator`.
 */
class HentaiDBCatalog(
    dto: CatalogDto,
    private val collator: Comparator<String> = defaultCollator(),
) {

    val tags: List<String> = dto.tags

    private val studioRaw: List<String> = dto.studios

    private val tagMigrations: Map<String, String> = dto.mig?.t.orEmpty()

    /** Lower-cased meta tag name -> (fields when included, fields when excluded). */
    private val metaTags: Map<String, List<List<Pair<String, Int>>>> = buildMap {
        val meta = LinkedHashMap(META_TAGS)
        dto.mig?.c.orEmpty().forEach { (name, censored) ->
            if (name !in meta) meta[name] = if (censored) CENSORED_META else UNCENSORED_META
        }
        meta.forEach { (name, value) -> put(name.lowercase(Locale.ROOT), value) }
    }

    val entries: List<Entry>

    val bySlug: Map<String, Entry>

    /** Number of titles using each tag index. */
    private val tagUses: IntArray = IntArray(tags.size)

    /** Folded studio key -> display name. */
    private val studioNames = LinkedHashMap<String, String>()

    /** Display studio name -> number of titles. */
    private val studioCounts = HashMap<String, Int>()

    private val tagWords: List<List<String>> = tags.map { tag ->
        tag.lowercase(Locale.ROOT).split(NON_ALNUM_REGEX).filter(String::isNotEmpty)
    }

    private val tagIndex: Map<String, Int> = tags.withIndex().associate { it.value to it.index }

    private val foldedTags = HashMap<String, Int>()

    private val orders = ConcurrentHashMap<String, List<Entry>>()

    init {
        entries = dto.rows.map { row ->
            val studio = row.st?.let { studioRaw.getOrNull(it) }.orEmpty()
            Entry(row, studio, row.g.mapNotNull { tags.getOrNull(it) })
        }
        entries.forEach { entry ->
            entry.row.g.forEach { if (it in tagUses.indices) tagUses[it]++ }
            entry.studioDisplayParts.forEachIndexed { i, part ->
                val key = entry.studioParts[i]
                if (key.isEmpty()) return@forEachIndexed
                val name = studioNames.getOrPut(key) { part }
                studioCounts[name] = (studioCounts[name] ?: 0) + 1
            }
        }
        bySlug = entries.associateBy { it.row.s }

        tags.forEachIndexed { i, tag ->
            val key = fold(tag)
            if (key.isEmpty()) return@forEachIndexed
            val current = foldedTags[key]
            if (current == null || tagUses[i] > tagUses[current]) foldedTags[key] = i
        }
        tagMigrations.forEach { (from, to) ->
            val key = fold(from)
            val target = tagIndex[to]
            if (key.isNotEmpty() && key !in foldedTags && target != null) foldedTags[key] = target
        }
    }

    /** Tags in A-Z order, for the filter sheet. */
    val tagsAlphabetical: List<String>
        get() = tags.indices.filter { tagUses[it] > 0 }.map { tags[it] }
            .sortedWith { a, b -> collator.compare(a, b).takeIf { it != 0 } ?: a.compareTo(b) }

    /** Studios in A-Z order of their display name, as (key, display name), for the filter sheet. */
    val studiosAlphabetical: List<Pair<String, String>>
        get() = studioNames.entries
            .sortedWith { a, b -> collator.compare(a.value, b.value).takeIf { it != 0 } ?: a.value.compareTo(b.value) }
            .map { it.key to it.value }

    fun resolveTag(name: String): Int? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        tagIndex[trimmed]?.let { return it }
        tagMigrations[trimmed]?.let { migrated -> tagIndex[migrated]?.let { return it } }
        return foldedTags[fold(trimmed)]
    }

    fun resolveStudio(value: String): String? {
        val key = fold(value)
        if (key.isEmpty()) return null
        if (key in studioNames) return key
        val byCount = compareByDescending<String> { studioCounts[studioNames[it]] ?: 0 }
        return studioNames.keys.filter { it.startsWith(key) }.sortedWith(byCount).firstOrNull()
            ?: studioNames.keys.filter { it.indexOf(key) > 0 }.sortedWith(byCount).firstOrNull()
    }

    // ============================ Query syntax ============================

    /** The site's `typedTag`: an exact tag name, else a folded (or migrated) tag name. */
    private fun typedTag(value: String): Int? = tagIndex[value] ?: foldedTags[fold(value)]

    private fun sortKey(value: String): String? = value.takeIf { it in SORT_KEYS } ?: SORT_WORDS[value]

    /**
     * Port of the site's search box parser (`parse` in `/js/query.*.js`). Besides plain words it
     * understands `tag:`, `-tag:`, `studio:`, `q:`, `subs:`, `type:`, `eps:`, `len:`, `sort:`, quoted
     * phrases and the shortcut words `4k`, `en`, `dub`, `48fps`, `short`, `(un)censored`, `completed`,
     * `ongoing`, `stalled` and `-<tag>`. Tokens the site ignores are ignored here too.
     */
    fun parse(input: String): Query {
        val n = Query()
        fun addTag(excluded: Boolean, index: Int) {
            if (index !in n.includedTags && index !in n.excludedTags) {
                (if (excluded) n.excludedTags else n.includedTags) += index
            }
        }
        fun setAll(fields: List<Pair<String, Int>>) = fields.forEach { (field, value) ->
            when (field) {
                "c" -> n.cut = value
                "q" -> n.minQuality = value
                "f48" -> n.fps48 = value != 0
            }
        }

        for (m in TOKEN_REGEX.findAll(input)) {
            val g = m.groups
            val key = g[2]?.value?.lowercase(Locale.ROOT)
            if (key != null) {
                val neg = g[1]?.value == "-"
                val v = g[3]?.value ?: g[4]?.value.orEmpty()
                val lv = v.lowercase(Locale.ROOT)
                when {
                    key == "tag" -> {
                        val k = typedTag(v)
                        val meta = if (k == null) metaTags[lv]?.getOrNull(if (neg) 1 else 0) else null
                        if (k != null) {
                            addTag(neg, k)
                        } else if (meta != null) {
                            setAll(meta)
                        }
                    }
                    neg -> {}
                    key == "q" && Q_2160_REGEX.matches(lv) -> n.minQuality = 2160
                    key == "q" && Q_1080_REGEX.matches(lv) -> n.minQuality = 1080
                    key == "q" && Q_720_REGEX.matches(lv) -> n.minQuality = 720
                    key == "subs" && SUBS_EN_REGEX.matches(lv) -> n.subtitles = 2
                    key == "subs" && lv == "any" -> n.subtitles = 1
                    key == "type" && TYPE_KEYS.indexOf(fold(v)) > 0 -> n.type = TYPE_KEYS.indexOf(fold(v))
                    key == "eps" && EPS_REGEX.matches(lv) -> n.episodes = when {
                        lv == "1" -> 1
                        lv[0] == '5' -> 5
                        else -> 2
                    }
                    key == "len" && lv in LENGTHS -> n.length = lv
                    key == "studio" && resolveStudio(v) != null -> n.studio = resolveStudio(v)!!
                    key == "sort" && sortKey(lv.removePrefix("-")) != null -> {
                        n.sort = sortKey(lv.removePrefix("-"))!!
                        n.reversed = lv.startsWith("-")
                    }
                }
                continue
            }

            g[5]?.value?.let { phrase ->
                n.words += phrase.split(WHITESPACE_REGEX).filter(String::isNotEmpty)
                continue
            }

            val w = g[6]?.value ?: continue
            val lw = w.lowercase(Locale.ROOT)
            val ng = lw.length > 1 && lw[0] == '-'
            val b = if (ng) lw.substring(1) else lw
            when {
                b == "uncensored" -> n.cut = if (ng) 1 else 2
                b == "censored" -> n.cut = if (ng) 2 else 1
                ng -> typedTag(b)?.let { addTag(true, it) }
                b == "4k" -> n.minQuality = 2160
                b == "en" -> n.subtitles = 2
                b == "48fps" -> n.fps48 = true
                b == "dub" -> n.dub = true
                b == "short" -> n.length = "short"
                b in STATUS_WORDS -> n.status = STATUS_WORDS.indexOf(b) + 1
                PARAM_REGEX.containsMatchIn(w) -> {}
                else -> n.words += w
            }
        }
        return n
    }

    // ============================== Querying ==============================

    fun search(query: Query): List<Entry> {
        val words = query.words.map { it.lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        val ordered = order(query.sort, query.reversed)
        if (words.isEmpty()) return ordered.filter { matches(it, query) }

        return ordered.asSequence()
            .filter { matches(it, query) }
            .mapNotNull { entry ->
                var score = 0
                for (word in words) {
                    val s = termScore(entry, word)
                    if (s == 0) return@mapNotNull null
                    score += s
                }
                entry to score
            }
            .toList()
            .sortedByDescending { it.second } // stable: keeps the selected order for ties
            .map { it.first }
    }

    private fun matches(r: Entry, n: Query): Boolean {
        val row = r.row
        if (n.minQuality > 0 && row.q < n.minQuality) return false
        if (n.subtitles > 0 && row.u < n.subtitles) return false
        if (n.cut != 0 && row.c != n.cut) return false
        if (n.fps48 && row.f48 == 0) return false
        if (n.status != 0 && row.sa != n.status) return false
        if (n.type != 0 && row.ty != n.type) return false
        when (n.episodes) {
            1 -> if (row.e != 1) return false
            2 -> if (row.e !in 2..4) return false
            5 -> if (row.e < 5) return false
        }
        when (n.length) {
            "short" -> if (!(r.median > 0 && r.median < 600)) return false
            "mid" -> if (!(r.median >= 600 && r.median <= 1200)) return false
            "long" -> if (!(r.median > 1200)) return false
        }
        if (n.dub && row.dub == 0) return false
        if (n.studio.isNotEmpty() && n.studio !in r.studioParts) return false
        if (n.includedTags.any { it !in row.g }) return false
        if (n.excludedTags.any { it in row.g }) return false
        return true
    }

    private fun termScore(r: Entry, t: String): Int {
        val title = r.titleLower
        val i = title.indexOf(t)
        return when {
            title == t -> 100
            i == 0 -> 70
            title.contains(" $t") -> 50
            i > 0 -> 35
            r.studioWords.contains(" $t") -> 25
            " ${r.row.k}".contains(" $t") -> 25
            r.row.g.any { k -> tagWords.getOrNull(k)?.any { it.startsWith(t) } == true } -> 15
            else -> 0
        }
    }

    private fun order(sort: String, reversed: Boolean): List<Entry> {
        val key = if (sort in SORT_KEYS) sort else "pop"
        val id = key + if (reversed) "-" else ""
        return orders.getOrPut(id) {
            val m = if (reversed) -1 else 1
            val unknown: (Entry) -> Boolean = when (key) {
                "pop" -> { e -> e.row.p == 0 }
                "runtime" -> { e -> e.runtime == 0 }
                "eplen" -> { e -> e.median == 0.0 }
                "quality" -> { e -> e.row.q == 0 }
                "subs" -> { e -> e.row.ul == 0 }
                "year" -> { e -> e.row.y == 0 }
                "studio" -> { e -> e.studio.isEmpty() }
                else -> { _ -> false }
            }
            val compare: (Entry, Entry) -> Int = when (key) {
                "title" -> { a, b -> collator.compare(a.row.t, b.row.t) }
                "eps" -> { a, b -> b.row.e.compareTo(a.row.e) }
                "runtime" -> { a, b -> b.runtime.compareTo(a.runtime) }
                "eplen" -> { a, b -> b.median.compareTo(a.median) }
                "quality" -> { a, b -> b.row.q.compareTo(a.row.q).takeIf { it != 0 } ?: b.row.f48.compareTo(a.row.f48) }
                "subs" -> { a, b -> b.row.ul.compareTo(a.row.ul).takeIf { it != 0 } ?: b.row.u.compareTo(a.row.u) }
                "year" -> { a, b -> b.row.y.compareTo(a.row.y) }
                "studio" -> { a, b -> collator.compare(a.studio, b.studio) }
                else -> { a, b -> a.row.p.compareTo(b.row.p) }
            }
            entries.sortedWith { a, b ->
                val ua = unknown(a)
                val ub = unknown(b)
                when {
                    ua != ub -> if (ua) 1 else -1
                    else -> (m * compare(a, b)).takeIf { it != 0 }
                        ?: collator.compare(a.row.t, b.row.t).takeIf { it != 0 }
                        ?: a.row.s.compareTo(b.row.s)
                }
            }
        }
    }

    class Entry(val row: CatalogRowDto, val studio: String, val tagNames: List<String>) {
        val studioDisplayParts: List<String> = studio.split(STUDIO_SPLIT_REGEX).map(String::trim).filter(String::isNotEmpty)
        val studioParts: List<String> = studioDisplayParts.map { fold(it) }
        val studioWords: String = studioDisplayParts.joinToString(" ").lowercase(Locale.ROOT).let { joined ->
            " " + joined.replace(NON_ALNUM_REGEX, " ") + " " + joined
        }
        val titleLower: String = row.t.lowercase(Locale.ROOT)
        private val known: List<Int> = row.d.filter { it > 0 }.sorted()
        val runtime: Int = if (row.d.isNotEmpty() && known.size == row.d.size) known.sum() else 0
        val median: Double = when {
            known.isEmpty() -> 0.0
            known.size % 2 == 1 -> known[known.size / 2].toDouble()
            else -> (known[known.size / 2 - 1] + known[known.size / 2]) / 2.0
        }
    }

    class Query(
        val words: MutableList<String> = mutableListOf(),
        var sort: String = "pop",
        var reversed: Boolean = false,
        var minQuality: Int = 0,
        var subtitles: Int = 0,
        var cut: Int = 0,
        var fps48: Boolean = false,
        var status: Int = 0,
        var type: Int = 0,
        var episodes: Int = 0,
        var length: String = "",
        var dub: Boolean = false,
        var studio: String = "",
        val includedTags: MutableList<Int> = mutableListOf(),
        val excludedTags: MutableList<Int> = mutableListOf(),
    )

    companion object {
        val SORT_KEYS = listOf("pop", "title", "year", "eps", "runtime", "eplen", "quality", "subs", "studio")
        val TYPES = listOf("OVA", "Movie", "TV Series", "Web", "Other")
        val STATUSES = listOf("Completed", "Ongoing", "Stalled")

        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val STUDIO_SPLIT_REGEX = Regex("\\s*,\\s*")

        // JavaScript's \s also matches Unicode spaces; mirror it so tokens split like the site.
        private const val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
        private val WHITESPACE_REGEX = Regex("[$JS_SPACE]+")
        private val TOKEN_REGEX = Regex(
            """(-?)([a-z]+):(?:"([^"]*)"|([^$JS_SPACE]+))|"([^"]*)"|([^$JS_SPACE]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val PARAM_REGEX = Regex("^(q|sort|tag|not|fq|fu|fc|fs|ft|fa|fe|fps|len|dub|studio)=", RegexOption.IGNORE_CASE)
        private val Q_2160_REGEX = Regex("""(4k|2160p?\+?)""")
        private val Q_1080_REGEX = Regex("""1080p?\+?""")
        private val Q_720_REGEX = Regex("""720p?\+?""")
        private val SUBS_EN_REGEX = Regex("en|eng|english")
        private val EPS_REGEX = Regex("""1|2-4|2|5\+?""")
        private val LENGTHS = listOf("short", "mid", "long")
        private val STATUS_WORDS = listOf("completed", "ongoing", "stalled")
        private val TYPE_KEYS = listOf("", "ova", "movie", "tv", "web", "other")

        /** The site's `SORTS[key].w` words, also accepted by `sort:`. */
        private val SORT_WORDS = mapOf(
            "popular" to "pop",
            "title" to "title",
            "year" to "year",
            "episodes" to "eps",
            "runtime" to "runtime",
            "eplen" to "eplen",
            "quality" to "quality",
            "subtitles" to "subs",
            "studio" to "studio",
        )

        private val CENSORED_META = listOf(listOf("c" to 1), listOf("c" to 2))
        private val UNCENSORED_META = listOf(listOf("c" to 2), listOf("c" to 1))

        /** The site's `META` pseudo tags usable with `tag:`. */
        private val META_TAGS: Map<String, List<List<Pair<String, Int>>>> = mapOf(
            "Censored" to CENSORED_META,
            "Uncensored" to UNCENSORED_META,
            "4K" to listOf(listOf("q" to 2160)),
            "4K 48Fps" to listOf(listOf("q" to 2160, "f48" to 1)),
            "48Fps" to listOf(listOf("f48" to 1)),
            "1080p" to listOf(listOf("q" to 1080)),
            "720p" to listOf(listOf("q" to 720)),
            "Hd" to listOf(listOf("q" to 720)),
        )

        fun defaultCollator(): Comparator<String> {
            val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
            return Comparator { a, b -> collator.compare(a, b) }
        }

        fun fold(value: String): String = value.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, "")
    }
}
