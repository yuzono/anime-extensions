package eu.kanade.tachiyomi.animeextension.en.wcostream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WCOStream : WcoTheme() {

    override val name = "WCOStream"

    override val baseUrl = "https://www.wcostream.tv"

    // wcostream.tv has no `#sidebar_right2` ("Recently Added Series"), so reuse
    // the "Recent Releases" grid for both Popular and Latest. The grid has
    // proper thumbnails, which is the whole point of the fix.
    override fun popularAnimeSelector(): String = "div#content > div > div:has(div.recent-release:contains(Recent Releases)) > div > ul > li"
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun searchAnimeSelector(): String = "div#blog div.iccerceve"

    override fun episodeListSelector() = "div#catlist-listview > ul > li, table:has(> tbody > tr > td > h3:contains(Episode List)) div.menustyle > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = super.latestUpdatesFromElement(element)
        .apply { title = title.substringBefore(" Episode").trim() }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        // Same fallback chain as the base theme, but using wcostream's anime
        // page selectors for thumbnail/genre/description.
        title = (
            document.selectFirst("div.video-title a")?.text()
                ?: document.selectFirst("div.header-tag h2 a")?.text()
                ?: document.selectFirst("div.video-title h1")?.text()
            ).orEmpty()
        genre = document.select("div#cat-genre > div.wcobtn").joinToString { it.text() }
            .ifBlank { null }
        description = document.select("div#content div.katcont div.iltext p").text()
            .ifBlank { null }
        thumbnail_url = document.selectFirst("#cat-img-desc img")?.attr("abs:src")
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
