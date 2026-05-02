package eu.kanade.tachiyomi.animeextension.en.dopeflix

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class DopeFlix :
    DopeFlix(
        "DopeFlix",
        "en",
        BuildConfig.MEGACLOUD_API,
        listOf(
            "1flix.stream",
            "flixhqz.com",
            "ww2-fmovies.com",
            "moviesdl.org",
            "movieclub-hd.com",
            // "himovies.sx", (dead)
            // "movies4kto.lol", (site works but irrelevant)
            // "myflixtor.tv", (merged into livezy.click)
            "series2watch.net",
            "watch32.sx",
            "livezy.click/citysonic", // citysonic current domain
        ),
    ) {
    override val detailInfoSelector by lazy { "div.detail_page-infor, div.m_i-detail" }
    override val coverSelector by lazy { "div.cover_follow, div.dp-w-cover, div.w_b-cover" }

    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
}
