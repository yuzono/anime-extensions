package eu.kanade.tachiyomi.animeextension.ru.animego

import java.util.Locale

internal object AnimegoPatterns {
    val KODIK_URL_PARAMS = Regex("""var urlParams = '(.*?)';""")
    val KODIK_VINFO_TYPE = Regex("""vInfo\.type = '(.*?)';""")
    val KODIK_VINFO_ID = Regex("""vInfo\.id = '(.*?)';""")
    val KODIK_VINFO_HASH = Regex("""vInfo\.hash = '(.*?)';""")
    val CONTROLLER_ID = Regex("""(?:^|&)id=(\d+)""")
    val NORMALIZE_ID = Regex("[^\\p{L}\\p{Nd}]+")
    val QUALITY_NUMBER = Regex("""(\d{3,4})""")
    val WHITESPACE = Regex("\\s+")
    val BORTH_SEED = Regex(
        """<meta\s+name=["']viewporti["']\s+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    val EPISODE_SEASON = Regex("""[?&]animego-season=(\d+)""")
    val EPISODE_NUMBER = Regex("""[?&]animego-episode=(\d+)""")
    val URL_SEASON = Regex("""(?:^|[-_/])(\d+)-sezon(?:-|\.|/|$)""", RegexOption.IGNORE_CASE)
    val TITLE_SEASON = Regex("""(?:сезон\s*(\d+)|(\d+)\s*сезон)""", RegexOption.IGNORE_CASE)
    val ALLOHA_SOURCE = Regex(""""src"\s*:\s*"((?:\\.|[^"])*)"""")
    val ALLOHA_FILE_LIST_SIMPLE = Regex("""fileList\s*=\s*JSON\.parse\(\s*'([^']*)'\s*\)""")
    val ALLOHA_ACTIVE_ID = Regex(""""active"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)""")
    val ALLOHA_NUMERIC_TOKEN = Regex("""(?:^|[^0-9])(\d{1,6})(?:[^0-9]|$)""")
    val ALLOHA_SEASON_EPISODE = Regex("""(?:^|[._/\-])s(\d{1,2})e(\d{1,3})(?:[._/\-]|$)""")
    val ALLOHA_EPISODE_TOKEN = Regex("""__(\d{1,3})(?:[._/\-]|$)""")
    val HLS_RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
    val HLS_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    val HLS_VARIANT_PATH = Regex("""(?:^|/)(2160|1440|1080|720|480|360|240|144)(?:/|p\b|$)""")

    val ALLOHA_FILE_LIST = listOf(
        Regex("""fileList\s*=\s*JSON\.parse\(\s*'((?:\\.|[^'])*)'\s*\)"""),
        Regex("""fileList\s*=\s*JSON\.parse\(\s*"((?:\\.|[^"])*)"\s*\)"""),
        Regex("""fileList\s*=\s*JSON\.parse\(\s*`((?:\\.|[^`])*)`\s*\)"""),
    )

    fun allohaQualityKey(qualityKey: String): Regex = Regex(""""${Regex.escape(qualityKey)}"\s*:\s*"((?:\\.|[^"])*)"""")
}

internal fun String.containsToken(token: String): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return normalized.contains(token.lowercase(Locale.ROOT))
}
