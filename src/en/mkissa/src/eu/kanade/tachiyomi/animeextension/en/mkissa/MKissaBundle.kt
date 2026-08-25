package eu.kanade.tachiyomi.animeextension.en.mkissa

/**
 * Recovers `buildId` and the four mask seeds from the obfuscated JS chunk. The seeds are lookups
 * into a string table rotated at load time by an amount only the bundle's checksum loop knows, so
 * [parse] tries every rotation and keeps the one whose results all have the seed shape.
 */
object MKissaBundle {

    class BuildInfo(val buildId: String, val seeds: List<String>)

    fun parse(js: String): BuildInfo? {
        // Legacy path: literal buildId like `!== "string" ? "12345" : ""`
        BUILD_ID_REGEX.find(js)?.groupValues?.get(1)?.let { legacyId ->
            extractSeeds(js)?.let { seeds -> return BuildInfo(legacyId, seeds) }
        }

        // New obfuscation: buildId is a decoded string via the same table rotation as seeds,
        // e.g. `const Mm=zt(520,520)` where the call resolves to "132" after rotation.
        // We reuse the same table/bases/aliases machinery and brute-force the rotation.
        val (tables, bases, aliases) = decodersFrom(js)

        val buildId = extractBuildIdNew(js, tables, bases, aliases) ?: return null
        val seeds = extractSeedsWithTables(js, tables, bases, aliases) ?: return null
        return BuildInfo(buildId, seeds)
    }

    private fun extractBuildIdNew(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        // Strategy 1: look for the variable used as default param for the mask function F_(e=Mm).
        // The mask function is the one that takes buildId as default and is later called as F_(_r).
        // Its name is minified (F_, U_, etc.) but we can find `function F_(e=Mm)` pattern.
        val maskDefaultVar = Regex("""function\s+($IDENT)\s*\(\s*\w+\s*=\s*(\w+)\s*[,)]""").findAll(js)
            .mapNotNull { it.groupValues[2].takeIf(String::isNotEmpty) }
            .firstOrNull { varName ->
                // The variable should be assigned via a single decoder call near the seed array
                Regex("""\b${Regex.escape(varName)}\s*=\s*$CALL_PATTERN""").containsMatchIn(js)
            }

        val candidates = mutableListOf<String>()

        if (maskDefaultVar != null) {
            val assignRegex = Regex("""\b${Regex.escape(maskDefaultVar)}\s*=\s*($CALL_PATTERN)""")
            assignRegex.findAll(js).forEach { m ->
                candidates.add(m.groupValues[1])
            }
        }

        // Fallback: look near `sf=` (seed array) for a preceding single-call assignment
        val sfIndex = js.indexOf("sf=")
        if (sfIndex != -1) {
            val windowStart = (sfIndex - 2000).coerceAtLeast(0)
            val window = js.substring(windowStart, sfIndex)
            val assignRegex = Regex("""\b\w+\s*=\s*($CALL_PATTERN)\s*(?:,|;|\n)""")
            assignRegex.findAll(window).forEach { m ->
                val call = m.groupValues[1]
                // Exclude the seed array itself which is `=[call+call, ...]`
                if (!call.contains("+")) {
                    candidates.add(call)
                }
            }
        }

        // Last resort: any single CALL assignment that resolves to digits
        if (candidates.isEmpty()) {
            val assignRegex = Regex("""\b\w+\s*=\s*($CALL_PATTERN)\b""")
            assignRegex.findAll(js).forEach { m ->
                val call = m.groupValues[1]
                if (!call.contains("+")) candidates.add(call)
            }
        }

        // Try each candidate call with every rotation, looking for a numeric buildId
        for (call in candidates) {
            // Find which table this call belongs to
            val aliasName = CALL_REGEX.find(call)?.groupValues?.get(1) ?: continue
            val alias = aliases[aliasName] ?: continue
            val base = bases[alias.base] ?: continue
            val table = tables[base.table] ?: continue

            for (rotation in table.indices) {
                val decoded = resolve(call, rotation, tables, bases, aliases) ?: continue
                if (decoded.matches(BUILD_ID_DIGITS_REGEX)) {
                    // Require that the same rotation also yields valid seeds, to avoid false positives
                    // (e.g. "211" salt value). Check that seeds resolve under this rotation.
                    val seedsOk = extractSeedsWithTables(js, tables, bases, aliases, forcedRotation = rotation) != null
                    if (seedsOk) return decoded
                }
            }
        }

        // Final fallback: brute-force any CALL that decodes to digits, even if not an assignment
        for (match in CALL_REGEX.findAll(js)) {
            val call = match.value
            if (call.contains("+")) continue
            val aliasName = match.groupValues[1]
            val alias = aliases[aliasName] ?: continue
            val base = bases[alias.base] ?: continue
            val table = tables[base.table] ?: continue
            for (rotation in table.indices) {
                val decoded = resolve(call, rotation, tables, bases, aliases) ?: continue
                if (decoded.matches(BUILD_ID_DIGITS_REGEX) && decoded.length in 2..8) {
                    // Heuristic: buildId is 2-8 digits, seeds are base64 12 chars with =
                    // Ensure this call is not part of the seed array (seed array calls are in a `=[...]` context)
                    val before = js.substring((match.range.first - 20).coerceAtLeast(0), match.range.first)
                    if (before.contains("sf=") || before.contains("kd=")) continue
                    if (extractSeedsWithTables(js, tables, bases, aliases, forcedRotation = rotation) == null) continue
                    return decoded
                }
            }
        }
        return null
    }

