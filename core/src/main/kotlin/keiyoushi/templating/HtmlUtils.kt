package keiyoushi.templating

/**
 * Strips HTML tags from a string, converting block elements to newlines.
 */
fun stripHtml(input: String): String = input
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .trim()
