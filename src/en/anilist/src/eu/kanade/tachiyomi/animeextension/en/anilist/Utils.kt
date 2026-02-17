package eu.kanade.tachiyomi.animeextension.en.anilist

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

fun parseDate(dateStr: String): Long = try {
    DATE_FORMAT.parse(dateStr)!!.time
} catch (_: ParseException) {
    0L
}

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
