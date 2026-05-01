package eu.kanade.tachiyomi.animeextension.en.zoro

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.megacloudextractor.MegaCloudExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getEditTextPreference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Element

class HiAnime :
    ZoroTheme(
        "en",
        "HiAnime",
        "https://hianime.to",
        hosterNames = listOf(
            "HD-1",
            "HD-2",
            "HD-3",
            "StreamTape",
        ),
    ) {
    override val id = 6706411382606718900L
    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by LazyMutable { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    // Caching baseUrl using a backing field to avoid multiple SharedPreferences reads
    override var baseUrl by LazyMutable { initBaseUrl() }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/recently-updated?page=$page",
        docHeaders,
    )

    override fun popularAnimeFromElement(element: Element): SAnime = super.popularAnimeFromElement(element).apply {
        url = url.substringBefore("?")
    }

    override fun extractVideo(server: VideoData): List<Video> = when (server.name) {
        "StreamTape" -> {
            streamtapeExtractor.videoFromUrl(
                server.link,
                "Streamtape - ${server.type}",
            )?.let(::listOf) ?: emptyList()
        }

        "HD-1", "HD-2", "HD-3" -> megaCloudExtractor.getVideosFromUrl(
            server.link,
            server.type,
            server.name,
        )

        else -> emptyList()
    }

    private val SharedPreferences.preferredDomain by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    private var SharedPreferences.customDomain by preferences.delegate(PREF_DOMAIN_CUSTOM_KEY, "")

    private fun initBaseUrl() = preferences.customDomain.domainFallback()
    private fun String.domainFallback() = this.trim().ifBlank { preferences.preferredDomain }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        val customDomainPref = screen.getEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = "Custom domain",
            dialogMessage = "Enter custom domain (e.g., https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "The URL is invalid, malformed, or ends with a slash" },
        ) { newValue ->
            newValue.domainFallback().let {
                baseUrl = it
                docHeaders = newHeaders()
            }
        }

        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            preferences.customDomain = ""
            customDomainPref.summary = ""
            baseUrl = it
            docHeaders = newHeaders()
        }

        screen.addPreference(customDomainPref)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"

        private val DOMAIN_ENTRIES = listOf(
            "hianime.to",
            "hianime.nz",
            "hianime.sx",
            "hianime.is",
            "hianime.bz",
            "hianime.pe",
            "hianime.cx",
            "hianime.do",
            "hianimez.is",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}
