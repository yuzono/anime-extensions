package eu.kanade.tachiyomi.animeextension.en.movhub

import eu.kanade.tachiyomi.multisrc.yflix.YFlixTheme
import org.jsoup.nodes.Document

class MovHub :
    YFlixTheme(
        "MovHub",
        listOf(
            "1moviesz.to",
            "myflixer.bz",
            "bflix.la",
            "myflixer.fi",
        ),
    ) {

    override val moviesSelector = "div.movie-cards div.item"

    override fun Document.isMovie(): Boolean = selectFirst("ol.breadcrumb li a[href*='/movie']") != null

    override fun Document.getBackdropUrl(): String? = selectFirst("div.site-movie-bg")
        ?.attr("style")
        ?.substringAfter("url('", "")?.substringBefore("')", "")

    override fun Document.getScore(): String? = selectFirst("div.detail-lower")?.attr("data-score")

    // ============================== Episodes ==============================

    override fun Document.contentIdSelect(): String? = selectFirst("#movie-rating[data-id]")?.attr("data-id")

    // ============================ Video Links =============================

    override val serversSelector = "div.server"
}
