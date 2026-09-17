package keiyoushi.utils

object TextUtils {

    private val EPISODE_NUMBER_REGEX = Regex("""(?:Episode|Ep\.?|Episodio|Folge|Épisode)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE_REGEX = Regex("""[Ss](\d+)[Ee](\d+)""")
    private val FLOAT_EPISODE_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    private val QUALITY_REGEX = Regex("""(\d+)p""")

    fun extractEpisodeNumber(text: String): Float? {
        EPISODE_NUMBER_REGEX.find(text)?.let {
            return it.groupValues[1].toFloatOrNull()
        }
        SEASON_EPISODE_REGEX.find(text)?.let {
            return it.groupValues[2].toFloatOrNull()
        }
        FLOAT_EPISODE_REGEX.find(text)?.let {
            return it.groupValues[1].toFloatOrNull()
        }
        return null
    }

    fun extractEpisodeNumberInt(text: String): Int? = extractEpisodeNumber(text)?.toInt()

    fun extractQuality(text: String): String? = QUALITY_REGEX.find(text)?.groupValues?.get(0)

    fun extractQualityNumber(text: String): Int? = QUALITY_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun cleanAnimeTitle(title: String, suffixes: List<String> = DEFAULT_SUFFIXES): String {
        var cleaned = title
        for (suffix in suffixes) {
            cleaned = cleaned.replace(suffix, "", ignoreCase = true)
        }
        return cleaned.trim()
    }

    fun removeSuffix(title: String, vararg suffixes: String): String {
        var cleaned = title
        for (suffix in suffixes) {
            cleaned = cleaned.replace(suffix, "", ignoreCase = true)
        }
        return cleaned.trim()
    }

    fun normalizeLanguageCode(code: String): String = when (code.lowercase()) {
        "id", "ind", "indo" -> "id"
        "pt", "pt-br", "por" -> "pt"
        "es", "spa" -> "es"
        "en", "eng" -> "en"
        "ja", "jpn" -> "ja"
        "ko", "kor" -> "ko"
        "zh", "zho", "cn" -> "zh"
        else -> code
    }

    private val DEFAULT_SUFFIXES = listOf(
        " Subtitle Indonesia",
        " Sub Indo",
        " Sub Ita",
        " Dub ITA",
        " (ITA)",
        " ITA",
        " Detail Anime",
        " Online",
        " Watch Online",
        " Streaming",
        " Watch",
    )
}
