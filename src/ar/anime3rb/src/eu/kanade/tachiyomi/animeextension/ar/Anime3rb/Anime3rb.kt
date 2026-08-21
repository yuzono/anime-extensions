package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.synchrony.Deobfuscator
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime3rb :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime3rb"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://anime3rb.com" }

    override val lang = "ar"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
  override fun popularAnimeSelector(): String =
    "div#postList div.col-xl-2 a"

override fun popularAnimeRequest(page: Int): Request =
    GET("$baseUrl/titles/list?page=$page", headers)

override fun popularAnimeFromElement(element: Element): SAnime {
    val anime = SAnime.create()

    anime.setUrlWithoutDomain(element.attr("href"))
    anime.title = element.select("h2.title-name").text()
    anime.thumbnail_url = element.select("img").attr("src")

    return anime
}

override fun popularAnimeNextPageSelector(): String =
    "ul.pagination li a.page-link[rel=next]"
        
    // ============================== Episodes ==============================
override fun episodeListSelector(): String =
    "div.epAll a"

private fun seasonsNextPageSelector(seasonNumber: Int): String =
    "div#seasonList div.col-xl-2:nth-child($seasonNumber)"

override fun episodeListParse(response: Response): List<SEpisode> {
    val episodes = mutableListOf<SEpisode>()
    var seasonNumber = 1

    fun addEpisodes(document: Document) {
        val episodeElements = document.select(episodeListSelector())

        if (episodeElements.isEmpty()) {
            document.select("div.shortLink").forEach {
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(
                    it.select("span#liskSh").text()
                )
                episode.name = "مشاهدة"
                episodes.add(episode)
            }
        } else {
            episodeElements.forEach {
                episodes.add(episodeFromElement(it))
            }

            document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                seasonNumber++

                val seasonUrl = "$baseUrl/?p=" +
                    it.select("div.seasonDiv")
                        .attr("onclick")
                        .substringAfterLast("=")
                        .substringBeforeLast("'")

                addEpisodes(
                    client.newCall(GET(seasonUrl, headers))
                        .execute()
                        .asJsoup()
                )
            }
        }
    }

    addEpisodes(response.asJsoup())
    return episodes.reversed()
}

override fun episodeFromElement(element: Element): SEpisode {
    val episode = SEpisode.create()

    episode.setUrlWithoutDomain(element.attr("href"))

    episode.name = element.text()

    episode.episode_number =
        element.text()
            .replace("الحلقة", "")
            .trim()
            .toFloatOrNull() ?: -1f

    return episode
}

    // ============================ Video Links =============================
override fun videoListSelector(): String =
    "li:contains(سيرفر)"

private val videoRegex by lazy {
    Regex("""(https?:)?//[^"]+\.m3u8""")
}

private val onClickRegex by lazy {
    Regex("""['"](https?://[^'"]+)['"]""")
}

override fun videoListParse(response: Response): List<Video> {
    return response.asJsoup()
        .select(videoListSelector())
        .parallelCatchingFlatMapBlocking { element ->

            val url = onClickRegex
                .find(element.attr("onclick"))
                ?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            val doc = client.newCall(GET(url, headers))
                .execute()
                .asJsoup()

            val script = doc.selectFirst(
                "script:containsData(video), script:containsData(mainPlayer)"
            )?.data()?.let(Deobfuscator::deobfuscateScript).orEmpty()

            val playlist = videoRegex.find(script)?.value

            if (playlist != null) {
                playlistUtils.extractFromHls(playlist)
            } else {
                emptyList()
            }
        }
}

override fun List<Video>.sort(): List<Video> {
    val quality = preferences.quality

    return sortedWith(
        compareBy { it.quality.contains(quality) }
    ).reversed()
}

override fun videoFromElement(element: Element): Video {
    throw UnsupportedOperationException()
}

override fun videoUrlParse(document: Document): List<Video> {
    throw UnsupportedOperationException()
}
    // =============================== Search ===============================
    override fun searchAnimeSelector(): String =
    "div#postList div.col-xl-2 a"

override fun searchAnimeFromElement(element: Element): SAnime {
    val anime = SAnime.create()

    anime.setUrlWithoutDomain(element.attr("href"))

    val img = element.selectFirst("img")

    anime.title = element.select("h2.title-name").text().ifEmpty {
        img?.attr("alt") ?: ""
    }

    anime.thumbnail_url = img?.attr("src").ifEmpty {
        img?.attr("data-src") ?: ""
    }

    return anime
}

