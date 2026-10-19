package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Request
import okhttp3.Response

abstract class AnimeHttpLegacySource : AnimeHttpSource() {
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = getVideoList(episode).toHosterList()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    open suspend fun getVideoList(episode: SEpisode): List<Video> = client.newCall(videoListRequest(episode))
        .awaitSuccess()
        .use(::videoListParse)

    open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)
    open fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()
}
