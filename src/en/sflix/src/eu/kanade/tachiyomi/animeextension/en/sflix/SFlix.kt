package eu.kanade.tachiyomi.animeextension.en.sflix

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class SFlix :
    DopeFlix(
        "SFlix",
        "en",
        BuildConfig.MEGACLOUD_API,
        listOf("sflix.ps", "sflix2.to"), // Domain list
    ) {
    override val id: Long = 8615824918772726940
}
