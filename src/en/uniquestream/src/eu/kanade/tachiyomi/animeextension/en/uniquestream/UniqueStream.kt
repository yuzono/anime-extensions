package eu.kanade.tachiyomi.animeextension.en.uniquestream

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.ceil

class UniqueStream : Source() {

    override val name = "UniqueStream"

    override val lang = "en"

    override val baseUrl = "https://anime.uniquestream.net"

    override val supportsLatest = true

    private val apiUrl get() = "$baseUrl/api/v1"

    private val preferredAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.get("$apiUrl/videos/popular?page=$page&limit=$PAGE_SIZE&type=all")
        val animeList = response.parseAs<List<BrowseItemDto>>().map { it.toSAnime() }
        return AnimesPage(animeList, animeList.size >= PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.get("$apiUrl/videos/new?page=$page&limit=$PAGE_SIZE&type=all")
        val animeList = response.parseAs<List<BrowseItemDto>>().map { it.toSAnime() }
        return AnimesPage(animeList, animeList.size >= PAGE_SIZE)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)

        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
            addQueryParameter("t", "all")
            addQueryParameter("limit", PAGE_SIZE.toString())
        }.build()

        val response = client.get(url)
        val result = response.parseAs<SearchResponseDto>()
        val animeList = (result.series.orEmpty() + result.movies.orEmpty()).map { it.toSAnime() }
        val totals = result.totals
        val hasNextPage = if (totals == null) {
            animeList.size >= PAGE_SIZE
        } else {
            val lastPage = maxOf(
                ceil((totals.series ?: 0) / PAGE_SIZE.toDouble()),
                ceil((totals.movies ?: 0) / PAGE_SIZE.toDouble()),
            ).toInt()
            animeList.isNotEmpty() && page < lastPage
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.get(apiUrl + anime.url)
        val details = response.parseAs<DetailsDto>()
        return SAnime.create().apply {
            title = details.title
            description = details.description
            genre = details.genre.orEmpty().joinToString { it.title }.ifBlank { null }
            author = details.studio
            thumbnail_url = details.images.orEmpty()
                .firstOrNull { it.type == "poster_tall" }?.url
                ?: details.images.orEmpty().firstOrNull()?.url
            status = when {
                details.seasons == null -> SAnime.COMPLETED // movies
                else -> SAnime.UNKNOWN // details API exposes no airing status
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.get(apiUrl + anime.url)
        val details = response.parseAs<DetailsDto>()

        if (details.seasons == null) {
            // Movies are streamed through the same media endpoint under /movie/.
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "movie/${details.contentId}|${details.audioLocales?.firstOrNull() ?: DEFAULT_AUDIO}"
                    scanlator = getScanlatorLabel(details.audioLocales)
                },
            )
        }

        val seasonPages = details.seasons.filter { it.episodeCount > 0 }
            .flatMap { season ->
                (1..ceil(season.episodeCount / PAGE_SIZE.toDouble()).toInt()).map { season to it }
            }

        return seasonPages.parallelCatchingFlatMap { (season, page) ->
            client.get("$apiUrl/season/${season.contentId}/episodes?page=$page&limit=$PAGE_SIZE")
                .parseAs<List<EpisodeDto>>()
                .map { it.toEpisode(season.displayNumber) }
        }.reversed()
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (mediaPath, _) = episode.url.split("|")
        return "$baseUrl/$mediaPath"
    }

    // =========================== Hosters & Videos ==========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        require(parts.size == 2) { "Outdated episode entry, refresh the entry" }
        val (mediaPath, locale) = parts
        val media = client.get("$apiUrl/$mediaPath/media/hls/$locale").parseAs<MediaResponse>()

        val hls = requireNotNull(media.hls) { "No HLS media available for this episode" }
        val mediaId = media.mediaId
        val hosters = mutableListOf<Hoster>()

        // Hard subs (e.g. English Hard Sub)
        hls.hardSubs.orEmpty().forEach { sub ->
            sub.playlist?.let { playlist ->
                hosters.add(
                    Hoster(
                        hosterName = hardSubLabel(sub.locale),
                        hosterUrl = playlist,
                        internalData = mediaId,
                    ),
                )
            }
        }

        // Original track (usually raw Japanese)
        hls.playlist?.let { playlist ->
            val label = hls.locale?.let { "${localeNames[it] ?: it} (Raw)" } ?: "Raw"
            hosters.add(
                Hoster(
                    hosterName = label,
                    hosterUrl = playlist,
                    internalData = mediaId,
                ),
            )
        }

        // Dub versions
        media.versions?.hls.orEmpty().forEach { version ->
            version.playlist?.let { playlist ->
                val langName = version.locale?.let { localeNames[it] ?: it } ?: "Dub"
                hosters.add(
                    Hoster(
                        hosterName = "$langName (Dub)",
                        hosterUrl = playlist,
                        internalData = mediaId,
                    ),
                )
            }
            version.hardSubs.orEmpty().forEach { sub ->
                sub.playlist?.let { playlist ->
                    val langName = version.locale?.let { localeNames[it] ?: it } ?: "Dub"
                    val subLang = sub.locale?.let { localeNames[it] ?: it } ?: "Hard Sub"
                    hosters.add(
                        Hoster(
                            hosterName = "$langName Dub ($subLang Sub)",
                            hosterUrl = playlist,
                            internalData = mediaId,
                        ),
                    )
                }
            }
        }

        require(hosters.isNotEmpty()) { "No hosters found for this episode" }
        return hosters.sortHosters()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val isDub = preferredAudio == "dub"
        return sortedWith(
            compareByDescending<Hoster> {
                if (isDub) {
                    it.hosterName.contains("Dub", ignoreCase = true)
                } else {
                    it.hosterName.contains("Sub", ignoreCase = true)
                }
            }.thenByDescending {
                it.hosterName.contains("English", ignoreCase = true)
            },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val masterUrl = hoster.hosterUrl
        val mediaId = hoster.internalData
        UniqueStreamHlsServer.setUp(client)

        val videos = runCatching {
            val master = client.get(masterUrl).bodyString()
            VARIANT_REGEX.findAll(master).mapNotNull { match ->
                val height = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val variantUrl = masterUrl.toHttpUrl().resolve(match.groupValues[2])?.toString()
                    ?: return@mapNotNull null
                Video(
                    videoUrl = UniqueStreamHlsServer.localPlaylistUrl(masterUrl, mediaId, height),
                    videoTitle = "${hoster.hosterName} - ${height}p",
                )
            }.toList()
        }.getOrNull().orEmpty()

        val result = if (videos.isNotEmpty()) {
            videos
        } else {
            listOf(
                Video(
                    videoUrl = UniqueStreamHlsServer.localPlaylistUrl(masterUrl, mediaId),
                    videoTitle = hoster.hosterName,
                ),
            )
        }

        val quality = preferredQuality
        return result.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("${quality}p") }
                .thenByDescending { it.videoTitle.substringAfterLast(" - ").removeSuffix("p").toIntOrNull() ?: 0 },
        )
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================

    @Serializable
    data class BrowseItemDto(
        @SerialName("content_id") val contentId: String,
        val title: String,
        val image: String? = null,
        val type: String,
        val subbed: Boolean? = null,
        val dubbed: Boolean? = null,
        val status: String? = null,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            val audio = listOfNotNull(
                if (subbed == true) "Subbed" else null,
                if (dubbed == true) "Dubbed" else null,
            ).joinToString()
            url = if (type == "movie") "/movie/$contentId" else "/series/$contentId"
            title = this@BrowseItemDto.title
            thumbnail_url = image
            genre = audio.ifBlank { null }
            status = when (this@BrowseItemDto.status) {
                "FINISHED", "RELEASED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    @Serializable
    data class SearchResponseDto(
        val series: List<BrowseItemDto>? = null,
        val movies: List<BrowseItemDto>? = null,
        val totals: TotalsDto? = null,
    ) {
        @Serializable
        data class TotalsDto(
            val series: Int? = null,
            val movies: Int? = null,
        )
    }

    @Serializable
    data class DetailsDto(
        @SerialName("content_id") val contentId: String,
        val title: String,
        val description: String? = null,
        val images: List<ImageDto>? = null,
        val studio: String? = null,
        val genre: List<GenreDto>? = null,
        val seasons: List<SeasonDto>? = null,
        @SerialName("audio_locales") val audioLocales: List<String>? = null,
    ) {
        @Serializable
        data class ImageDto(
            val url: String,
            val type: String? = null,
        )

        @Serializable
        data class GenreDto(
            val title: String,
        )
    }

    @Serializable
    data class SeasonDto(
        @SerialName("content_id") val contentId: String,
        val title: String? = null,
        @SerialName("display_number") val displayNumber: String? = null,
        @SerialName("episode_count") val episodeCount: Int,
    )

    @Serializable
    data class EpisodeDto(
        val title: String,
        val episode: String = "",
        @SerialName("episode_number") val episodeNumber: Double = 0.0,
        @SerialName("content_id") val contentId: String,
        @SerialName("audio_locales") val audioLocales: List<String>? = null,
    ) {
        fun toEpisode(seasonNumber: String?): SEpisode = SEpisode.create().apply {
            name = buildString {
                if (!seasonNumber.isNullOrBlank()) append("S$seasonNumber ")
                if (episode.isNotBlank()) append("E$episode - ")
                append(title)
            }
            episode_number = episodeNumber.toFloat()
            url = "episode/$contentId|${audioLocales?.firstOrNull() ?: DEFAULT_AUDIO}"
            scanlator = getScanlatorLabel(audioLocales)
        }
    }

    @Serializable
    data class MediaResponse(
        @SerialName("media_id") val mediaId: String,
        val hls: HlsDto? = null,
        // Episodes wrap dubs in {"hls": [...]}; movies omit the key entirely.
        val versions: VersionsDto? = null,
    ) {
        @Serializable
        data class VersionsDto(
            val hls: List<HlsDto>? = null,
        )

        @Serializable
        data class HlsDto(
            val locale: String? = null,
            val playlist: String? = null,
            @SerialName("hard_subs") val hardSubs: List<HlsDto>? = null,
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val DEFAULT_AUDIO = "ja-JP"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Dub")
        private val PREF_AUDIO_VALUES = listOf("sub", "dub")
        private const val PREF_AUDIO_DEFAULT = "sub"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        // Master playlists carry one line of metadata followed by the variant URL.
        private val VARIANT_REGEX = Regex("""RESOLUTION=\d+x(\d+)[^\n]*\n([^\n#]+\.m3u8[^\n]*)""")

        private val localeNames = mapOf(
            "ja-JP" to "Japanese",
            "en-US" to "English",
            "es-419" to "Spanish (LatAm)",
            "es-ES" to "Spanish (Spain)",
            "pt-BR" to "Portuguese",
            "fr-FR" to "French",
            "de-DE" to "German",
            "it-IT" to "Italian",
            "ar-SA" to "Arabic",
            "ru-RU" to "Russian",
            "hi-IN" to "Hindi",
            "ta-IN" to "Tamil",
            "pl-PL" to "Polish",
        )

        private fun hardSubLabel(locale: String?): String = when (locale) {
            "en-US" -> "English Hard Sub"
            null -> "Hard Sub"
            else -> "${localeNames[locale] ?: locale} Hard Sub"
        }

        private fun getScanlatorLabel(locales: List<String>?): String? {
            if (locales.isNullOrEmpty()) return null
            val hasSub = locales.contains("ja-JP")
            val hasDub = locales.any { it != "ja-JP" }
            return when {
                hasSub && hasDub -> "Sub, Dub"
                hasDub -> "Dub"
                hasSub -> "Sub"
                else -> null
            }
        }
    }
}
