package keiyoushi.templating

import org.jsoup.nodes.Element

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

fun Element.getImageUrls(): List<String> = select("img")
    .mapNotNull { it.getImageUrl() }
    .filter { it.isNotBlank() }

fun Element.getAllImageUrls(): List<String> {
    val urls = mutableListOf<String>()
    urls.addAll(getImageUrls())
    val bgUrl = getBackgroundImageUrl()
    if (!bgUrl.isNullOrBlank()) {
        urls.add(bgUrl)
    }
    return urls
}

fun Element.getTextContent(selector: String): String? = selectFirst(selector)?.text()?.trim()

fun Element.getHtmlContent(selector: String): String? = selectFirst(selector)?.html()?.trim()

fun Element.getAttribute(selector: String, attribute: String): String? = selectFirst(selector)?.attr(attribute)?.trim()

fun Element.getAbsoluteUrl(selector: String, attribute: String = "href"): String? = selectFirst(selector)?.absUrl(attribute)?.trim()

fun Element.getIntValue(selector: String): Int? = selectFirst(selector)?.text()?.trim()?.toIntOrNull()

fun Element.getFloatValue(selector: String): Float? = selectFirst(selector)?.text()?.trim()?.toFloatOrNull()

fun Element.extractInfoMap(vararg tags: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (tag in tags) {
        val value = getInfo(tag)
        if (!value.isNullOrBlank()) {
            map[tag.removeSuffix(":")] = value
        }
    }
    return map
}

fun Element.extractMetadata(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val infoTags = listOf("Status", "Genre", "Type", "Episodes", "Aired", "Premiered")
    for (tag in infoTags) {
        val value = getInfo(tag)
        if (!value.isNullOrBlank()) {
            map[tag] = value
        }
    }
    return map
}

fun Element.getMetaContent(name: String): String? = selectFirst("meta[name=$name], meta[property=$name]")
    ?.attr("content")
    ?.trim()

fun Element.getOpenGraphData(property: String): String? = getMetaContent("og:$property")

fun Element.parseNumber(text: String): Int? = text.replace(Regex("[^\\d]"), "").toIntOrNull()

fun Element.parseEpisodeNumber(text: String): Float? = text.replace(Regex("[^\\d.]"), "").toFloatOrNull()

fun Element.parseDate(dateString: String): String? {
    val dateRegex = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    val match = dateRegex.find(dateString)
    return match?.let {
        val (_, year, month, day) = it.destructured
        "$year-$month-$day"
    }
}
