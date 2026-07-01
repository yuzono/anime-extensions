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
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"), // seed/fallback only
    )
