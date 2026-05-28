package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class Anikoto :
    AnikotoTheme(
        "en",
        "Anikoto",
        // https://anikoto.site/#domains and https://megaplay.buzz/domains
        domainEntries = listOf(
            "anikototv.to",
            "anikoto.bz",
            "anikoto.cz",
            "anikoto.me",
            "anikoto.net",
            "anikototv.se",
        ),
        hosterNames = listOf("megaplay", "vidstream", "vidcloud", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
    )
