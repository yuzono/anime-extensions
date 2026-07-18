package eu.kanade.tachiyomi.animeextension.es.animeytes

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy

class AnimeYTES :
    AnimeStream(
        "es",
        "AnimeYT.es",
        "https://animeyt.es",
    ) {
    override val preferences by getPreferencesLazy()

    override val prefQualityDefault = "1080"
    override val prefQualityValues = listOf("1080", "720", "480", "360")

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Amazon"
        private val SERVER_LIST = listOf(
            "YourUpload",
            "SendVid",
            "BurstCloud",
            "StreamTape",
            "Filemoon",
            "Okru",
        )
    }

    override val animeListUrl = "$baseUrl/tv"

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstcloudExtractor by lazy { BurstCloudExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when (name) {
        "OK" -> okruExtractor.videosFromUrl(url)
        "Stream" -> streamtapeExtractor.videosFromUrl(url)
        "Send" -> sendvidExtractor.videosFromUrl(url)
        "Your" -> youruploadExtractor.videoFromUrl(url, headers)
        "Alpha" -> burstcloudExtractor.videoFromUrl(url, headers)
        "Moon" -> filemoonExtractor.videosFromUrl(url)
        else -> universalExtractor.videosFromUrl(url, headers)
    }

    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val server = preferences.serverPref
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }
}
