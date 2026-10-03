package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

abstract class ParsedAnimeHttpLegacySource : ParsedAnimeHttpSource() {
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = getVideoList(episode).toHosterList()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    open suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode))
        .awaitSuccess()
        .use(::videoListParse)

    open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    open fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).map { videoFromElement(it) }
    }
    open fun videoListSelector(): String = throw UnsupportedOperationException()
    open fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun seasonListSelector(): String = throw UnsupportedOperationException()
    override fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException()
}
