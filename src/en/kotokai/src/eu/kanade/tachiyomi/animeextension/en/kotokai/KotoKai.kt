package eu.kanade.tachiyomi.animeextension.en.kotokai

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class KotoKai :
    AnikotoTheme(
        "en",
        "AnimeKai (Unoriginal)",
        domainEntries = listOf(
            "animekaitv.to",
            "anikaitv.to",
            "animekai.se",
            "anikai.se",
        ),
        hosterNames = listOf("HD", "Vidstream", "VidCloud", "Kiwi-Stream"), // seed/fallback only
    )
