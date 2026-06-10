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
        hosterNames = listOf("HD", "Vidstream", "VidCloud", "Kiwi-Stream"), // seed/fallback only
    )
