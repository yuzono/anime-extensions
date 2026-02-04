package eu.kanade.tachiyomi.animeextension.en.dopebox

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class DopeBox :
    DopeFlix(
        "DopeBox",
        "en",
        BuildConfig.MEGACLOUD_API,
        listOf("dopebox.to"),
    ) {
    override val id: Long = 787491081765201367
}
