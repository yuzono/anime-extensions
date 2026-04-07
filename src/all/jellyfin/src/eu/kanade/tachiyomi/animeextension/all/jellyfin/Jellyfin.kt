package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.FiltersDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemListDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemType
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.LoginDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.MediaLibraryDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.PlaybackInfoDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.SessionDto
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.HttpException
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.formatBytes
import keiyoushi.utils.get
import keiyoushi.utils.getListPreference
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.apache.commons.text.StringSubstitutor
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class Jellyfin(private val suffix: String) :
    Source(),
    UnmeteredSource {
    override val json: Json by lazy {
        Json {
            isLenient = false
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            namingStrategy = PascalCaseToCamelCase
        }
    }

    private val deviceInfo by lazy { getDeviceInfo(context) }
    private val displayName by lazy { preferences.displayName.ifBlank { suffix } }

    override var baseUrl by LazyMutable { preferences.hostUrl }

    override val lang = "all"

    override val name by lazy { "Jellyfin ($displayName)" }

    override val supportsLatest = true

    override val versionId = 2

    override val id by lazy {
        val key = "jellyfin ($suffix)/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    override val client = network.client.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8")
                .build()

            if (request.url.encodedPath.endsWith("AuthenticateByName")) {
                return@addInterceptor chain.proceed(request)
            }

            val apiKey = preferences.apiKey
            if (apiKey.isBlank()) {
                throw IOException("Please login in extension settings")
            }

            val authRequest = request.newBuilder()
                .addHeader("Authorization", getAuthHeader(deviceInfo, apiKey))
                .build()

            chain.proceed(authRequest)
        }
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        checkPreferences()

        val startIndex = (page - 1) * SERIES_FETCH_LIMIT
        val url = getItemsUrl(startIndex)

        return getAnimePage(url, page)
    }

    private suspend fun getAnimePage(url: HttpUrl, page: Int): AnimesPage {
        val items = client.get(url).parseAs<ItemListDto>(json)

        val animeList = items.items.map { it.toSAnime(baseUrl, preferences.userId, preferences.concatNames) }
        val hasNextPage = SERIES_FETCH_LIMIT * page < items.totalRecordCount

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        checkPreferences()

        val startIndex = (page - 1) * SERIES_FETCH_LIMIT
        val url = getItemsUrl(startIndex).newBuilder().apply {
            setQueryParameter("SortBy", "DateCreated,SortName")
            setQueryParameter("SortOrder", "Descending")
        }.build()

        return getAnimePage(url, page)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        checkPreferences()
        val filterList = filters.ifEmpty { getFilterList() }
        filterList.filterIsInstance<TypeFilter>().first().let {
            itemTypes = it.state.filter { s -> s.state }.map { s -> s.id }
            if (preferences.saveTypes) {
                preferences.saveTypesValue = json.encodeToString<List<ItemType>>(itemTypes)
            }
        }

        val startIndex = (page - 1) * SERIES_FETCH_LIMIT
        val url = if (query.isNotBlank()) {
            getItemsUrl(startIndex).newBuilder().apply {
                setQueryParameter("Limit", SERIES_FETCH_LIMIT.toString())
                setQueryParameter("SearchTerm", query)
            }.build()
        } else {
            baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("Users")
                addPathSegment(preferences.userId)
                addPathSegment("Items")
                addQueryParameter("StartIndex", startIndex.toString())
                addQueryParameter("Limit", SERIES_FETCH_LIMIT.toString())
                addQueryParameter("Recursive", "true")
                addQueryParameter("ImageTypeLimit", "1")
                addQueryParameter("ParentId", preferences.selectedLibrary)
                addQueryParameter("EnableImageTypes", "Primary")

                filterList.filterIsInstance<UrlFilter>().forEach {
                    it.addToUrl(this)
                }
            }.build()
        }
        return getAnimePage(url, page)
    }

    // ============================== Filters ===============================

    interface UrlFilter {
        fun addToUrl(url: HttpUrl.Builder)
    }

    class CheckboxFilter<T>(
        name: String,
        val id: T,
        state: Boolean = false,
    ) : AnimeFilter.CheckBox(name, state)

    open class CheckboxListFilter<T>(
        name: String,
        values: List<CheckboxFilter<T>>,
        val queryParam: String,
        val querySeparator: String = ",",
        val transform: (T) -> String = { it.toString() },
    ) : AnimeFilter.Group<CheckboxFilter<T>>(name, values),
        UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            val selected = state.filter { it.state }

            if (selected.isNotEmpty()) {
                url.addQueryParameter(
                    queryParam,
                    selected.joinToString(querySeparator) { transform(it.id) },
                )
            }
        }
    }

    class TypeFilter(selected: List<ItemType>) :
        CheckboxListFilter<ItemType>(
            "Select type(s)",
            listOf(
                CheckboxFilter("Movies", ItemType.Movie, ItemType.Movie in selected),
                CheckboxFilter("Series", ItemType.Series, ItemType.Series in selected),
                CheckboxFilter("Episodes", ItemType.Episode, ItemType.Episode in selected),
                CheckboxFilter("Seasons", ItemType.Season, ItemType.Season in selected),
                CheckboxFilter("Collections", ItemType.BoxSet, ItemType.BoxSet in selected),
            ),
            "IncludeItemTypes",
            transform = { it.name },
        )

    class SortFilter :
        AnimeFilter.Sort(
            "Sort by",
            sortList.map { it.first }.toTypedArray(),
            Selection(0, true),
        ),
        UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            val value = sortList[state!!.index].second
            val order = if (state!!.ascending) "Ascending" else "Descending"

            url.addQueryParameter("SortBy", value)
            url.addQueryParameter("SortOrder", order)
        }

        companion object {
            private val sortList = listOf(
                "Name" to "SortName",
                "Random" to "Random",
                "Community Rating" to "CommunityRating,SortName",
                "Date Show Added" to "DateCreated,SortName",
                "Date Episode Added" to "DateLastContentAdded,SortName",
                "Date Played" to "SeriesDatePlayed,SortName",
                "Parental Rating" to "OfficialRating,SortName",
                "Release Date" to "PremiereDate,SortName",
            )
        }
    }

    class FilterFilter :
        CheckboxListFilter<String>(
            "Filters",
            listOf(
                CheckboxFilter("Played", "IsPlayed"),
                CheckboxFilter("Unplayed", "IsUnPlayed"),
                CheckboxFilter("Resumable", "IsResumable"),
                CheckboxFilter("Favorites", "IsFavorite"),
            ),
            "Filters",
        )

    class StatusFilter :
        CheckboxListFilter<String>(
            "Status",
            listOf(
                CheckboxFilter("Continuing", "Continuing"),
                CheckboxFilter("Ended", "Ended"),
                CheckboxFilter("Not yet released", "Unreleased"),
            ),
            "SeriesStatus",
        )

    class FeaturesFilter :
        CheckboxListFilter<String>(
            "Features",
            listOf(
                CheckboxFilter("Subtitles", "HasSubtitles"),
                CheckboxFilter("Trailer", "HasTrailer"),
                CheckboxFilter("Special Features", "HasSpecialFeature"),
                CheckboxFilter("Theme song", "HasThemeSong"),
                CheckboxFilter("Theme video", "HasThemeVideo"),
            ),
            "unused",
        ) {
        override fun addToUrl(url: HttpUrl.Builder) {
            state.filter { it.state }.forEach {
                url.addQueryParameter(it.id, "true")
            }
        }
    }

    class GenreFilter(genres: List<String>) :
        CheckboxListFilter<String>(
            "Genres",
            genres.map { CheckboxFilter(it, it) },
            "Genres",
            querySeparator = "|",
        )

    class RatingFilter(ratings: List<String>) :
        CheckboxListFilter<String>(
            "Parental Ratings",
            ratings.map { CheckboxFilter(it, it) },
            "OfficialRatings",
            querySeparator = "|",
        )

    class TagFilter(tags: List<String>) :
        CheckboxListFilter<String>(
            "Tags",
            tags.map { CheckboxFilter(it, it) },
            "Tags",
            querySeparator = "|",
        )

    class YearFilter(years: List<Int>) :
        CheckboxListFilter<Int>(
            "Years",
            years.map { CheckboxFilter(it.toString(), it) },
            "Years",
        )

    private var itemTypes by LazyMutable {
        if (preferences.saveTypes) {
            preferences.saveTypesValue.parseAs<List<ItemType>>(json)
        } else {
            listOf(ItemType.Movie, ItemType.Series, ItemType.BoxSet)
        }
    }
    private var filterResult: FiltersDto? = null
    private var filtersState = FilterState.Unfetched
    private var filterAttempts = 0

    private enum class FilterState {
        Fetching,
        Fetched,
        Unfetched,
    }

    private suspend fun getFilters() {
        if (filtersState != FilterState.Fetching && filterAttempts < 3) {
            filtersState = FilterState.Fetching
            filterAttempts++

            try {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("Items")
                    addPathSegment("Filters")
                    addQueryParameter("UserId", preferences.userId)
                    addQueryParameter("ParentId", preferences.selectedLibrary)
                    addQueryParameter("IncludeItemTypes", itemTypes.joinToString(",") { it.name })
                }.build()

                filterResult = client.get(url).parseAs<FiltersDto>(json)
                filtersState = FilterState.Fetched
                filterAttempts = 0
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to fetch filters", e)
                filtersState = FilterState.Unfetched
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        CoroutineScope(Dispatchers.IO).launch { getFilters() }

        val filters = buildList {
            add(AnimeFilter.Header("Note: search ignores all filters except selected type(s)"))
            add(TypeFilter(itemTypes))
            add(AnimeFilter.Separator())
            add(SortFilter())

            add(FilterFilter())
            add(StatusFilter())
            add(FeaturesFilter())

            add(AnimeFilter.Separator())
            add(AnimeFilter.Header("Press 'reset' after searching to set filters for selected type(s)"))
            filterResult?.let { f ->
                f.genres?.takeIf { it.isNotEmpty() }?.let { add(GenreFilter(it)) }
                f.officialRatings?.takeIf { it.isNotEmpty() }?.let { add(RatingFilter(it)) }
                f.tags?.takeIf { it.isNotEmpty() }?.let { add(TagFilter(it)) }
                f.years?.takeIf { it.isNotEmpty() }?.let { add(YearFilter(it)) }
            }
        }

        return AnimeFilterList(filters)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val data = client.get(anime.url).parseAs<ItemDto>(json)
        val infoData = if (preferences.seriesData && data.seriesId != null) {
            val httpUrl = anime.url.toHttpUrl()
            val seriesUrl = httpUrl.newBuilder().apply {
                removePathSegment(httpUrl.pathSize - 1)
                addPathSegment(data.seriesId)
            }.build()

            client.get(seriesUrl).parseAs<ItemDto>(json)
        } else {
            data
        }

        return infoData.toSAnime(baseUrl, preferences.userId, preferences.concatNames)
    }

    // ============================== Episodes ==============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val httpUrl = anime.url.toHttpUrl()
        val itemId = httpUrl.pathSegments[3]
        val fragment = httpUrl.fragment!!

        val url = when {
            fragment.startsWith("boxSet") -> {
                httpUrl.newBuilder().apply {
                    removePathSegment(3)
                    addQueryParameter("SortBy", "SortName")
                    addQueryParameter("SortOrder", "Ascending")
                    addQueryParameter("IncludeItemTypes", "Movie,Season,BoxSet,Series")
                    addQueryParameter("ParentId", itemId)
                    addQueryParameter("Fields", "DateCreated,OriginalTitle,SortName")
                }.build()
            }

            fragment.startsWith("series") -> {
                httpUrl.newBuilder().apply {
                    encodedPath("/")
                    addPathSegment("Shows")
                    addPathSegment(itemId)
                    addPathSegment("Seasons")
                }.build()
            }

            else -> {
                httpUrl.newBuilder().apply {
                    addQueryParameter("Fields", "DateCreated,OriginalTitle,SortName")
                }.build()
            }
        }

        return client.get(url).parseAs<ItemListDto>(json).items.map {
            it.toSAnime(baseUrl, preferences.userId, preferences.concatNames)
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.toHttpUrl()
        val fragment = url.fragment!!
        val itemList = if (fragment == "movie") {
            listOf(client.get(url).parseAs<ItemDto>(json))
        } else {
            val episodesUrl = url.newBuilder().apply {
                encodedPath("/")
                addPathSegment("Shows")
                addPathSegment(fragment.split(",").last())
                addPathSegment("Episodes")
                addQueryParameter("seasonId", url.pathSegments.last())
                addQueryParameter("userId", preferences.userId)
                addQueryParameter("Fields", "Overview,MediaSources,DateCreated,OriginalTitle,SortName")
            }.build()

            client.get(episodesUrl).parseAs<ItemListDto>(json).items
        }

        return itemList.map {
            it.toSEpisode(
                baseUrl = baseUrl,
                userId = preferences.userId,
                epDetails = preferences.epDetails,
                episodeTemplate = preferences.episodeTemplate,
            )
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = getVideoList(episode).toHosterList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.get(episode.url).parseAs<ItemDto>(json)
        val mediaSource = item.mediaSources?.firstOrNull() ?: return emptyList()
        val itemId = item.id

        val videoList = mutableListOf<Video>()
        val subtitleList = mutableListOf<Track>()
        val externalSubtitleList = mutableListOf<Track>()

        var audioTrackIndex: Int? = null
        var subtitleTrackIndex: Int? = null
        var referenceBitrate = Constants.QUALITIES_LIST.first().videoBitrate

        mediaSource.mediaStreams.forEach { media ->
            when (media.type) {
                "Video" -> {
                    referenceBitrate = media.bitRate ?: referenceBitrate
                }

                "Subtitle" -> {
                    if (media.supportsExternalStream) {
                        val subtitleUrl = baseUrl.toHttpUrl().newBuilder().apply {
                            addPathSegment("Videos")
                            addPathSegment(itemId)
                            addPathSegment(mediaSource.id!!)
                            addPathSegment("Subtitles")
                            addPathSegment(media.index.toString())
                            addPathSegment("0")
                            addPathSegment("Stream.${media.codec}")
                        }.build().toString()

                        if (media.isExternal) {
                            externalSubtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                        }
                        subtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                    }
                    if (media.language == preferences.subLang) {
                        subtitleTrackIndex = media.index
                    }
                }

                "Audio" -> {
                    if (media.language == preferences.audioLang) {
                        audioTrackIndex = media.index
                    }
                }
            }
        }

        val sessionData = getSessionData(
            videoBitrate = Int.MAX_VALUE,
            audioBitrate = Int.MAX_VALUE,
            mediaId = mediaSource.id!!,
            itemId = itemId,
            audioStreamIndex = audioTrackIndex?.toString(),
            subtitleStreamIndex = subtitleTrackIndex?.toString(),
        )

        val videoBitrate = mediaSource.bitrate!!.toLong().formatBytes().replace("B", "b")
        val videoHeaders = headersBuilder()
            .add("Authorization", getAuthHeader(deviceInfo, preferences.apiKey))
            .build()

        val staticVideo = if (mediaSource.isRemote && mediaSource.path != null && preferences.useRemote) {
            Video(
                videoTitle = "Source - ${videoBitrate}ps (Remote)",
                videoUrl = mediaSource.path,
                bitrate = Int.MAX_VALUE,
                preferred = mediaSource.bitrate == preferences.quality.toInt(),
                subtitleTracks = externalSubtitleList,
                initialized = true,
            )
        } else {
            val staticUrl = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("Videos")
                addPathSegment(itemId)
                addPathSegment("stream")
                addQueryParameter("static", "True")
                addQueryParameter("PlaySessionId", sessionData.playSessionId)
            }.build().toString()

            Video(
                videoTitle = "Source - ${videoBitrate}ps",
                videoUrl = staticUrl,
                bitrate = Int.MAX_VALUE,
                headers = videoHeaders,
                preferred = mediaSource.bitrate == preferences.quality.toInt(),
                subtitleTracks = externalSubtitleList,
                initialized = true,
            )
        }

        val sessionMediaSource = sessionData.mediaSources.firstOrNull()
            ?: return emptyList()

        // Build video list
        if (sessionMediaSource.supportsDirectStream) {
            videoList.add(staticVideo)
        }

        if (!sessionMediaSource.supportsTranscoding) {
            return videoList
        }

        val qualities = Constants.QUALITIES_LIST.takeWhile { it.videoBitrate <= referenceBitrate }
        qualities.forEach {
            videoList.add(
                Video(
                    videoUrl = "",
                    videoTitle = it.description,
                    bitrate = it.videoBitrate,
                    headers = videoHeaders,
                    preferred = it.videoBitrate == preferences.quality.toInt(),
                    subtitleTracks = subtitleList,
                    internalData = TranscodingInfo(
                        videoBitrate = it.videoBitrate,
                        audioBitrate = it.audioBitrate,
                        mediaId = mediaSource.id,
                        itemId = itemId,
                        audioStreamIndex = audioTrackIndex?.toString(),
                        subtitleStreamIndex = subtitleTrackIndex?.toString(),
                    ).toJsonString(),
                ),
            )
        }

        return videoList
    }

    @Serializable
    data class TranscodingInfo(
        val videoBitrate: Int,
        val audioBitrate: Int,
        val mediaId: String,
        val itemId: String,
        val audioStreamIndex: String?,
        val subtitleStreamIndex: String?,
    )

    private suspend fun getSessionData(
        videoBitrate: Int,
        audioBitrate: Int,
        mediaId: String,
        itemId: String,
        audioStreamIndex: String?,
        subtitleStreamIndex: String?,
    ): SessionDto {
        val playbackInfo = PlaybackInfoDto(
            userId = preferences.userId,
            isPlayback = true,
            mediaSourceId = mediaId,
            maxStreamingBitrate = videoBitrate,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            alwaysBurnInSubtitleWhenTranscoding = preferences.burnSub,
            enableTranscoding = true,
            deviceProfile = getDeviceProfile(
                name = deviceInfo.name,
                videoCodec = preferences.videoCodec,
                videoBitrate = videoBitrate,
                audioBitrate = audioBitrate,
            ),
        )

        val sessionUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Items")
            addPathSegment(itemId)
            addPathSegment("PlaybackInfo")
            addQueryParameter("userId", preferences.userId)
        }.build().toString()

        return client.post(
            url = sessionUrl,
            body = json.encodeToString(playbackInfo).toJsonBody(),
        ).parseAs<SessionDto>(json)
    }

    override suspend fun resolveVideo(video: Video): Video? {
        val transcodingInfo = video.internalData.parseAs<TranscodingInfo>(json)
        val sessionData = getSessionData(
            videoBitrate = transcodingInfo.videoBitrate,
            audioBitrate = transcodingInfo.audioBitrate,
            mediaId = transcodingInfo.mediaId,
            itemId = transcodingInfo.itemId,
            audioStreamIndex = transcodingInfo.audioStreamIndex,
            subtitleStreamIndex = transcodingInfo.subtitleStreamIndex,
        )

        return sessionData.mediaSources.firstOrNull()?.transcodingUrl?.let {
            video.copy(
                videoUrl = baseUrl + it,
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = sortedWith(
        compareBy { it.bitrate!! },
    ).reversed()

    // =============================== Login ================================

    data class DeviceInfo(
        val clientName: String,
        val version: String,
        val id: String,
        val name: String,
    )

    // From https://github.com/jellyfin/jellyfin-sdk-kotlin
    private fun Application.getDeviceName(): String {
        // Use name from device settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            if (!name.isNullOrBlank()) return name
        }

        // Concatenate the name based on manufacturer and model
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL

        return if (model.startsWith(manufacturer) || manufacturer.isBlank()) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private fun getDeviceInfo(context: Application): DeviceInfo {
        val deviceId = preferences.deviceId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
                .also { preferences.edit().putString(DEVICEID_KEY, it).apply() }
        val name = context.getDeviceName()

        return DeviceInfo(
            clientName = "Aniyomi",
            version = BuildConfig.VERSION_NAME,
            id = deviceId,
            name = name,
        )
    }

    private suspend fun authenticate(username: String, password: String): LoginDto {
        val authHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo))

        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toJsonRequestBody(json)

        return try {
            val resp = client.post(
                url = "$baseUrl/Users/AuthenticateByName",
                headers = authHeaders,
                body = body,
            )

            resp.parseAs<LoginDto>(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to perform login", e)
            throw IOException("Failed to login", e)
        }
    }

    // ============================= Utilities ==============================

    private fun getItemsUrl(startIndex: Int): HttpUrl = baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("Users")
        addPathSegment(preferences.userId)
        addPathSegment("Items")
        addQueryParameter("StartIndex", startIndex.toString())
        addQueryParameter("Limit", SERIES_FETCH_LIMIT.toString())
        addQueryParameter("Recursive", "true")
        addQueryParameter("SortBy", "SortName")
        addQueryParameter("SortOrder", "Ascending")
        addQueryParameter("IncludeItemTypes", itemTypes.joinToString(",") { it.name })
        addQueryParameter("ImageTypeLimit", "1")
        addQueryParameter("ParentId", preferences.selectedLibrary)
        addQueryParameter("EnableImageTypes", "Primary")
    }.build()

    private fun checkPreferences() {
        if (preferences.selectedLibrary.isBlank()) {
            throw IllegalStateException("Select library in extension settings")
        }
    }

    private val SharedPreferences.deviceId
        get() = getString(DEVICEID_KEY, null)

    companion object {
        private const val SERIES_FETCH_LIMIT = 20
        private val LIBRARY_BLACKLIST = listOf(
            "music",
            "musicvideos",
            "trailers",
            "books",
            "photos",
            "livetv",
        )

        private const val DEVICEID_KEY = "device_id"
        const val APIKEY_KEY = "api_key"
        const val USERID_KEY = "user_id"
        const val LIBRARY_LIST_KEY = "media_library_list"

        internal const val EXTRA_SOURCES_COUNT_KEY = "extraSourcesCount"
        internal const val EXTRA_SOURCES_COUNT_DEFAULT = "3"
        private val EXTRA_SOURCES_ENTRIES = (1..10).map { it.toString() }

        private const val PREF_CUSTOM_LABEL_KEY = "pref_label"
        private const val PREF_CUSTOM_LABEL_DEFAULT = ""

        private const val HOSTURL_KEY = "host_url"
        private const val HOSTURL_DEFAULT = ""

        private const val USERNAME_KEY = "username"
        private const val USERNAME_DEFAULT = ""

        private const val PASSWORD_KEY = "password"
        private const val PASSWORD_DEFAULT = ""

        private const val MEDIA_LIBRARY_KEY = "library_pref"
        private const val MEDIA_LIBRARY_DEFAULT = ""

        private const val PREF_EPISODE_NAME_TEMPLATE_KEY = "pref_episode_name_template"
        private const val PREF_EPISODE_NAME_TEMPLATE_DEFAULT = "{type} {number} - {title}"

        private const val PREF_EP_DETAILS_KEY = "pref_episode_details_key"
        private val PREF_EP_DETAILS = listOf("Overview", "Runtime", "Size")
        private val PREF_EP_DETAILS_DEFAULT = emptySet<String>()

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = Int.MAX_VALUE.toString()

        private const val PREF_VIDEO_CODEC_KEY = "pref_video_codec"
        private const val PREF_VIDEO_CODEC_DEFAULT = "h264"

        private const val PREF_AUDIO_KEY = "preferred_audioLang"
        private const val PREF_AUDIO_DEFAULT = "jpn"

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_DEFAULT = "eng"

        private const val PREF_BURN_SUB_KEY = "pref_burn_subs"
        private const val PREF_BURN_SUB_DEFAULT = false

        private const val PREF_INFO_TYPE = "preferred_meta_type"
        private const val PREF_INFO_DEFAULT = false

        private const val PREF_CONCATENATE_NAMES_KEY = "preferred_concatenate_names"
        private const val PREF_CONCATENATE_NAMES_DEFAULT = false

        private const val PREF_SAVE_TYPES_KEY = "preferred_save_types"
        private const val PREF_SAVE_TYPES_DEFAULT = false
        private const val PREF_SAVE_TYPES_VALUE = "preferred_save_types_value"

        private const val PREF_USE_REMOTE_KEY = "pref_use_remote"
        private const val PREF_USE_REMOTE_DEFAULT = true

        private val SUBSTITUTE_VALUES = hashMapOf(
            "title" to "",
            "originalTitle" to "",
            "sortTitle" to "",
            "type" to "",
            "typeShort" to "",
            "seriesTitle" to "",
            "seasonTitle" to "",
            "number" to "",
            "createdDate" to "",
            "releaseDate" to "",
            "size" to "",
            "sizeBytes" to "",
            "runtime" to "",
            "runtimeS" to "",
        )
        private val STRING_SUBSTITUTOR = StringSubstitutor(SUBSTITUTE_VALUES, "{", "}").apply {
            isEnableUndefinedVariableException = true
        }

        private const val LOG_TAG = "Jellyfin"
    }

    // ============================ Preferences =============================

    // Updating `name` requires a restart, so there's no point in using a delegate for this
    private val SharedPreferences.displayName
        get() = getString(PREF_CUSTOM_LABEL_KEY, PREF_CUSTOM_LABEL_DEFAULT)!!

    private var SharedPreferences.libraryList by preferences.delegate(LIBRARY_LIST_KEY, "[]")
    private var SharedPreferences.userId by preferences.delegate(USERID_KEY, "")
    private var SharedPreferences.apiKey by preferences.delegate(APIKEY_KEY, "")
    private val SharedPreferences.hostUrl by preferences.delegate(HOSTURL_KEY, HOSTURL_DEFAULT)
    private val SharedPreferences.username by preferences.delegate(USERNAME_KEY, USERNAME_DEFAULT)
    private val SharedPreferences.password by preferences.delegate(PASSWORD_KEY, PASSWORD_DEFAULT)
    private var SharedPreferences.selectedLibrary by preferences.delegate(MEDIA_LIBRARY_KEY, MEDIA_LIBRARY_DEFAULT)
    private val SharedPreferences.episodeTemplate by preferences.delegate(
        PREF_EPISODE_NAME_TEMPLATE_KEY,
        PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
    )
    private val SharedPreferences.epDetails by preferences.delegate(PREF_EP_DETAILS_KEY, PREF_EP_DETAILS_DEFAULT)
    private val SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.videoCodec by preferences.delegate(PREF_VIDEO_CODEC_KEY, PREF_VIDEO_CODEC_DEFAULT)
    private val SharedPreferences.audioLang by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val SharedPreferences.subLang by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.burnSub by preferences.delegate(PREF_BURN_SUB_KEY, PREF_BURN_SUB_DEFAULT)
    private val SharedPreferences.seriesData by preferences.delegate(PREF_INFO_TYPE, PREF_INFO_DEFAULT)
    private val SharedPreferences.concatNames by preferences.delegate(
        PREF_CONCATENATE_NAMES_KEY,
        PREF_CONCATENATE_NAMES_DEFAULT,
    )
    private val SharedPreferences.saveTypes by preferences.delegate(PREF_SAVE_TYPES_KEY, PREF_SAVE_TYPES_DEFAULT)
    private var SharedPreferences.saveTypesValue by preferences.delegate(PREF_SAVE_TYPES_VALUE, "[]")
    private val SharedPreferences.useRemote by preferences.delegate(PREF_USE_REMOTE_KEY, PREF_USE_REMOTE_DEFAULT)

    private fun clearCredentials() {
        preferences.libraryList = "[]"
        preferences.selectedLibrary = MEDIA_LIBRARY_DEFAULT
        preferences.userId = ""
        preferences.apiKey = ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loginJob: Job? = null
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mediaLibrarySummary: (String) -> String = {
            if (it.isBlank()) {
                "Currently not logged in"
            } else {
                "Selected: %s"
            }
        }
        val libraryList = preferences.libraryList.parseAs<List<MediaLibraryDto>>(json)
        val mediaLibraryPref = screen.getListPreference(
            key = MEDIA_LIBRARY_KEY,
            default = MEDIA_LIBRARY_DEFAULT,
            title = "Select media library",
            summary = mediaLibrarySummary(preferences.apiKey),
            entries = libraryList.map { it.name },
            entryValues = libraryList.map { it.id },
            enabled = preferences.apiKey.isNotBlank(),
        )

        fun onCompleteLogin(result: Boolean) {
            mediaLibraryPref.setEnabled(result)
            mediaLibraryPref.summary = mediaLibrarySummary(if (result) "unused" else "")
            mediaLibraryPref.value = ""

            if (result) {
                val libraryList = preferences.libraryList.parseAs<List<MediaLibraryDto>>(json)
                mediaLibraryPref.entries = libraryList.map { it.name }.toTypedArray()
                mediaLibraryPref.entryValues = libraryList.map { it.id }.toTypedArray()

                // Only enable the preference if login succeeded
                mediaLibraryPref.setEnabled(true)
            } else {
                clearCredentials()
            }
        }

        fun logIn() {
            mediaLibraryPref.setEnabled(false)
            mediaLibraryPref.summary = "Loading..."
            clearCredentials()

            loginJob?.cancel()
            loginJob = scope.launch {
                try {
                    val loginDto = authenticate(preferences.username, preferences.password)

                    preferences.userId = loginDto.sessionInfo.userId
                    preferences.apiKey = loginDto.accessToken

                    val getLibrariesUrl = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("Users")
                        addPathSegment(loginDto.sessionInfo.userId)
                        addPathSegment("Items")
                    }.build()

                    val libraryList = client.get(getLibrariesUrl).parseAs<ItemListDto>(json)
                        .items
                        .filterNot { it.collectionType in LIBRARY_BLACKLIST }
                        .map { MediaLibraryDto(it.name, it.id) }
                    val libraryListJson = json.encodeToString<List<MediaLibraryDto>>(libraryList)

                    displayToast("Login successful")

                    handler.post {
                        preferences.libraryList = libraryListJson
                        onCompleteLogin(true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    val message = when {
                        e is HttpException && e.code == 401 -> "Invalid credentials"
                        else -> e.message
                    }

                    Log.e(LOG_TAG, "Failed to login", e)
                    displayToast("Login failed: $message")
                    handler.post {
                        onCompleteLogin(false)
                    }
                }
            }
        }

        if (suffix == "1") {
            screen.addListPreference(
                key = EXTRA_SOURCES_COUNT_KEY,
                default = EXTRA_SOURCES_COUNT_DEFAULT,
                title = "Number of sources",
                summary = "Number of Jellyfin sources to create. There will always be at least one Jellyfin source.",
                entries = EXTRA_SOURCES_ENTRIES,
                entryValues = EXTRA_SOURCES_ENTRIES,
                restartRequired = true,
            )
        }

        screen.addEditTextPreference(
            key = PREF_CUSTOM_LABEL_KEY,
            default = suffix,
            title = "Source display name",
            summary = displayName.ifBlank { "Here you can change the source displayed suffix" },
            restartRequired = true,
        )

        val addressUrlSummary: (String) -> String = { it.ifBlank { "The server address" } }
        screen.addEditTextPreference(
            key = HOSTURL_KEY,
            default = HOSTURL_DEFAULT,
            title = "Address",
            summary = addressUrlSummary(baseUrl),
            getSummary = addressUrlSummary,
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = { "The URL is invalid, malformed, or ends with a slash" },
        ) {
            baseUrl = it

            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (preferences.username.isNotBlank() && preferences.password.isNotBlank()) {
                    logIn()
                }
            }
        }

        val userNameSummary: (String) -> String = { it.ifBlank { "The user account name" } }
        screen.addEditTextPreference(
            key = USERNAME_KEY,
            default = USERNAME_DEFAULT,
            title = "Username",
            summary = userNameSummary(preferences.username),
            getSummary = userNameSummary,
        ) {
            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (baseUrl.isNotBlank() && preferences.password.isNotBlank()) {
                    logIn()
                }
            }
        }

        val passwordSummary: (String) -> String = {
            if (it.isBlank()) "The user account password" else "•".repeat(it.length)
        }
        screen.addEditTextPreference(
            key = PASSWORD_KEY,
            default = PASSWORD_DEFAULT,
            title = "Password",
            summary = passwordSummary(preferences.password),
            getSummary = passwordSummary,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) {
            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (baseUrl.isNotBlank() && preferences.username.isNotBlank()) {
                    logIn()
                }
            }
        }

        screen.addPreference(mediaLibraryPref)

        screen.addEditTextPreference(
            key = PREF_EPISODE_NAME_TEMPLATE_KEY,
            default = PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
            title = "Episode title format",
            summary = "Customize how episode names appear",
            dialogMessage = """
            |Supported placeholders:
            |- {title}: Episode name
            |- {originalTitle}: Original title
            |- {sortTitle}: Sort title
            |- {type}: Type, 'Episode' for episodes and 'Movie' for movies
            |- {typeShort}: Type, 'Ep.' for episodes and 'Movie' for movies
            |- {seriesTitle}: Series name
            |- {seasonTitle}: Season name
            |- {number}: Episode number
            |- {createdDate}: Episode creation date
            |- {releaseDate}: Episode release date
            |- {size}: Episode file size (formatted)
            |- {sizeBytes}: Episode file size (in bytes)
            |- {runtime}: Episode runtime (formatted)
            |- {runtimeS}: Episode runtime (in seconds)
            |If you wish to place some text between curly brackets, place the escape character "$"
            |before the opening curly bracket, e.g. ${'$'}{series}.
            """.trimMargin(),
            inputType = InputType.TYPE_CLASS_TEXT,
            validate = {
                try {
                    STRING_SUBSTITUTOR.replace(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = { "Invalid episode title format" },
        )

        screen.addSetPreference(
            key = PREF_EP_DETAILS_KEY,
            default = PREF_EP_DETAILS_DEFAULT,
            title = "Additional details for episodes",
            summary = "Show additional details about an episode in the scanlator field",
            entries = PREF_EP_DETAILS,
            entryValues = PREF_EP_DETAILS,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "Preferred quality. 'Source' means no transcoding.",
            entries = listOf("Source") + Constants.QUALITIES_LIST.reversed().map { it.description },
            entryValues =
            listOf(PREF_QUALITY_DEFAULT) + Constants.QUALITIES_LIST.reversed().map { it.videoBitrate.toString() },
        )

        screen.addEditTextPreference(
            key = PREF_VIDEO_CODEC_KEY,
            default = PREF_VIDEO_CODEC_DEFAULT,
            title = "Transcoding video codec",
            summary = "Video codec when transcoding. Does not affect 'Source' quality.",
        )

        screen.addEditTextPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred transcoding audio language",
            summary = "Preferred audio when transcoding. Does not affect 'Source' quality.",
            dialogMessage = "Enter language as 3 letter ISO 639-2/T code",
            validate = { it in Constants.LANG_CODES },
            validationMessage = {
                if (it.length == 3) {
                    "'$it' is not a valid code"
                } else {
                    "'$it' is not a three letter code"
                }
            },
        )

        screen.addEditTextPreference(
            key = PREF_SUB_KEY,
            default = PREF_SUB_DEFAULT,
            title = "Preferred transcoding subtitle language",
            summary = "Preferred subtitle when transcoding. Does not affect 'Source' quality.",
            dialogMessage = "Enter language as 3 letter ISO 639-2/T code",
            validate = { it in Constants.LANG_CODES },
            validationMessage = {
                if (it.length == 3) {
                    "'$it' is not a valid code"
                } else {
                    "'$it' is not a three letter code"
                }
            },
        )

        screen.addSwitchPreference(
            key = PREF_BURN_SUB_KEY,
            default = PREF_BURN_SUB_DEFAULT,
            title = "Burn in subtitles",
            summary = "Burn in subtitles when transcoding. Does not affect 'Source' quality.",
        )

        screen.addSwitchPreference(
            key = PREF_INFO_TYPE,
            default = PREF_INFO_DEFAULT,
            title = "Retrieve metadata from series",
            summary = "Enable this to retrieve metadata from series instead of season when applicable.",
        )

        screen.addSwitchPreference(
            key = PREF_CONCATENATE_NAMES_KEY,
            default = PREF_CONCATENATE_NAMES_DEFAULT,
            title = "Concatenate series and season names",
            summary = "",
        )

        screen.addSwitchPreference(
            key = PREF_SAVE_TYPES_KEY,
            default = PREF_SAVE_TYPES_DEFAULT,
            title = "Save selected types filter",
            summary = "Applies to Popular, Latest, and Search",
        )

        screen.addSwitchPreference(
            key = PREF_USE_REMOTE_KEY,
            default = PREF_USE_REMOTE_DEFAULT,
            title = "Use remote link",
            summary = "Stream from remote link directly instead of proxying through jellyfin.",
        )
    }
}
