package eu.kanade.tachiyomi.animeextension.all.lmanime

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Response

class LMAnime :
    AnimeStream(
        "all",
        "LMAnime",
        "https://lmanime.com",
    ) {
    // ============================ Video Links =============================
    override val prefQualityValues = listOf("144p", "288p", "480p", "720p", "1080p")

    override fun videoListParse(response: Response): List<Video> {
        val items = response.useAsJsoup().select(videoListSelector())
        val allowed = preferences.allowedLangsPref
        return items
            .filter { element ->
                val text = element.text()
                allowed.any { it in text }
            }.parallelCatchingFlatMapBlocking {
                val language = it.text().substringBefore(" ")
                val url = getHosterUrl(it)
                getVideoList(url, language)
            }
    }

    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val dailyExtractor by lazy { DailymotionExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "($name) - "
        return when {
            "dailymotion" in url -> dailyExtractor.videosFromUrl(url, "Dailymotion ($name)")
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers, prefix)
            "filelions" in url -> streamwishExtractor.videosFromUrl(url, "StreamWish ($name)")
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = PREF_LANG_TITLE,
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_ENTRIES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_ALLOWED_LANGS_KEY,
            title = PREF_ALLOWED_LANGS_TITLE,
            entries = PREF_ALLOWED_LANGS_ENTRIES,
            entryValues = PREF_ALLOWED_LANGS_ENTRIES,
            default = PREF_ALLOWED_LANGS_DEFAULT,
            summary = "",
        )
    }

    private val SharedPreferences.langPref by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)
    private val SharedPreferences.allowedLangsPref by preferences.delegate(PREF_ALLOWED_LANGS_KEY, PREF_ALLOWED_LANGS_DEFAULT)

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val lang = preferences.langPref
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(lang, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "pref_language"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "English"
        private val PREF_LANG_ENTRIES = listOf(
            "English",
            "Español",
            "Indonesian",
            "Portugués",
            "Türkçe",
            "العَرَبِيَّة",
            "ไทย",
        )

        private const val PREF_ALLOWED_LANGS_KEY = "pref_allowed_languages"
        private const val PREF_ALLOWED_LANGS_TITLE = "Allowed languages to fetch videos"
        private val PREF_ALLOWED_LANGS_ENTRIES = PREF_LANG_ENTRIES
        private val PREF_ALLOWED_LANGS_DEFAULT = PREF_ALLOWED_LANGS_ENTRIES.toSet()
    }
}
