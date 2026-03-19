package aniyomi.lib.jsunpacker

/**
 * This singleton class provides functionality to detect and unpack packed javascript based on Dean Edwards JavaScript's Packer.
 *
 * See [Dean Edwards JavaScript's Packer](http://dean.edwards.name/packer/)
 *
 * Copied from https://github.com/DatL4g/JsUnpacker
 */
object JsUnpacker {

    /**
     * Regex to detect packed functions.
     */
    private val packedRegex by lazy { Regex("""eval\(function\(p,a,c,k,e,[rd]?""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    /**
     * Regex to get and group the packed javascript.
     * Needed to get information and unpack the code.
     */
    private val packedExtractRegex by lazy { Regex("""\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    /**
     * Matches function names and variables to de-obfuscate the code.
     */
    private val unpackReplaceRegex by lazy { Regex("""\b\w+\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    /**
     * Check if script is packed.
     *
     * @param scriptBlock the String to check if it is packed.
     *
     * @return whether the [scriptBlock] contains packed code or not.
     */
    fun detect(scriptBlock: String): Boolean = scriptBlock.contains(packedRegex)

    /**
     * Check if scripts are packed.
     *
     * @param scriptBlock (multiple) String(s) to check if it is packed.
     *
     * @return the packed scripts passed in [scriptBlock].
     */
    fun detect(vararg scriptBlock: String): List<String> = scriptBlock.mapNotNull {
        if (it.contains(packedRegex)) it else null
    }

    /**
     * Check if scripts are packed.
     *
     * @param scriptBlocks multiple Strings to check if it is packed.
     *
     * @return the packed scripts passed in [scriptBlocks].
     */
    fun detect(scriptBlocks: Collection<String>): List<String> = detect(*scriptBlocks.toTypedArray())

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return unpacked code in a list or an empty list if non is packed.
     */
    fun unpack(scriptBlock: String): Sequence<String> = if (!detect(scriptBlock)) {
        emptySequence()
    } else {
        unpacking(scriptBlock)
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and combines them into a single String.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return unpacked code in a list combined by a whitespace to a single String.
     */
    fun unpackAndCombine(scriptBlock: String): String? {
        val unpacked = unpack(scriptBlock)
        return unpacked.joinToString(" ").takeIf(String::isNotBlank)
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlock (multiple) String(s) to unpack.
     *
     * @return unpacked code in a flat list or an empty list if non is packed.
     */
    fun unpack(vararg scriptBlock: String): List<String> {
        val packedScripts = detect(*scriptBlock)
        return packedScripts.flatMap {
            unpacking(it)
        }
    }

    /**
     * Unpack the passed [scriptBlocks].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlocks multiple Strings to unpack.
     *
     * @return unpacked code in a flat list or an empty list if non is packed.
     */
    fun unpack(scriptBlocks: Collection<String>): List<String> = unpack(*scriptBlocks.toTypedArray())

    /**
     * Unpacking functionality.
     * Match all found occurrences, get the information group and unbase it.
     * If found symtabs are more or less than the count provided in code, the occurrence will be ignored
     * because it cannot be unpacked correctly.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return a list of all unpacked code from all found packed and unpackable occurrences found.
     */
    private fun unpacking(scriptBlock: String): Sequence<String> {
        val unpacked = packedExtractRegex.findAll(scriptBlock).mapNotNull { result ->

            val payload = result.groups[1]?.value
            val symtab = result.groups[4]?.value?.split('|')
            val radix = result.groups[2]?.value?.toIntOrNull() ?: 10
            val count = result.groups[3]?.value?.toIntOrNull()
            val unbaser = Unbaser(radix)

            if (payload == null || symtab == null || count == null || symtab.size != count) {
                // Ignore this occurrence as it cannot be unpacked correctly
                null
            } else {
                payload.replace(unpackReplaceRegex) { match ->
                    val word = match.value
                    val unbased = symtab.getOrNull(unbaser.unbase(word)) ?: ""
                    unbased.ifEmpty { word }
                }
            }
        }
        return unpacked
    }
}
