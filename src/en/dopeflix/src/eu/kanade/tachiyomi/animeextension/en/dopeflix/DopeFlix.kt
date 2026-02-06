package eu.kanade.tachiyomi.animeextension.en.dopeflix

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class DopeFlix :
    DopeFlix(
        "DopeFlix",
        "en",
        BuildConfig.MEGACLOUD_API,
        listOf(
            "1flix.to",
            "flixhq.to",
            "fmovieszz.lol",
            "gomovies.gg",
            "hdtoday.cc",
            "himovies.sx",
            "movies4kto.lol",
            "myflixtor.tv",
            "series2watch.net",
            "watch32.sx",
            // "citysonic.tv",
        ),
    ) {
    override val detailInfoSelector by lazy { "div.detail_page-infor, div.m_i-detail" }
    override val coverSelector by lazy { "div.cover_follow, div.dp-w-cover, div.w_b-cover" }

    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
}
