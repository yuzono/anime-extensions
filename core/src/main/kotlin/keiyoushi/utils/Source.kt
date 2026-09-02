package keiyoushi.utils

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

abstract class Source :
    AnimeHttpHosterSource(),
    ConfigurableAnimeSource {
    protected val context: Application by injectLazy()

    protected open val migration: SharedPreferences.() -> Unit = {}

    open val json: Json by injectLazy()

    val preferences: SharedPreferences by getPreferencesLazy { migration }

    protected val handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }

    // TODO: Remove with ext lib 16
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun seasonListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()
    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun videoListRequest(hoster: Hoster) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster) = throw UnsupportedOperationException()
}

fun Video.copyLegacy(
    // This is quick fix for the bug in Anikku preview r8888 (caused by a bug upstream)
    url: String = this.videoUrl,
    quality: String = this.videoTitle,
    videoUrl: String? = this.videoUrl,
    headers: Headers? = this.headers,
    subtitleTracks: List<Track> = this.subtitleTracks,
    audioTracks: List<Track> = this.audioTracks,
): Video = Video(
    url = url,
    quality = quality,
    videoUrl = videoUrl,
    headers = headers,
    subtitleTracks = subtitleTracks,
    audioTracks = audioTracks,
)
