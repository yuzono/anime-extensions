package eu.kanade.tachiyomi.animeextension.en.animenosub

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.MoonExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.VtubeExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import org.jsoup.nodes.Element

class Animenosub :
    AnimeStream(
        "en",
        "Animenosub",
        "https://animenosub.to",
    ) {
    // ============================== Episodes ==============================
    override fun getEpisodeName(element: Element, epNum: String): String = element.selectFirst("div.epl-title")?.text()
        ?.takeIf { !it.contains("Episode $epNum", true) }
        .let {
            listOfNotNull(
                "Ep. $epNum",
                it,
            ).joinToString(" ")
        }

    // ============================ Video Links =============================

    override suspend fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            listOf(
                "bysesayeveum",
                "filemoon",
                "fmoon",
                "moonembed",
            ).any(url::contains) -> {
                MoonExtractor(client, headers, baseUrl).videosFromUrl(url, prefix)
            }
            url.contains("vidmoly") -> {
                VidMolyExtractor(client, headers).videosFromUrl(url, prefix.trim())
            }
            listOf(
                "streamwish",
                "swdyu",
            ).any(url::contains) -> {
                val wishHeaders = headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()
                StreamWishExtractor(client, wishHeaders).videosFromUrl(url, prefix)
            }
            listOf(
                "vtbe",
                "vtube",
            ).any(url::contains) -> {
                VtubeExtractor(client, headers).videosFromUrl(url, baseUrl, prefix)
            }
            url.contains("wolfstream") -> {
                WolfstreamExtractor(client).videosFromUrl(url, prefix)
            }
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = PREF_TYPE_TITLE,
            entries = PREF_TYPE_VALUES,
            entryValues = PREF_TYPE_VALUES,
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = PREF_SERVER_TITLE,
            entries = PREF_SERVER_VALUES,
            entryValues = PREF_SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================
    private val SharedPreferences.typePref by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val type = preferences.typePref
        val server = preferences.serverPref
        return sortedWith(
            compareBy(
                { it.quality.contains(type, ignoreCase = true) },
                { it.quality.contains(quality, ignoreCase = true) },
                { it.quality.contains(server, ignoreCase = true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_TITLE = "Preferred Video Type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private val PREF_TYPE_VALUES = listOf("SUB", "RAW")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred Video Server"
        private const val PREF_SERVER_DEFAULT = "Moon"
        private val PREF_SERVER_VALUES = listOf(
            "Moon",
            "StreamWish",
            "VidMoly",
            "Vtube",
            "WolfStream",
        )
    }
}
