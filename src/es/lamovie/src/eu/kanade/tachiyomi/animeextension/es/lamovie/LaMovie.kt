package eu.kanade.tachiyomi.animeextension.es.lamovie

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.goodstramextractor.GoodStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.es.lamovie.extractors.LaMovieEmbedExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.utils.parallelFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LaMovie :
    DopeFlix(
        "LaMovie",
        "es",
        BuildConfig.MEGACLOUD_API,
        listOf(
            "lamovie.la",
        ),
    ) {
    override val id: Long = 5419283741928374105

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val lamovieEmbedExtractor by lazy { LaMovieEmbedExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = itemsUrlBuilder(page)
            .addQueryParameter("kind", preferredListingType())
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = response.parseListing()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = itemsUrlBuilder(page)
            .addQueryParameter("kind", preferredListingType(BASE_PREF_LATEST_KEY))
            .addQueryParameter("sort", "recent")
            .build()
        return GET(url, headers)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = client.get(latestUpdatesRequest(page).url, headers)
        .use { latestUpdatesParse(it) }

    override fun latestUpdatesParse(response: Response): AnimesPage = response.parseListing()

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = LaMovieFilters.createFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = LaMovieFilters.getSearchParameters(filters)
        val trimmedQuery = query.trim()

        val builder = itemsUrlBuilder(page)

        val kind = params.type.ifEmpty { if (trimmedQuery.isEmpty()) preferredListingType() else null }
        kind?.let { builder.addQueryParameter("kind", it) }

        if (trimmedQuery.isNotEmpty()) {
            builder.addQueryParameter("q", trimmedQuery)
        } else {
            builder.addQueryParameter("sort", params.sort)
        }

        if (params.genre.isNotEmpty()) builder.addQueryParameter("genre", params.genre)
        params.year.toIntOrNull()?.let { builder.addQueryParameter("year", it.toString()) }

        return GET(builder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = response.parseListing()

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val (kind, id) = parseKindAndId(anime.url)
        return GET(apiUrlBuilder("items", kind, id).build(), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<ItemResponseDto>().item.toSAnime()

    override fun getAnimeUrl(anime: SAnime): String {
        val (kind, id) = parseKindAndId(anime.url)
        return "$baseUrl/${webPath(kind)}/$id"
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (kind, id) = parseKindAndId(anime.url)

        if (kind == KIND_MOVIE) {
            return listOf(
                SEpisode.create().apply {
                    url = "/$kind/$id"
                    name = "Película"
                    episode_number = 1F
                },
            )
        }

        val seasons = client.get(apiUrlBuilder("items", kind, id, "seasons").build(), headers)
            .parseAs<SeasonsDto>()
            .seasons

        return seasons
            .parallelFlatMap { season ->
                client.get(apiUrlBuilder("items", kind, id, "seasons", season.season.toString()).build(), headers)
                    .parseAs<SeasonResponseDto>()
                    .season
                    .episodes
                    .filter { it.playable }
            }
            .map { it.toSEpisode(kind, id) }
            .sortedByDescending { it.episode_number }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val url = "$baseUrl${episode.url}".toHttpUrl()
        val (kind, id) = parseKindAndId(episode.url)
        val season = url.queryParameter("season")
        val number = url.queryParameter("episode")

        return if (season != null && number != null) {
            "$baseUrl/${webPath(kind)}/$id/temporada/$season/episodio/$number"
        } else {
            "$baseUrl/${webPath(kind)}/$id"
        }
    }

    // ============================ Video Links =============================
    override fun hosterListRequest(episode: SEpisode): Request {
        val url = "$baseUrl${episode.url}".toHttpUrl()
        val (kind, id) = parseKindAndId(episode.url)
        val builder = apiUrlBuilder("playback", kind, id)

        url.queryParameter("season")?.let { builder.addQueryParameter("season", it) }
        url.queryParameter("episode")?.let { builder.addQueryParameter("episode", it) }

        return GET(builder.build(), headers)
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val embeds = client.get(hosterListRequest(episode).url, headers, ensureSuccess = false).use { response ->
            when {
                response.code == 404 -> emptyList<EmbedItem>()
                !response.isSuccessful -> throw Exception("HTTP ${response.code}")
                else -> response.parseAs<PlaybackDto>().embeds
            }
        }
        if (embeds.isEmpty()) return emptyList()

        return embeds.mapNotNull { embed ->
            if (embed.serverKey() == SERVER_KEY_UNKNOWN) return@mapNotNull null
            val url = embed.url.toHttpUrlOrNull() ?: return@mapNotNull null
            Hoster(
                hosterUrl = embed.url,
                hosterName = listOfNotNull(embed.language, embed.server.ifBlank { url.host }, embed.quality)
                    .joinToString(" - "),
                internalData = embed.toJsonString(),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = resolveEmbedVideos(hoster.internalData.parseAs<EmbedItem>()).sortVideos()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
            ?.let(::normalizeLanguagePreference) ?: PREF_LANGUAGE_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return map { it to it.internalData.parseAs<EmbedItem>() }
            .sortedWith(
                compareByDescending<Pair<Hoster, EmbedItem>> { (_, embed) -> embed.matchesLanguage(language) }
                    .thenByDescending { (_, embed) -> embed.matchesServer(server) },
            )
            .map { it.first }
    }

    private suspend fun resolveEmbedVideos(embed: EmbedItem): List<Video> {
        val prefix = buildString {
            embed.language?.takeIf(String::isNotBlank)?.let { append("${it.uppercase(Locale.US)} | ") }
            embed.quality?.takeIf(String::isNotBlank)?.let { append(" - $it") }
        }

        return when (embed.serverKey()) {
            SERVER_KEY_DOOD -> doodExtractor.videosFromUrl(embed.url, "$prefix - Doodstream")
            SERVER_KEY_VOE -> voeExtractor.videosFromUrl(embed.url, "$prefix - Voe")
            SERVER_KEY_MP4UPLOAD -> mp4uploadExtractor.videosFromUrl(embed.url, headers, "$prefix - Mp4upload")
            SERVER_KEY_STREAMHIDE -> vidHideExtractor.videosFromUrl(embed.url) { quality -> "StreamHide - $quality - $prefix" }
            SERVER_KEY_STREAMWISH -> streamWishExtractor.videosFromUrl(embed.url, prefix)
            SERVER_KEY_YOURUPLOAD -> yourUploadExtractor.videoFromUrl(embed.url, headers, "$prefix - YourUpload")
            SERVER_KEY_FILEMOON -> filemoonExtractor.videosFromUrl(embed.url, "$prefix - Filemoon")
            SERVER_KEY_GOODSTREAM -> goodStreamExtractor.videosFromUrl(embed.url, "$prefix - GoodStream")
            SERVER_KEY_LAMOVIE -> lamovieEmbedExtractor.videosFromUrl(embed.url, "$prefix - HLS")
            else -> emptyList()
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val preferredQualityLower = preferredQuality.lowercase(Locale.US)
        val preferredQualityValue = QUALITY_REGEX.find(preferredQualityLower)?.groupValues?.get(1)?.toIntOrNull()

        fun Video.matchesPreferredQuality(): Boolean {
            val normalized = videoTitle.lowercase(Locale.US)
            if (normalized.contains(preferredQualityLower)) return true

            val numericQuality = QUALITY_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (preferredQualityValue != null && numericQuality != null && numericQuality == preferredQualityValue) return true

            val aliases = QUALITY_KEYWORDS[preferredQualityValue]
            return !aliases.isNullOrEmpty() && aliases.any { normalized.contains(it) }
        }

        fun Video.extractQualityValue(): Int {
            val normalized = videoTitle.lowercase(Locale.US)
            val numericQuality = QUALITY_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (numericQuality != null) return numericQuality

            return when {
                normalized.contains("4k") || normalized.contains("uhd") -> 2160
                normalized.contains("2k") || normalized.contains("qhd") -> 1440
                normalized.contains("full hd") || normalized.contains("fhd") -> 1080
                normalized.contains("hd") -> 720
                normalized.contains("sd") -> 480
                normalized.contains("cam") -> 144
                else -> 0
            }
        }

        val qualitySorted = this.sortedWith(
            compareByDescending<Video> { if (it.matchesPreferredQuality()) 1 else 0 }
                .thenByDescending { it.extractQualityValue() },
        )

        val preferredLanguage = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
            ?.let(::normalizeLanguagePreference)
            ?: PREF_LANGUAGE_DEFAULT
        val languageSorted = if (preferredLanguage == PREF_LANGUAGE_DEFAULT) {
            qualitySorted
        } else {
            qualitySorted.sortedByDescending { it.matchesLanguage(preferredLanguage) }
        }

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        if (preferredServer == PREF_SERVER_DEFAULT) return languageSorted

        return languageSorted.sortedByDescending { it.matchesServer(preferredServer) }
    }

    private fun EmbedItem.matchesServer(preferredKey: String): Boolean {
        if (preferredKey == PREF_SERVER_DEFAULT) return false
        return serverKey() == preferredKey
    }

    private fun EmbedItem.serverKey(): String = detectServer(server, url)

    private fun EmbedItem.matchesLanguage(preferredKey: String): Boolean {
        if (preferredKey == PREF_LANGUAGE_DEFAULT) return false
        return languageCode() == preferredKey
    }

    private fun EmbedItem.languageCode(): String = detectLanguage(language, server, url)

    private fun Video.matchesServer(preferredKey: String): Boolean {
        if (preferredKey == PREF_SERVER_DEFAULT) return false
        return serverKey() == preferredKey
    }

    private fun Video.serverKey(): String = detectServer(videoTitle, videoUrl)

    private fun Video.matchesLanguage(preferredKey: String): Boolean {
        if (preferredKey == PREF_LANGUAGE_DEFAULT) return false
        return languageCode() == preferredKey
    }

    private fun Video.languageCode(): String = detectLanguage(videoTitle, videoUrl)

    private fun detectServer(vararg texts: String?): String {
        val combined = texts
            .asSequence()
            .filterNotNull()
            .joinToString(" ") { it.lowercase(Locale.US) }

        SERVER_KEYWORDS.forEach { (key, keywords) ->
            if (keywords.any { it in combined }) return key
        }

        return SERVER_KEY_UNKNOWN
    }

    private fun detectLanguage(vararg texts: String?): String {
        val fingerprint = texts
            .asSequence()
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.US)

        return when {
            LANGUAGE_LATINO_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_LATINO
            LANGUAGE_CASTELLANO_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_CASTELLANO
            LANGUAGE_SUB_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_SUB
            LANGUAGE_ENGLISH_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_ENGLISH
            else -> LANGUAGE_CODE_UNKNOWN
        }
    }

    // ============================== Utilities =============================
    private fun apiUrlBuilder(vararg segments: String): HttpUrl.Builder {
        val host = baseUrl.toHttpUrl().host.removePrefix("www.")
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("$API_SUBDOMAIN.$host")
            .addPathSegment("v1")
        segments.forEach(builder::addPathSegment)
        return builder
    }

    private fun itemsUrlBuilder(page: Int): HttpUrl.Builder = apiUrlBuilder("items")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", POSTS_PER_PAGE.toString())

    private fun Response.parseListing(): AnimesPage {
        val data = parseAs<ItemsDto>()
        return AnimesPage(data.items.map { it.toSAnime() }, data.pagination?.hasNext ?: false)
    }

    private fun parseKindAndId(url: String): Pair<String, String> {
        val segments = "$baseUrl$url".toHttpUrl().pathSegments
        val kind = segments.getOrNull(0)
        val id = segments.getOrNull(1)
        if (kind == null || kind !in KINDS || id.isNullOrEmpty() || id.toLongOrNull() == null) {
            throw Exception("URL obsoleta, migra esta entrada desde el catálogo de LaMovie")
        }
        return kind to id
    }

    private fun webPath(kind: String): String = when (kind) {
        KIND_MOVIE -> "pelicula"
        KIND_ANIME -> "anime"
        else -> "serie"
    }

    private fun EpisodeDto.toSEpisode(kind: String, id: String): SEpisode = SEpisode.create().apply {
        url = "/$kind/$id?season=$season&episode=$episode"
        name = this@toSEpisode.name
        episode_number = "$season.${episode.toString().padStart(3, '0')}".toFloatOrNull() ?: episode.toFloat()
        date_upload = dateFormat.tryParse(airDate)
    }

    companion object {
        private const val API_SUBDOMAIN = "tmdb"
        private const val POSTS_PER_PAGE = 18

        const val KIND_MOVIE = "movie"
        private const val KIND_TVSHOW = "tvshow"
        private const val KIND_ANIME = "anime"
        private val KINDS = setOf(KIND_MOVIE, KIND_TVSHOW, KIND_ANIME)
        private const val DEFAULT_LISTING_TYPE = KIND_MOVIE

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private const val PREF_POPULAR_KEY = "preferred_popular_page_new"
        private const val PREF_POPULAR_DEFAULT = KIND_MOVIE
        private val CONTENT_ENTRIES = arrayOf("Películas", "Series", "Anime")
        private val CONTENT_VALUES = arrayOf(KIND_MOVIE, KIND_TVSHOW, KIND_ANIME)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val BASE_PREF_LATEST_KEY = "preferred_latest_page"
        private const val PREF_LANGUAGE_KEY = "preferred_subLang"

        private const val LANGUAGE_CODE_ANY = "any"
        private const val LANGUAGE_CODE_UNKNOWN = "unknown"
        private const val LANGUAGE_CODE_LATINO = "latino"
        private const val LANGUAGE_CODE_CASTELLANO = "castellano"
        private const val LANGUAGE_CODE_SUB = "sub"
        private const val LANGUAGE_CODE_ENGLISH = "english"

        private const val PREF_LANGUAGE_DEFAULT = LANGUAGE_CODE_ANY
        private val PREF_LANGUAGE_ENTRIES = arrayOf(
            "Sin preferencia",
            "Latino",
            "Castellano",
            "Subtitulado",
            "Inglés",
        )
        private val PREF_LANGUAGE_VALUES = arrayOf(
            LANGUAGE_CODE_ANY,
            LANGUAGE_CODE_LATINO,
            LANGUAGE_CODE_CASTELLANO,
            LANGUAGE_CODE_SUB,
            LANGUAGE_CODE_ENGLISH,
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private val QUALITY_KEYWORDS = mapOf(
            2160 to listOf("2160", "4k", "uhd"),
            1440 to listOf("1440", "2k", "qhd"),
            1080 to listOf("1080", "fhd", "full hd"),
            720 to listOf("720", "hd"),
            480 to listOf("480", "sd"),
            360 to listOf("360"),
        )

        private const val SERVER_KEY_UNKNOWN = "unknown"
        private const val SERVER_KEY_DOOD = "dood"
        private const val SERVER_KEY_VOE = "voe"
        private const val SERVER_KEY_MP4UPLOAD = "mp4upload"
        private const val SERVER_KEY_STREAMHIDE = "streamhide"
        private const val SERVER_KEY_STREAMWISH = "streamwish"
        private const val SERVER_KEY_YOURUPLOAD = "yourupload"
        private const val SERVER_KEY_FILEMOON = "filemoon"
        private const val SERVER_KEY_GOODSTREAM = "goodstream"
        private const val SERVER_KEY_LAMOVIE = "lamovie"

        private val SERVER_KEYWORDS = mapOf(
            SERVER_KEY_DOOD to listOf("dood", "d000d", "doodstream", "doodcdn", "doodapi", "ds2play"),
            SERVER_KEY_VOE to listOf("voe", "voeunblock", "voecloud"),
            SERVER_KEY_MP4UPLOAD to listOf("mp4upload", "mp4u", "mp4-cdn"),
            SERVER_KEY_STREAMHIDE to listOf("streamhide", "shtcdn", "shtembed", "shtplayer"),
            SERVER_KEY_STREAMWISH to listOf("streamwish", "hlswish", "wishfast", "wishflix", "wishvid", "strwish"),
            SERVER_KEY_YOURUPLOAD to listOf("yourupload", "urupload", "yourcdn"),
            SERVER_KEY_FILEMOON to listOf("filemoon", "moonplayer", "mooncdn", "moonstream"),
            SERVER_KEY_GOODSTREAM to listOf("goodstream", "gdstream", "gdst"),
            SERVER_KEY_LAMOVIE to listOf("lamovie", "la.movie", "vimeos"),
        )

        private val LANGUAGE_LATINO_REGEX = Regex("\\b(lat|latino|latam|latinoamerica|español|espanol|esp-lat|es-lat|es_lat)\\b")
        private val LANGUAGE_CASTELLANO_REGEX = Regex("\\b(cast|castellano|españa|espana|es-es|esp-es)\\b")
        private val LANGUAGE_SUB_REGEX = Regex("\\b(sub|subs|subtitulad[ao]|subtitulado|subtitulos|vose)\\b")
        private val LANGUAGE_ENGLISH_REGEX = Regex("\\b(english|ingles|inglés|eng)\\b")

        private const val PREF_SERVER_KEY = "preferred_server_lamovie"
        private const val PREF_SERVER_DEFAULT = "auto"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Sin preferencia",
            "DoodStream",
            "VOE",
            "MP4Upload",
            "StreamHide",
            "StreamWish",
            "YourUpload",
            "Filemoon",
            "GoodStream",
            "LaMovie (HLS)",
        )
        private val PREF_SERVER_VALUES = arrayOf(
            PREF_SERVER_DEFAULT,
            SERVER_KEY_DOOD,
            SERVER_KEY_VOE,
            SERVER_KEY_MP4UPLOAD,
            SERVER_KEY_STREAMHIDE,
            SERVER_KEY_STREAMWISH,
            SERVER_KEY_YOURUPLOAD,
            SERVER_KEY_FILEMOON,
            SERVER_KEY_GOODSTREAM,
            SERVER_KEY_LAMOVIE,
        )
    }

    private fun preferredListingType(key: String = PREF_POPULAR_KEY): String {
        val stored = preferences.getString(key, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
        return normalizeListingType(stored)
    }

    private fun normalizeListingType(raw: String): String = when (raw.lowercase(Locale.US)) {
        "movie", "movies", "peliculas", "películas" -> KIND_MOVIE
        "tvshow", "tvshows", "tv-show", "tv shows", "series" -> KIND_TVSHOW
        "anime", "animes" -> KIND_ANIME
        else -> DEFAULT_LISTING_TYPE
    }

    private fun normalizeLanguagePreference(raw: String): String = when (raw.lowercase(Locale.US)) {
        "any", "none", "sin preferencia", "todos" -> LANGUAGE_CODE_ANY
        "latino", "latam", "es-lat", "esp-lat", "es_lat" -> LANGUAGE_CODE_LATINO
        "castellano", "esp", "es-es", "españa", "esp-es", "spanish", "es" -> LANGUAGE_CODE_CASTELLANO
        "sub", "subs", "subtitulado", "subtitulos", "vose" -> LANGUAGE_CODE_SUB
        "english", "ingles", "inglés", "eng", "en" -> LANGUAGE_CODE_ENGLISH
        else -> raw.ifBlank { LANGUAGE_CODE_ANY }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_POPULAR_KEY
            title = "Tipo de contenido predeterminado"
            entries = CONTENT_ENTRIES
            entryValues = CONTENT_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
            val normalized = normalizeListingType(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = BASE_PREF_LATEST_KEY
            title = "Sección para últimas actualizaciones"
            entries = CONTENT_ENTRIES
            entryValues = CONTENT_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
            val normalized = normalizeListingType(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Calidad preferida"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            summary = "%s"

            val stored = preferences.getString(key, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            value = stored
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Idioma preferido"
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_LANGUAGE_DEFAULT) ?: PREF_LANGUAGE_DEFAULT
            val normalized = normalizeLanguagePreference(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor de video preferido"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
            value = stored
        }.also(screen::addPreference)
    }
}
