package eu.kanade.tachiyomi.animeextension.en.animeverse

import android.annotation.SuppressLint
import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// =========================== SAnime Mappers ==============================

fun jsonToAnime(el: JsonElement, useAlt: Boolean, baseUrl: String): SAnime {
    val o = el.jsonObject
    val genres = o.stringArray("genres")
    val studios = o.stringArray("studios")
    val mainTitle = o.string("title") ?: "Unknown"
    val altTitle = o.string("alternativeTitle")?.takeIf { it.isNotEmpty() }

    return SAnime.create().apply {
        title = if (useAlt) altTitle ?: mainTitle else mainTitle
        url = "/series/${o.string("slug")}"
        thumbnail_url = resolveImage(baseUrl, o.string("cover") ?: o.string("thumb"))
        author = studios.takeIf { it.isNotEmpty() }?.joinToString(", ")
        genre = genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
        status = SAnime.UNKNOWN
    }
}

fun recentToAnime(el: JsonElement, baseUrl: String): SAnime {
    val o = el.jsonObject
    return SAnime.create().apply {
        title = o.string("seriesTitle") ?: "Unknown"
        url = "/series/${o.string("seriesSlug")}"
        thumbnail_url = resolveImage(baseUrl, o.string("thumb"))
        genre = o.string("language")?.uppercase() ?: o.string("releaseTime")
        status = SAnime.UNKNOWN
    }
}

// =========================== Helpers ==============================

fun SAnime.slug(): String = url.substringAfter("/series/")

private val regexSpecialCharacters = Regex("""[^a-zA-Z0-9\s]""")
private val regexWhitespace = Regex("""\s+""")
private val regexNumberOnly = Regex("""^\d+$""")

fun String.stripKeywordForRelatedAnimes(): List<String> = replace(regexSpecialCharacters, " ")
    .split(regexWhitespace)
    .map {
        it.replace(regexNumberOnly, "")
            .lowercase()
    }
    .filter { it.length > 2 } // Increase minimum length to 3 to ignore short common words

fun resolveImage(baseUrl: String, path: String?): String? {
    if (path.isNullOrEmpty()) return null
    if (path.startsWith("http")) return path
    if (path.startsWith("/i/")) {
        runCatching { String(base64UrlDecode(path.substringAfter("/i/")), Charsets.UTF_8) }
            .getOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
    }
    return "$baseUrl$path"
}

@SuppressLint("DefaultLocale")
fun formatRating(rating10: Double): String {
    if (rating10 <= 0) return ""
    val fullStars = (rating10 / 2.0).roundToInt().coerceIn(0, 5)
    val emptyStars = 5 - fullStars
    val stars = "★".repeat(fullStars) + "☆".repeat(emptyStars)
    return "$stars ${String.format("%.2f", rating10)}"
}

fun base64UrlEncode(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

fun base64UrlDecode(str: String): ByteArray = Base64.decode(str, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

// =========================== JSON Extensions ==============================

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

fun JsonObject.double(key: String): Double = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

fun extractArray(root: JsonElement): List<JsonElement> = when (root) {
    is JsonArray -> root
    is JsonObject -> (root["items"] ?: root["data"] ?: root.values.firstOrNull { it is JsonArray })
        as? JsonArray ?: emptyList()
    else -> emptyList()
}

// =========================== Video Helpers ==============================

fun cleanQuality(q: String): String {
    Regex("""(\d{3,4})p""").find(q)?.let { return it.value }
    Regex("""\dx(\d+)""").find(q)?.groupValues?.get(1)?.let { return "${it}p" }
    return q.substringBefore(" - ").substringBefore("(").trim()
}

fun extractBaseUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val pathStart = url.indexOf("/", schemeEnd + 3)
    return if (pathStart > 0) url.substring(0, pathStart) else url
}

fun isMegaplayStream(path: String): Boolean = path.contains("/stream/mal/") ||
    path.contains("/stream/ani/") ||
    path.contains("/stream/s-") ||
    path.contains("megaplay") ||
    path.contains("vidwish")
