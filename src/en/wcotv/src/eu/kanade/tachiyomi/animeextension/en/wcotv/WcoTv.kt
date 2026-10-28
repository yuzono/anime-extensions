package eu.kanade.tachiyomi.animeextension.en.wcotv

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.Response

@Source
abstract class WcoTv : WcoTheme() {

    override val useOldIframeExtractor = true

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
            // Remove entries that have no URL
            .filterNot { it.url == "/" }
            .let { AnimesPage(it, false) }
    }
}
