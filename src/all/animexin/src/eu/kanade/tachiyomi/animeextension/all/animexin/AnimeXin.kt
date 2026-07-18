package eu.kanade.tachiyomi.animeextension.all.animexin

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.YouTubeExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import org.jsoup.nodes.Document

class AnimeXin :
    AnimeStream(
        "all",
        "AnimeXin",
        "https://animexin.dev",
    ) {
    override val id = 4620219025406449669

    // =========================== Anime Details ============================
    override fun getAnimeDescription(document: Document): String? {
        val description = super.getAnimeDescription(document) ?: return null
        val englishIdx = description.indexOf("English", ignoreCase = true)
        val indonesiaIdx = description.indexOf("Indonesia", ignoreCase = true)

        return if (englishIdx != -1 && indonesiaIdx != -1 && englishIdx < indonesiaIdx) {
            val isIndo = preferences.langPref == "Indonesia"
            val result = if (isIndo) {
                description.substring(indonesiaIdx + "Indonesia".length)
            } else {
                description.substring(englishIdx + "English".length, indonesiaIdx)
            }
            result.trim().removePrefix(":").trim()
        } else {
            description
        }
    }

    // ============================ Video Links =============================
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vidstreamingExtractor by lazy { VidstreamingExtractor(client) }
    private val youTubeExtractor by lazy { YouTubeExtractor(client) }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix)

            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)

            url.contains("https://dood") -> doodExtractor.videosFromUrl(url, name)

            url.contains("gdriveplayer") -> {
                val gdriveHeaders = headersBuilder()
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Referer", "$baseUrl/")
                    .build()
                gdrivePlayerExtractor.videosFromUrl(url, name, gdriveHeaders)
            }

            url.contains("youtube.com") -> youTubeExtractor.videosFromUrl(url, prefix)

            url.contains("vidstreaming") -> vidstreamingExtractor.videosFromUrl(url, prefix)

            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = PREF_LANG_TITLE,
            entries = PREF_LANG_VALUES,
            entryValues = PREF_LANG_VALUES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )
    }

    private val SharedPreferences.langPref by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val language = preferences.langPref

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(language, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_TITLE = "Preferred Video Language"
        private const val PREF_LANG_DEFAULT = "All Sub"
        private val PREF_LANG_VALUES = listOf(
            "All Sub", "Arabic", "English", "German", "Indonesia", "Italian",
            "Polish", "Portuguese", "Spanish", "Thai", "Turkish",
        )
    }
}
