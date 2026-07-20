package keiyoushi.utils

import org.jsoup.nodes.Element

/**
 * Extension functions for Jsoup Element to extract common data.
 */
fun Element.getImageUrl(): String? = when {
    hasAttr("data-src") -> attr("abs:data-src")
    hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
    hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
    else -> attr("abs:src")
}?.substringBefore("?resize")

fun Element.getBackgroundImageUrl(): String? {
    val style = attr("style")
    val regex = Regex("""background-image:\s*url\(["']?([^"')]+)["']?\)""")
    return regex.find(style)?.groupValues?.get(1)
}

fun Element.getInfo(tag: String): String? = selectFirst("div:contains($tag), span:contains($tag), li:contains($tag)")
    ?.selectFirst("a, span.text, span.name, b + span, b + a")
    ?.text()
    ?.trim()

fun Element.getInfoFull(tag: String, full: Boolean = false): String? {
    val value = getInfo(tag)
    return if (full && value != null) "\n$tag $value" else value
}

fun Element.getInfoList(tag: String): List<String> = select("div:contains($tag) a, span:contains($tag) a")
    .eachText()
    .map { it.trim() }
    .filter { it.isNotBlank() }
