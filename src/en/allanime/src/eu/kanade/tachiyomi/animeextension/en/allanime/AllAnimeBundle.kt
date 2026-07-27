package eu.kanade.tachiyomi.animeextension.en.allanime

/**
 * Recovers the two per-build crypto inputs — `buildId` and the four mask seeds — from mkissa's
 * obfuscated JS chunk.
 *
 * `buildId` is a plain literal. The seeds are not: each is two concatenated calls into a string
 * table, reached through alias functions that offset the index, and the table is rotated at load
 * time by an amount only the bundle's own checksum loop knows. Rather than emulate that loop,
 * [parse] resolves the lookup chain for every possible rotation and keeps the one whose results
 * all have the seed shape.
 */
object AllAnimeBundle {

    class BuildInfo(val buildId: String, val seeds: List<String>)

    fun parse(js: String): BuildInfo? {
        val buildId = BUILD_ID_REGEX.find(js)?.groupValues?.get(1) ?: return null
        val seeds = extractSeeds(js) ?: return null
        return BuildInfo(buildId, seeds)
    }

    private class Base(val table: String, val offset: Int)
    private class Alias(val base: String, val argIndex: Int, val delta: Int)

    private fun extractSeeds(js: String): List<String>? {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { m ->
            m.groupValues[1] to Base(m.groupValues[4], fold(m.groupValues[3]))
        }
        val aliases = buildMap {
            // A seed may call a base decoder directly rather than through an alias; treating each
            // base as its own identity alias lets one lookup path handle both spellings.
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { m ->
                val (name, firstParam, _, callee, arg, delta) = m.destructured
                if (callee !in bases) return@forEach
                // The alias forwards exactly one of its two parameters; which one tells us where
                // the real table index sits in the call. Deeper chains are not handled.
                put(name, Alias(callee, if (arg == firstParam) 0 else 1, if (delta.isEmpty()) 0 else fold(delta)))
            }
        }

        // Normally there is exactly one such array, but scan every candidate so an unrelated
        // lookalike earlier in the chunk cannot poison the parse.
        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val calls = CALL_REGEX.findAll(match.groupValues[1]).map(MatchResult::value).toList()
            if (calls.size != AllAnimeCrypto.SEED_COUNT * 2) continue

            // Every decoder ultimately indexes the same table, so any of them bounds the search.
            val table = CALL_REGEX.find(calls.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue

            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(calls, rotation, tables, bases, aliases)
            }
            // A wrong rotation matching the seed shape by chance is vanishingly unlikely, but
            // would silently yield a bad mask — so require the answer to be unambiguous.
            matches.singleOrNull()?.let { return it }
        }
        return null
    }

    private fun seedsAt(
        calls: List<String>,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): List<String>? {
        val seeds = calls.chunked(2).mapNotNull { (first, second) ->
            val a = resolve(first, rotation, tables, bases, aliases) ?: return@mapNotNull null
            val b = resolve(second, rotation, tables, bases, aliases) ?: return@mapNotNull null
            (a + b).takeIf(SEED_REGEX::matches)
        }
        return seeds.takeIf { it.size == AllAnimeCrypto.SEED_COUNT }
    }

    private fun resolve(
        call: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = aliases[match.groupValues[1]] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table]?.takeIf { it.isNotEmpty() } ?: return null

        val args = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val arg = args.getOrNull(alias.argIndex) ?: return null

        val index = arg + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        for (match in TABLE_HEAD_REGEX.findAll(js)) {
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    /** Whitelist parser: returns null rather than a partial array if anything unexpected appears. */
    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var i = open + 1
        while (i < js.length) {
            when (val c = js[i]) {
                ']' -> return items
                ',', ' ' -> i++
                '"', '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < js.length && js[i] != c) {
                        if (js[i] == '\\') {
                            sb.append(js[i + 1])
                            i += 2
                        } else {
                            sb.append(js[i])
                            i++
                        }
                    }
                    if (i >= js.length) return null
                    i++
                    items.add(sb.toString())
                }
                else -> return null
            }
        }
        return null
    }

    /** Folds the `2935+-1459*2` arithmetic the obfuscator hides every integer behind. */
    private fun fold(expression: String): Int {
        var total = 0
        for (term in TERM_REGEX.findAll(expression.replace(" ", "")).map(MatchResult::value)) {
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }
            var value = 1
            for (factor in body.split('*')) value *= factor.toIntOrNull() ?: return 0
            total += sign * value
        }
        return total
    }

    private val BUILD_ID_REGEX = Regex("""!==\s*["']string["']\s*\?\s*["'](\d+)["']\s*:\s*["']["']""")

    private val TABLE_HEAD_REGEX = Regex("""function (\w+)\(\)\s*\{\s*(?:const|let|var)\s+\w+\s*=\s*\[""")

    // Subtracts a constant from its argument and indexes the string table with the result.
    private val BASE_DECODER_REGEX = Regex("""function (\w+)\((\w+)(?:,\w+)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,(\w+)\(\)\[\2\]\}""")

    // Forwards one of its two parameters to a base decoder, optionally shifting it. Kept to
    // exactly two parameters on purpose: the argIndex logic above only distinguishes first
    // from second.
    private val ALIAS_DECODER_REGEX = Regex("""function (\w+)\((\w+),(\w+)\)\{return (\w+)\((\w+)((?:[-+][\d+*\s-]+)?)\)\}""")

    private const val CALL_PATTERN = """(\w+)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)

    // Four elements, each two concatenated lookups — the shape is what tells this array apart
    // from every other array literal in the chunk.
    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$CALL_PATTERN\+$CALL_PATTERN,){3}$CALL_PATTERN\+$CALL_PATTERN)]""")

    // 8 bytes of base64. Discriminating enough that only the true rotation produces four of them.
    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")

    private val TERM_REGEX = Regex("""[-+]?[^-+]+""")
}
