package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class AniWave :
    AnikotoTheme(
        "en",
        "AniWave (Unoriginal)",
        domainEntries = listOf(
            "animewave.to",
            "aniwave.cz",
        ),
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"), // seed/fallback only
    )