    private class Base(val table: String, val offset: Int)
    private class Alias(val base: String, val argIndex: Int, val delta: Int)

    private fun decodersFrom(js: String): Triple<Map<String, List<String>>, Map<String, Base>, Map<String, Alias>> {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { m ->
            m.groupValues[1] to Base(m.groupValues[4], fold(m.groupValues[3]))
        }
        val aliases = buildMap {
            // A seed may call a base decoder directly, so each base is its own identity alias.
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { m ->
                val (name, firstParam, _, callee, arg, delta) = m.destructured
                if (callee !in bases) return@forEach
                // Which parameter the alias forwards tells us where the table index sits.
                put(name, Alias(callee, if (arg == firstParam) 0 else 1, if (delta.isEmpty()) 0 else fold(delta)))
            }
        }
        return Triple(tables, bases, aliases)
    }

    private fun extractSeeds(js: String): List<String>? {
        val (tables, bases, aliases) = decodersFrom(js)
        return extractSeedsWithTables(js, tables, bases, aliases)
    }

    private fun extractSeedsWithTables(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
        forcedRotation: Int? = null,
    ): List<String>? {
        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val calls = CALL_REGEX.findAll(match.groupValues[1]).map(MatchResult::value).toList()
            if (calls.size != MKissaCrypto.SEED_COUNT * 2) continue

            val table = CALL_REGEX.find(calls.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue

            if (forcedRotation != null) {
                seedsAt(calls, forcedRotation, tables, bases, aliases)?.let { return it }
                continue
            }

            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(calls, rotation, tables, bases, aliases)
            }
            // A chance match would silently yield a bad mask, so require an unambiguous answer.
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
        return seeds.takeIf { it.size == MKissaCrypto.SEED_COUNT }
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

    /** Folds the `2935+-1459*2` arithmetic every integer is hidden behind; signs stack.
     *  2025-09: the obfuscator started emitting negative factors (`2461*-4`), which the old
     *  term scan split mid-product and misread as additions, zeroing every decoder offset.
     */
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
    private val BUILD_ID_DIGITS_REGEX = Regex("""\d{2,10}""")

    // The obfuscator names functions with `$` too (`$l`, `Cr`), which `\w` excludes. The `${'$'}`
    // interpolation yields the literal dollar sign without starting a template.
    private val IDENT = """[${'$'}A-Za-z0-9_]+"""

    private val TABLE_HEAD_REGEX = Regex("""function ($IDENT)\(\)\s*\{\s*(?:const|let|var)\s+$IDENT\s*=\s*\[""")

    private val BASE_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT)(?:,$IDENT)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,($IDENT)\(\)\[\2\]\}""")

    // Two parameters exactly: the argIndex logic only distinguishes first from second.
    private val ALIAS_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT),($IDENT)\)\{return ($IDENT)\(($IDENT)((?:[-+][\d+*\s-]+)?)\)\}""")

    private val CALL_PATTERN = """($IDENT)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)

    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$CALL_PATTERN\+$CALL_PATTERN,){3}$CALL_PATTERN\+$CALL_PATTERN)]""")

    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")

    // Leading signs matched greedily; `fold` counts them. Factors may be negative, so the
    // product is kept inside one term (`2461*-4`) instead of splitting at every sign.
    private val TERM_REGEX = Regex("""[-+]*\d+(?:\*[-+]*\d+)*""")
}
