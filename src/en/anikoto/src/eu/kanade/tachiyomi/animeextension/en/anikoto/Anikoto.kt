package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class Anikoto :
    AnikotoTheme(
        "en",
        "Anikoto",
        domainEntries = listOf(
            "anikototv.to",
            "anikoto.cz",
            "anikoto.me",
            "anikoto.net",
            "anikototv.se",
        ),
        hosterNames = listOf("megaplay", "vidstream", "vidcloud", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
        )
