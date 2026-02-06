package eu.kanade.tachiyomi.animeextension.en.kaido

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.rapidcloudextractor.RapidCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import kotlin.getValue

class Kaido :
    ZoroTheme(
        "en",
        "Kaido",
        "https://kaido.to",
        hosterNames = listOf(
            "Vidstreaming",
            "Vidcloud",
        ),
    ) {
    private val rapidCloudExtractor by lazy { RapidCloudExtractor(client, headers, preferences) }

    override fun extractVideo(server: VideoData): List<Video> = when (server.name) {
        "Vidstreaming", "Vidcloud" -> rapidCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
        else -> emptyList()
    }
}
