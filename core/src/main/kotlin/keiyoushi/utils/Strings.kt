package keiyoushi.utils

private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

/**
 * Replaces every `\uXXXX` escape sequence in this string with the character it encodes,
 * e.g. `Fran\u00e7ais` -> `Français`.
 *
 * Other escape sequences (`\n`, `\/`, `\\`, ...) and malformed `\u` sequences are left untouched.
 * An escaped backslash does not shield a following `uXXXX`: `\\u0041` becomes `\A`, so callers that
 * strip the leftover backslashes afterwards also decode double-escaped input.
 */
fun String.unescapeUnicode(): String = UNICODE_ESCAPE_REGEX.replace(this) {
    it.groupValues[1].toInt(16).toChar().toString()
}
