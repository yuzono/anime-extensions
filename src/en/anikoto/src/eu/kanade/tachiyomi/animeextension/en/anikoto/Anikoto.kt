package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class Anikoto :
    AnikotoTheme(
        "en",
        "Anikoto",
        domainEntries = listOf(
            "anikototv.to",
            "anikoto.bz",
            "anikoto.cz",
            "anikoto.me",
            "anikoto.net",
            "anikototv.se",
        ),
        hosterNames = listOf("HD", "Vidstream", "VidCloud", "Kiwi-Stream"),
    )
