package eu.kanade.tachiyomi.animeextension.en.wcostream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WCOStream : WcoTheme() {

    override val name = "WCOStream"

    override val baseUrl = "https://www.wcostream.tv"

    override fun popularAnimeSelector(): String = "div#content div.menu ul > li > a"
    override fun latestUpdatesSelector(): String = "div#content > div > div:has(div.recent-release:contains(Recent Releases)) > div > ul > li"
    override fun searchAnimeSelector(): String = "div#blog div.iccerceve"

    override fun episodeListSelector() = "div#catlist-listview > ul > li, table:has(> tbody > tr > td > h3:contains(Episode List)) div.menustyle > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = super.latestUpdatesFromElement(element)
        .apply { title = title.substringBefore(" Episode").trim() }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("div.video-title a")?.text()?.let { title = it }
        genre = document.select("div#cat-genre > div.wcobtn").joinToString { it.text() }
        description = document.select("div#content div.katcont div.iltext p").text()
        thumbnail_url = document.select("#cat-img-desc img").attr("abs:src")
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
