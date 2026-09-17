package keiyoushi.utils

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

fun SimpleDateFormat.tryParse(date: String?): Long {
    date ?: return 0L
    return parse(date, ParsePosition(0))?.time ?: 0L
}

object DateUtils {

    private val COMMON_FORMATS = mapOf(
        "yyyy-MM-dd" to Locale.ENGLISH,
        "dd/MM/yyyy" to Locale.ENGLISH,
        "MM/dd/yyyy" to Locale.ENGLISH,
        "d MMMM yyyy" to Locale.ENGLISH,
        "dd MMMM yyyy" to Locale.ENGLISH,
        "MMMM dd, yyyy" to Locale.ENGLISH,
        "dd MMM yyyy" to Locale.ENGLISH,
        "yyyy-MM-dd'T'HH:mm:ss" to Locale.ENGLISH,
        "yyyy-MM-dd'T'HH:mm:ss.SSS" to Locale.ENGLISH,
        "yyyy-MM-dd'T'HH:mm:ssZ" to Locale.ENGLISH,
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ" to Locale.ENGLISH,
    )

    fun parseDate(
        date: String?,
        vararg formats: String,
        locale: Locale = Locale.ENGLISH,
    ): Long {
        date ?: return 0L
        val formatsToTry = if (formats.isNotEmpty()) {
            formats.map { SimpleDateFormat(it, locale) }
        } else {
            COMMON_FORMATS.map { (format, loc) -> SimpleDateFormat(format, loc) }
        }
        for (formatter in formatsToTry) {
            val result = formatter.tryParse(date)
            if (result > 0L) return result
        }
        return 0L
    }

    fun parseDateOrNull(
        date: String?,
        vararg formats: String,
        locale: Locale = Locale.ENGLISH,
    ): Long? {
        val result = parseDate(date, *formats, locale = locale)
        return if (result > 0L) result else null
    }

    fun formatDate(timestamp: Long, format: String = "yyyy-MM-dd", locale: Locale = Locale.ENGLISH): String {
        val formatter = SimpleDateFormat(format, locale)
        return formatter.format(timestamp)
    }
}
