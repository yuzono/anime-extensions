package eu.kanade.tachiyomi.animeextension.all.chineseanime

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.all.chineseanime.extractors.VatchusExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate

class ChineseAnime :
    AnimeStream(
        "all",
        "ChineseAnime",
        "https://www.chineseanime.vip",
    ) {

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = "div.mrgn > a.r"

    // =========================== Anime Details ============================
    override val animeDescriptionSelector = ".entry-content"

    // ============================== Filters ===============================
    override val filtersSelector = "div.filter > ul"

    // ============================ Video Links =============================
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vatchusExtractor by lazy { VatchusExtractor(client, headers) }

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
            url.contains("embedwish") -> streamwishExtractor.videosFromUrl(url, prefix)
            url.contains("vatchus") -> vatchusExtractor.videosFromUrl(url, prefix)
            url.contains("donghua.xyz/v/") -> vidHideExtractor.videosFromUrl(url) { "$prefix $it" }
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
            "All Sub", "Arabic", "English", "Indonesia", "Persian", "Malay",
            "Polish", "Portuguese", "Spanish", "Thai", "Vietnamese",
        )
    }
}
