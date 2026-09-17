package keiyoushi.utils

import org.jsoup.nodes.Element

@Deprecated("Use keiyoushi.templating.getImageUrl() instead", ReplaceWith("getImageUrl()", "keiyoushi.templating.getImageUrl"))
fun Element.getImageUrl(): String? = when {
    hasAttr("data-src") -> attr("abs:data-src")
    hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
    hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
    else -> attr("abs:src")
}?.substringBefore("?resize")

@Deprecated("Use keiyoushi.templating.getBackgroundImageUrl() instead", ReplaceWith("getBackgroundImageUrl()", "keiyoushi.templating.getBackgroundImageUrl"))
fun Element.getBackgroundImageUrl(): String? {
    val style = attr("style")
    val regex = Regex("""background-image:\s*url\(["']?([^"')]+)["']?\)""")
    return regex.find(style)?.groupValues?.get(1)
}

@Deprecated("Use keiyoushi.templating.getInfo() instead", ReplaceWith("getInfo(tag)", "keiyoushi.templating.getInfo"))
fun Element.getInfo(tag: String): String? = selectFirst("div:contains($tag), span:contains($tag), li:contains($tag)")
    ?.selectFirst("a, span.text, span.name, b + span, b + a")
    ?.text()
    ?.trim()

@Deprecated("Use keiyoushi.templating.getInfoFull() instead", ReplaceWith("getInfoFull(tag, full)", "keiyoushi.templating.getInfoFull"))
fun Element.getInfoFull(tag: String, full: Boolean = false): String? {
    val value = getInfo(tag)
    return if (full && value != null) "\n$tag $value" else value
}

@Deprecated("Use keiyoushi.templating.getInfoList() instead", ReplaceWith("getInfoList(tag)", "keiyoushi.templating.getInfoList"))
fun Element.getInfoList(tag: String): List<String> = select("div:contains($tag) a, span:contains($tag) a")
    .eachText()
    .map { it.trim() }
    .filter { it.isNotBlank() }
