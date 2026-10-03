package eu.kanade.tachiyomi.animeextension.en.wcoforever

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class WcoForever : WcoTheme() {
    override val name = "WcoForever"
    override val baseUrl = "https://www.wcoforever.net"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).mapNotNull {
            runCatching { episodeFromElement(it) }.getOrNull()
        }

        // Subbed first, then dubbed; within each group sort by episode number
        val sorted = episodes.sortedWith(
            compareBy(
                { it.name.contains("dub", ignoreCase = true) },
                { it.episode_number },
            ),
        )

        return sorted.mapIndexed { index, episode ->
            episode.apply {
                episode_number = (sorted.size - index).toFloat()
            }
        }
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        val title = document.select(".baslikCell").text()
                        val (name, _) = episodeTitleFromElement(title)
                        this.name = name
                    },
                )
            }
    }
}