override fun searchAnimeNextPageSelector(): String =
    "ul.pagination li a.page-link[rel=next]"

override fun searchAnimeRequest(
    page: Int,
    query: String,
    filters: AnimeFilterList,
): Request {
    val filterList = if (filters.isEmpty()) getFilterList() else filters
    val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
    val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
    val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

    return if (query.isNotBlank()) {
        GET("$baseUrl/titles/list?search=$query&page=$page", headers)
    } else {
        val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()

        if (sectionFilter.state != 0) {
            url.addPathSegment(sectionFilter.toUriPart())
        } else if (categoryFilter.state != 0) {
            url.addPathSegment(categoryFilter.toUriPart())
            url.addPathSegment(genreFilter.toUriPart().lowercase())
        } else {
            throw Exception("من فضلك اختر قسم أو نوع")
        }

        url.addPathSegment("page")
        url.addPathSegment("$page")

        GET(url.toString(), headers)
    }
}
    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
    val anime = SAnime.create()

    anime.title = document.select("h1, h2.title-name").first()?.text().orEmpty()

    val img = document.selectFirst("img")
    anime.thumbnail_url = img?.attr("src").ifEmpty {
        img?.attr("data-src") ?: ""
    }

    anime.description = document.select(
        "div.singleDesc, div.story, div.description"
    ).text()

    anime.genre = document.select(
        "a[href*=genre], a[href*=category]"
    ).joinToString(", ") { it.text() }

    val status = document.select(
        "span:contains(حالة), span:contains(Status)"
    ).text()

    anime.status = parseStatus(
        status.replace("حالة", "")
            .replace(":", "")
            .trim()
    )

    return anime
}
    // =============================== Latest ===============================
  override fun latestUpdatesSelector(): String =
    "div#postList div.col-xl-2 a"

override fun latestUpdatesRequest(page: Int): Request =
    GET("$baseUrl/titles/list?page=$page", headers)

override fun latestUpdatesFromElement(element: Element): SAnime {
    val anime = SAnime.create()

    anime.setUrlWithoutDomain(element.attr("href"))

    val img = element.selectFirst("img")
    anime.title = element.select("h2.title-name").text().ifEmpty {
        img?.attr("alt") ?: ""
    }

    anime.thumbnail_url = img?.attr("src").ifEmpty {
        img?.attr("data-src") ?: ""
    }

    return anime
}

override fun latestUpdatesNextPageSelector(): String =
    "ul.pagination li a.page-link[rel=next]"

    // ============================ Filters =============================
private class GenreFilter :
    SingleFilter(
        "التصنيف",
        arrayOf(
            "أكشن",
            "إيسيكاي",
            "أساطير",
            "استراتيجي",
            "إيتشي",
            "أنثروبولوجي",
            "بطولة راشدين",
            "بوليسي",
            "تاريخي",
            "تشويق",
            "حائز على جوائز",
            "حب متعدد الأطراف",
            "حريم",
            "خارق للطبيعة",
            "خيال",
            "خيال حضري",
            "خيال علمي",
            "دراما",
            "دموي",
            "رعب",
            "رومانسي",
            "رياضات جماعية",
            "رياضي",
            "ساموراي",
            "ساخر",
            "سفر عبر الزمن",
            "سينين",
            "شوجو",
            "شونين",
            "عمل",
            "فتاة ساحرة",
            "فضاء",
            "قتالي",
            "قوى خارقة",
            "كوميديا",
            "كوميديا حركية",
            "كيوت",
            "للأطفال",
            "مصاصي دماء",
            "مغامرة",
            "ميكا",
            "موسيقي",
            "نفسي",
            "غموض",
            "عسكري",
            "الحياة اليومية",
            "تناسخ وإعادة إحياء",
            "جوسي"
        ).sortedArray(),
    )
    // preferred quality settings
    private var SharedPreferences.customDomain by preferences.delegate(PREF_DOMAIN_CUSTOM_KEY, "")
    private var SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "الجودة المفضلة",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = "المجال المخصص",
            dialogMessage = "أدخل المجال المخصص (على سبيل المثال، https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }

    companion object {
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
