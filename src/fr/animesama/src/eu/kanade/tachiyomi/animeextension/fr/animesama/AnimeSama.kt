package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.content.SharedPreferences
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.PreferenceScreen
import aniyomi.lib.embed4meextractor.Embed4MeExtractor
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

@Source
abstract class AnimeSama :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private var SharedPreferences.customDomain by preferences.delegate(PREF_URL_KEY, PREF_URL_DEFAULT)

    override var baseUrl by LazyMutable { preferences.customDomain.ifBlank { PREF_URL_DEFAULT }.sanitizeDomain() }

    override val client: OkHttpClient = super.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val maxRedirects = 10
            var request = chain.request()
            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect && redirectCount < maxRedirects) {
                val newUrl = response.header("Location") ?: break
                val newUrlHttp = request.url.resolve(newUrl) ?: break
                val redirectedDomain = newUrlHttp.run { "$scheme://$host" }
                // This client is shared with the video extractors, and hosts like uqload.is
                // permanently redirect to another domain. Only a redirect on the source's own
                // domain is a domain migration; anything else must not touch baseUrl/headers.
                val isSourceDomain = request.url.host == baseUrl.toHttpUrlOrNull()?.host
                if (isSourceDomain && redirectedDomain != baseUrl) {
                    updateDomain(redirectedDomain)
                }
                response.close()
                request = request.newBuilder()
                    .url(newUrlHttp)
                    .apply {
                        if (isSourceDomain) {
                            header("Origin", redirectedDomain)
                            header("Referer", "$redirectedDomain/")
                        }
                    }
                    .build()
                response = chain.proceed(request)
                redirectCount++
            }
            if (redirectCount >= maxRedirects) {
                response.close()
                throw java.io.IOException("Too many redirects: $maxRedirects")
            }
            response
        }.build()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val page = response.request.url.fragment?.toIntOrNull() ?: 0
        val chunks = doc.select("#containerPepites > div").chunked(5)
        val animes = chunks.getOrNull(page - 1)?.mapNotNull(::animeFromCard).orEmpty()
        return AnimesPage(animes, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
            .select("#containerAjoutsAnimes > div")
            .mapNotNull(::animeFromCard)
        return AnimesPage(animes, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    // =============================== Search ===============================
    override fun getFilterList() = Filters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host && url.host !in KNOWN_HOSTS) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        } else if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val animeUrl = if (id.startsWith("/")) "$baseUrl$id" else "$baseUrl/catalogue/$id/"
            val res = client.get(animeUrl)
            val parent = parentEntry(res.request.url.encodedPath, parseAnimePage(res))
            return AnimesPage(listOf(parent), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = Filters.getSearchFilters(filters)
        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.languages.forEach { url.addQueryParameter("langue[]", it) }
        params.statuses.forEach { url.addQueryParameter("current[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }
        if (params.yearMin.isNotEmpty()) url.addQueryParameter("annee_min", params.yearMin)
        if (params.yearMax.isNotEmpty()) url.addQueryParameter("annee_max", params.yearMax)
        if (params.sort.isNotEmpty()) url.addQueryParameter("tri", params.sort)
        url.addQueryParameter("search", query)
        url.addQueryParameter("page", "$page")
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val anime = document.select("#list_catalog > div").mapNotNull(::animeFromCard)
        val page = response.request.url.queryParameterValues("page").firstOrNull() ?: "1"
        val lastPage = document.select("#list_pagination a:last-child").text()
        val hasNextPage = lastPage.isNotEmpty() && lastPage != page
        return AnimesPage(anime, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val segments = anime.url.trim('/').split("/")
        return if (segments.size <= 2) {
            parentDetails(anime)
        } else {
            fetchSeasonEntries("$baseUrl/${segments.take(2).joinToString("/")}/", segments[2])
                .firstOrNull() ?: anime
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    private suspend fun parentDetails(anime: SAnime): SAnime {
        val res = client.get("$baseUrl/${anime.url.trim('/')}")
        return parentEntry(anime.url, parseAnimePage(res))
    }

    // ============================== Seasons ===============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val res = client.get("$baseUrl/${anime.url.trim('/')}")
        return seasonEntries(res, "")
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.removeSuffix("/")
        val slotIdx = url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val cleanUrl = url.substringBefore("#")

        val pages = fetchEpisodePages("$baseUrl$cleanUrl")
        val isFilm = cleanUrl.trim('/').split("/").drop(2).any { it.contains("film", true) }

        if (isFilm) {
            val movieCount = if (slotIdx != null) 1 else (pages.maxOfOrNull { page -> page.slots.maxOfOrNull { it.size } ?: 0 } ?: 0)
            val filmNames = pages.firstOrNull { it.filmNames.isNotEmpty() }?.filmNames.orEmpty()

            val episodes = List(movieCount) { idx ->
                val targetIdx = slotIdx ?: idx
                val voicesWithData = pages.filter { page ->
                    page.slots.getOrNull(targetIdx)?.isNotEmpty() == true
                }
                val voiceData = voicesWithData.map { page ->
                    val slot = page.slots.getOrNull(targetIdx).orEmpty()
                    EpisodeVoiceData(
                        voice = page.voice,
                        jeton = page.jeton,
                        path = page.path,
                        players = slot.map { token ->
                            val host = page.epsMeta[token.substringAfter("lien:")]
                            PlayerToken(
                                token = token,
                                family = host?.let { page.lecteurs[it] }.orEmpty(),
                            )
                        },
                    )
                }
                val movieName = filmNames.getOrNull(targetIdx) ?: "Film ${targetIdx + 1}"
                SEpisode.create().apply {
                    name = movieName
                    this.url = voiceData.toJsonString()
                    episode_number = (targetIdx + 1).toFloat()
                    scanlator = voicesWithData.joinToString { it.voice.uppercase() }
                }
            }
            if (slotIdx != null) {
                return listOfNotNull(episodes.firstOrNull())
            }
            return episodes
        }

        val episodeCount = pages.maxOfOrNull { page -> page.slots.maxOfOrNull { it.size } ?: 0 } ?: 0
        val episodes = List(episodeCount) { epIdx ->
            val voicesWithData = pages.filter { page ->
                page.slots.any { it.getOrNull(epIdx)?.isNotEmpty() == true }
            }
            val voiceData = voicesWithData.map { page ->
                EpisodeVoiceData(
                    voice = page.voice,
                    jeton = page.jeton,
                    path = page.path,
                    players = page.slots.map { slot ->
                        val token = slot.getOrNull(epIdx).orEmpty()
                        val host = page.epsMeta[token.substringAfter("lien:")]
                        PlayerToken(
                            token = token,
                            family = host?.let { page.lecteurs[it] }.orEmpty(),
                        )
                    },
                )
            }
            SEpisode.create().apply {
                name = "Episode ${epIdx + 1}"
                this.url = voiceData.toJsonString()
                episode_number = (epIdx + 1).toFloat()
                scanlator = voicesWithData.joinToString { it.voice.uppercase() }
            }
        }
        if (slotIdx != null) {
            return listOfNotNull(episodes.getOrNull(slotIdx))
        }
        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // Brief cache so one open (getEpisodeList then getHosterList) doesn't re-probe every
    // voice folder within seconds.
    private class PagesCache(val key: String, val pages: List<EpisodePage>, val expiresAtMillis: Long)

    @Volatile
    private var pagesCache: PagesCache? = null

    private suspend fun fetchEpisodePages(pageUrl: String): List<EpisodePage> {
        pagesCache?.let { cache ->
            if (cache.key == pageUrl && cache.expiresAtMillis > System.currentTimeMillis()) {
                return cache.pages
            }
        }

        val primaryVoice = pageUrl.trim('/').substringAfterLast('/').takeIf { it in VOICES_VALUES }.orEmpty()
        val primary = fetchEpisodePage(pageUrl, primaryVoice)
        val candidates = primary.languesDispo
            .ifEmpty { VOICES_VALUES.toList() }
            .filter { it.isNotEmpty() }
            .distinct()

        val pages = buildList {
            add(primary)
            for (voice in candidates) {
                if (voice.equals(primary.voice, ignoreCase = true)) continue
                try {
                    add(fetchEpisodePage("${primary.rootUrl}/$voice/", voice))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.message?.contains("404") != true) throw e
                    // Voice folder absent (404) — skip.
                }
            }
        }

        pagesCache = pagesCache?.takeIf { it.key == pageUrl && it.pages.size > pages.size }
            ?: PagesCache(pageUrl, pages, System.currentTimeMillis() + PAGES_CACHE_TTL_MS)
        return pagesCache!!.pages
    }

    private suspend fun fetchEpisodePage(url: String, voice: String): EpisodePage = parseEpisodePage(client.get(url), voice)

    private fun parseEpisodePage(response: Response, voiceHint: String): EpisodePage {
        val doc = response.useAsJsoup()
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val uncommented = commentRegex.replace(scriptData, "")

        val slots = epsArrayRegex.findAll(uncommented)
            .sortedBy { it.groupValues[1].toInt() }
            .map { it.groupValues[2].parseAs<List<String>>() }
            .toList()
        val jeton = epsJetonRegex.find(uncommented)?.groupValues?.get(1).orEmpty()
        val epsMeta = epsMetaRegex.find(uncommented)?.groupValues?.get(1)
            ?.parseAs<Map<String, List<JsonPrimitive>>>().orEmpty()
            .mapValues { (_, value) -> value.firstOrNull()?.takeIf { it.isString }?.content }
        val lecteurs = lecteursRegex.find(uncommented)?.groupValues?.get(1)
            ?.parseAs<Map<String, LecteurInfo>>().orEmpty()
            .mapValues { it.value.nom }

        val scriptLangues = languesRegex.find(uncommented)?.groupValues?.get(1)
            ?.parseAs<List<String>>().orEmpty()
        val htmlLangues = doc.select(".ct-langues a").mapNotNull { a ->
            a.attr("href").trim('/').substringAfterLast('/').takeIf { it.isNotEmpty() && it != ".." }
                ?: a.attr("id").removePrefix("switch").lowercase().takeIf { it.isNotEmpty() }
        }
        val languesDispo = (scriptLangues + htmlLangues).distinct()

        val url = response.request.url
        val cleanSegments = url.encodedPathSegments.filter { it.isNotEmpty() }
        val lastSegment = cleanSegments.lastOrNull()?.lowercase().orEmpty()

        val voice = voiceHint.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst(".ct-langue-active")?.attr("id")
                ?.removePrefix("switch")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst(".ct-langue-active")?.attr("href")?.trim('/')
                ?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != ".." }
            ?: lastSegment.takeIf { it in VOICES_VALUES || it in languesDispo }
            ?: ""

        val path = url.encodedPath.removeSuffix("/")
        val rootSegments = if (voice.isNotEmpty() && cleanSegments.lastOrNull()?.equals(voice, ignoreCase = true) == true) {
            cleanSegments.dropLast(1)
        } else {
            cleanSegments
        }
        val rootUrl = url.newBuilder()
            .encodedPath("/" + rootSegments.joinToString("/"))
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .removeSuffix("/")

        val filmNames = filmNameRegex.findAll(uncommented).map { it.destructured.component2() }.toList()

        return EpisodePage(voice, path, rootUrl, languesDispo, jeton, slots, filmNames, epsMeta, lecteurs)
    }

    // ============================= Hosters ================================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = when {
        episode.url.startsWith("[{") -> hosterListFromVoiceData(episode.url)
        // Legacy entries store the player urls directly as a JSON matrix in episode.url.
        episode.url.startsWith("[[") -> hosterListFromPlayerUrls(episode)
        // Interim entries point at the content page with the episode index in the fragment.
        else -> hosterListFromFragment(episode)
    }.sortHosters()

    private fun hosterListFromVoiceData(url: String): List<Hoster> = url.parseAs<List<EpisodeVoiceData>>()
        .flatMap { voice ->
            voice.players.mapNotNull { player ->
                if (player.token.isEmpty()) return@mapNotNull null
                val label = player.family.ifEmpty { playerLabel(player.token) }
                Hoster(
                    hosterName = if (voice.voice.isBlank()) label else "(${voice.voice.uppercase()}) $label",
                    internalData = "${voice.voice}|||${voice.jeton}|||${player.token}|||${player.family}|||${voice.path}",
                )
            }
        }
        .distinctBy { it.internalData }

    private suspend fun hosterListFromFragment(episode: SEpisode): List<Hoster> {
        val cleanUrl = episode.url.substringBefore("#")
        val epIndex = episode.url.substringAfter("#", "").toIntOrNull() ?: 0

        val pages = fetchEpisodePages("$baseUrl$cleanUrl")
        return pages.flatMap { page ->
            page.slots.mapNotNull { slot ->
                val token = slot.getOrNull(epIndex)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val host = page.epsMeta[token.substringAfter("lien:")]
                // The site's own player family name (lecteursInfos, e.g. "VOE")
                val family = host?.let { page.lecteurs[it] }.orEmpty()
                val label = family.ifEmpty { host ?: playerLabel(token) }
                Hoster(
                    hosterName = if (page.voice.isBlank()) label else "(${page.voice.uppercase()}) $label",
                    internalData = "${page.voice}|||${page.jeton}|||$token|||$family|||${page.path}",
                )
            }
        }.distinctBy { it.internalData }
    }

    private fun hosterListFromPlayerUrls(episode: SEpisode): List<Hoster> {
        val playerUrls = episode.url.parseAs<List<List<String>>>()
        val voiceNames = episode.scanlator?.split(", ") ?: emptyList()

        return playerUrls
            .filter { it.isNotEmpty() }
            .flatMapIndexed { i, players ->
                val voice = voiceNames.getOrElse(i) { "" }
                players.filter { it.isNotEmpty() }.map { it to voice }
            }
            .distinctBy { it.first }
            .map { (playerUrl, voice) ->
                val label = playerLabel(playerUrl)
                Hoster(
                    hosterName = if (voice.isBlank()) label else "($voice) $label",
                    internalData = "$voice|||$playerUrl",
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(voices, true) }
                .thenByDescending { player.isNotEmpty() && it.hosterName.contains(player, true) },
        )
    }

    // ============================ Video Links =============================
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val embed4MeExtractor by lazy { Embed4MeExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val universalMutex = Mutex()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.split("|||")
        val voice = parts.getOrElse(0) { "" }
        val playerUrl = if (parts.size >= 3 && parts[2].startsWith("lien:")) {
            resolveLienUrl(parts[1], parts[2]) ?: run {
                val path = parts.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: return@run null
                try {
                    val page = fetchEpisodePage("$baseUrl$path", voice)
                    resolveLienUrl(page.jeton, parts[2])
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        } else {
            parts.getOrNull(1)
        } ?: return emptyList()
        val family = parts.getOrNull(3).orEmpty()
        val prefix = if (voice.isBlank()) "" else "(${voice.uppercase()}) "

        val videos = videosFromKnownPlayer(playerUrl, prefix, family).ifEmpty {
            universalMutex.withLock {
                try {
                    universalExtractor.videosFromUrl(playerUrl, headers, prefix = prefix.trim())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }
        }
        val distinct = videos.distinctBy { it.videoTitle to it.videoUrl.substringBefore("?") }
        if (distinct.isEmpty()) return emptyList()

        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        val isPreferredVoice = hoster.hosterName.contains(voices, true)
        val isPreferredPlayer = hoster.hosterName.contains(player, true)
        val isPreferredHoster = isPreferredVoice && (player.isEmpty() || isPreferredPlayer)

        if (distinct.size <= 1) {
            return distinct.map { it.copy(preferred = isPreferredHoster) }
        }

        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val qualityList = listOf("360", "480", "720", "1080")

        val sorted = distinct.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality, true) }
                .thenByDescending { video -> qualityList.indexOfLast { video.videoTitle.contains(it) } },
        )

        val preferredIndex = if (isPreferredHoster) {
            sorted.indexOfFirst { it.videoTitle.contains(quality, true) }.takeIf { it != -1 } ?: 0
        } else {
            -1
        }

        return sorted.mapIndexed { index, video ->
            video.copy(preferred = index == preferredIndex)
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = when {
        episode.url.startsWith("[{") -> episode.url.parseAs<List<EpisodeVoiceData>>()
            .firstOrNull()?.path?.let { "$baseUrl$it" } ?: baseUrl
        episode.url.startsWith("[[") -> episode.preview_url?.let { if (it.startsWith("http")) it else "$baseUrl$it" } ?: baseUrl
        // Fragment format points at the content page directly.
        !episode.url.startsWith("[") -> "$baseUrl${episode.url.substringBefore("#").removeSuffix("/")}"
        else -> baseUrl
    }

    private suspend fun resolveLienUrl(jeton: String, token: String): String? = try {
        client.get("$baseUrl/api/lien.php?j=$jeton&l=${token.substringAfter("lien:")}")
            .parseAs<LienResponse>()
            .u
            .takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    private suspend fun videosFromKnownPlayer(playerUrl: String, prefix: String, family: String): List<Video> {
        val isEarnvids = family.equals("Earnvids", true) || family.equals("VidHide", true) ||
            VIDHIDE_DOMAINS.any { playerUrl.contains(it, ignoreCase = true) }
        val vids = try {
            with(playerUrl) {
                when {
                    family.equals("Sibnet", true) || contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(playerUrl, prefix)

                    family.equals("VK", true) || contains("vk.") || contains("vkvideo") -> vkExtractor.videosFromUrl(playerUrl, prefix)

                    family.equals("Sendvid", true) || contains("sendvid.com") -> sendvidExtractor.videosFromUrl(playerUrl, prefix)

                    family.equals("Vidmoly", true) || contains("vidmoly") || contains("ansembed") -> vidmolyExtractor.videosFromUrl(playerUrl, prefix.trim())

                    family.equals("VOE", true) || contains("voe") -> voeExtractor.videosFromUrl(playerUrl, prefix.trim())

                    isEarnvids -> {
                        val embedHeaders = headers.newBuilder()
                            .set("Referer", "$baseUrl/")
                            .set("Origin", baseUrl)
                            .build()
                        VidHideExtractor(client, embedHeaders).videosFromUrl(
                            playerUrl,
                            videoNameGen = { quality -> "${prefix.trim()} Earnvids - $quality" },
                        )
                    }

                    family.equals("Uqload", true) || contains("uqload") -> uqloadExtractor.videosFromUrl(playerUrl, prefix)

                    family.equals("YourUpload", true) || contains("yourupload") -> yourUploadExtractor.videoFromUrl(playerUrl, headers, prefix = prefix)

                    family.equals("Embed4me", true) || contains("embed4me") -> embed4MeExtractor.videosFromUrl(playerUrl, prefix)

                    contains(".mp4") -> listOf(Video(videoUrl = playerUrl, videoTitle = "${prefix}Direct", headers = headers))

                    else -> emptyList()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        return if (isEarnvids) {
            vids.distinctBy { it.videoTitle }
        } else {
            vids
        }
    }

    private fun playerLabel(playerUrl: String): String = when {
        playerUrl.contains("sibnet") -> "Sibnet"
        playerUrl.contains("vidmoly") || playerUrl.contains("ansembed") -> "VidMoly"
        playerUrl.contains("voe") -> "VOE"
        playerUrl.contains("vk.") || playerUrl.contains("vkvideo") -> "VK"
        playerUrl.contains("sendvid") -> "Sendvid"
        VIDHIDE_DOMAINS.any { playerUrl.contains(it, ignoreCase = true) } -> "EarnVids"
        playerUrl.contains("uqload") -> "Uqload"
        playerUrl.contains("yourupload") -> "YourUpload"
        playerUrl.contains("embed4me") -> "Embed4Me"
        playerUrl.contains(".mp4") -> "Direct"
        else -> playerUrl.toHttpUrlOrNull()?.host ?: "Auto"
    }

    // ============================ Utils =============================
    private fun animeFromCard(card: Element): SAnime? {
        val url = card.attr("data-url").ifEmpty {
            card.selectFirst("a")?.attr("abs:href").orEmpty()
        }
        if (url.isEmpty()) return null

        val badges = card.select(".as-badge")
        if (badges.isNotEmpty()) {
            val hasValidBadge = badges.any {
                it.hasClass("as-badge-anime") || it.hasClass("as-badge-film") || it.hasClass("as-badge-autres") ||
                    it.text().equals("Anime", true) || it.text().equals("Film", true) || it.text().equals("Autres", true)
            }
            if (!hasValidBadge) return null
        }

        return SAnime.create().apply {
            title = card.attr("data-nom").trim().ifEmpty {
                card.selectFirst(".as-carte-titre")?.text().orEmpty()
            }
            thumbnail_url = card.attr("data-img").ifEmpty {
                card.selectFirst("img")?.absUrl("src")
            }
            setUrlWithoutDomain(url)
            fetch_type = FetchType.Seasons
        }
    }

    private fun parentEntry(url: String, page: AnimePageData): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(url)
        title = page.name
        thumbnail_url = page.thumbnailUrl
        description = page.description
        genre = page.genres
        status = page.status
        fetch_type = FetchType.Seasons
        initialized = true
    }

    private fun parseAnimePage(response: Response): AnimePageData {
        val animeDoc = response.useAsJsoup()
        val animeName = animeDoc.selectFirst("h1")?.text()
            ?: throw Exception("Page introuvable ou indisponible")

        val statusText = animeDoc.selectFirst(".oe-meta")?.text() ?: ""

        val animeStatus = when {
            statusText.contains("En cours", true) -> SAnime.ONGOING
            statusText.contains("Terminé", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val thumbnailUrl = animeDoc.selectFirst(".oe-affiche img")?.absUrl("src")
            ?: animeDoc.selectFirst("#imgOeuvre")?.absUrl("src")
            ?: animeDoc.selectFirst("meta[property=og:image]")?.attr("abs:content")

        val scripts = animeDoc.select("script").joinToString("\n") { it.data() }
        val uncommented = commentRegex.replace(scripts, "")
        val seasons = seasonRegex.findAll(uncommented)
            .map { Pair(it.destructured.component2(), it.destructured.component4()) }
            .toList()

        val descriptionText = animeDoc.selectFirst("#synopsisText")?.text() ?: ""
        val genresText = animeDoc.select(".genre-pill").joinToString { g -> g.text() }

        return AnimePageData(animeName, thumbnailUrl, animeStatus, descriptionText, genresText, seasons)
    }

    private suspend fun fetchSeasonEntries(animeUrl: String, seasonFilter: String): List<SAnime> {
        val res = client.get(animeUrl)
        return seasonEntries(res, seasonFilter)
    }

    private val commentRegex by lazy { Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL) }
    private val seasonRegex by lazy { Regex("""panneauAnime\((["'])(.*?)\1,\s*(["'])(.*?)\3\)""") }
    private val filmNameRegex by lazy { Regex("""newSPF\((["'])(.*?)\1\)""") }

    // Inline episode script: var eps1 = ["lien:<id>", ...]; var epsMeta = {...};
    // var epsJeton = "..."; var lecteursInfos = {...}; var languesDispo = [...];
    private val epsArrayRegex by lazy { Regex("""var\s+eps(\d+)\s*=\s*(\[[^\]]*\])""") }
    private val epsJetonRegex by lazy { Regex("""var\s+epsJeton\s*=\s*"([^"]+)"""") }
    private val epsMetaRegex by lazy { Regex("""var\s+epsMeta\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL) }
    private val lecteursRegex by lazy { Regex("""var\s+lecteursInfos\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL) }
    private val languesRegex by lazy { Regex("""var\s+languesDispo\s*=\s*(\[[^\]]*\])""") }

    private suspend fun seasonEntries(response: Response, seasonFilter: String): List<SAnime> {
        val page = parseAnimePage(response)
        val pageUrl = response.request.url.toString().removeSuffix("/")

        val entries = mutableListOf<Triple<String, String, Double>>()
        page.seasons.forEachIndexed { index, (seasonName, seasonStem) ->
            val stemSeason = seasonStem.substringBefore("/")
            val baseSeasonNum = (index + 1).toDouble()

            if (stemSeason.contains("film", true)) {
                try {
                    val filmRes = client.get("$pageUrl/$seasonStem")
                    val episodePage = parseEpisodePage(filmRes, "")
                    val filmNames = episodePage.filmNames
                    val movieCount = episodePage.slots.maxOfOrNull { it.size } ?: 0

                    for (slotIdx in 0 until movieCount) {
                        val movieNum = baseSeasonNum + slotIdx * 0.1
                        if (seasonFilter.isNotEmpty() && stemSeason != seasonFilter) {
                            continue
                        }
                        val movieName = filmNames.getOrNull(slotIdx) ?: "${page.name} Film ${slotIdx + 1}"
                        entries.add(Triple(movieName, "$pageUrl/$seasonStem#$slotIdx", movieNum))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (seasonFilter.isEmpty() || stemSeason == seasonFilter) {
                        entries.add(Triple("${page.name} Film", "$pageUrl/$seasonStem", baseSeasonNum))
                    }
                }
            } else {
                if (seasonFilter.isNotEmpty() && stemSeason != seasonFilter) {
                    return@forEachIndexed
                }
                val displaySeason = if (stemSeason.startsWith("saison")) {
                    "Saison " + stemSeason.substringAfter("saison").substringBefore("/")
                } else {
                    seasonName.substringBefore(" (")
                }
                entries.add(Triple("${page.name} $displaySeason", "$pageUrl/$seasonStem", baseSeasonNum))
            }
        }

        return entries.map { (title, url, seasonNum) ->
            SAnime.create().apply {
                this.title = title
                thumbnail_url = page.thumbnailUrl
                description = page.description
                genre = page.genres
                status = page.status
                setUrlWithoutDomain(url.removeSuffix("/"))
                season_number = seasonNum
                fetch_type = FetchType.Episodes
                initialized = true
            }
        }
    }

    private fun String.sanitizeDomain() = trim().removeSuffix("/").ifBlank { PREF_URL_DEFAULT }

    private fun updateDomain(domain: String) {
        val newDomain = domain.sanitizeDomain()
        if (URLUtil.isValidUrl(newDomain)) {
            preferences.customDomain = newDomain
            baseUrl = newDomain
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_URL_KEY,
            title = PREF_URL_TITLE,
            default = PREF_URL_DEFAULT,
            summary = PREF_URL_SUMMARY,
            onChange = { _, newValue ->
                val newDomain = newValue.trim().removeSuffix("/")
                if (URLUtil.isValidUrl(newDomain)) {
                    updateDomain(newDomain)
                    true
                } else {
                    Toast.makeText(screen.context, "URL invalide. Exemple: $PREF_URL_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            },
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = arrayOf("1080p", "720p", "480p", "360p").toList(),
            entryValues = arrayOf("1080", "720", "480", "360").toList(),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_VOICES_KEY,
            title = "Préférence des voix",
            entries = VOICES.toList(),
            entryValues = VOICES_VALUES.toList(),
            default = PREF_VOICES_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_PLAYER_KEY,
            title = "Lecteur par défaut",
            entries = PLAYERS.toList(),
            entryValues = PLAYERS_VALUES.toList(),
            default = PREF_PLAYER_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PAGES_CACHE_TTL_MS = 2 * 60 * 1000L

        private const val PREF_URL_KEY = "base_url_pref"
        private const val PREF_URL_TITLE = "URL de base"

        // Domain info at: https://anime-sama.pw
        private const val PREF_URL_DEFAULT = "https://anime-sama.to"
        private const val PREF_URL_SUMMARY = "Pour changer le domaine de l'extension. Voir https://anime-sama.pw"

        // Links on the default and older domains still open once baseUrl has followed a redirect to a newer one.
        // Keep the old domains in sync with their deeplink in build.gradle.kts.
        private val KNOWN_HOSTS = setOf(PREF_URL_DEFAULT.toHttpUrl().host, "anime-sama.si", "anime-sama.tv")

        private val voicesMap = mapOf(
            "Préférer VOSTFR" to "vostfr",
            "Préférer VF" to "vf",
            "Préférer VF1" to "vf1",
            "Préférer VF2" to "vf2",
            "Préférer VA" to "va",
            "Préférer VASTFR" to "vastfr",
            "Préférer VAR" to "var",
            "Préférer VCN" to "vcn",
            "Préférer VJ" to "vj",
            "Préférer VKR" to "vkr",
            "Préférer VQC" to "vqc",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Sendvid" to "sendvid",
            "Sibnet" to "sibnet",
            "VK" to "vk",
            "VidMoly" to "vidmoly",
            "Earnvids" to "earnvids",
            "VidHide" to "vidhide",
            "VOE" to "voe",
            "Uqload" to "uqload",
            "YourUpload" to "yourupload",
            "Myvi" to "myvi",
            "Oneupload" to "oneupload",
            "Direct" to "direct",
            "Embed4Me" to "embed4me",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()

        // Earnvids/VidHide-family hosts
        private val VIDHIDE_DOMAINS = listOf("smoothpre", "movearnpre", "minochinos", "morencius", "dingtezuni", "earnvids", "callistanise")

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_PLAYER_KEY = "player_preference"
        private const val PREF_PLAYER_DEFAULT = "vidmoly"
    }
}
