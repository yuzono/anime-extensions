package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class AniWave :
    AnikotoTheme(
        "en",
        "AniWave (Unoriginal)",
        domainEntries = listOf(
            "animewave.to",
            "aniwave.id",
            "aniwave.best",
            "aniwave.ro",
        ),
        hosterNames = listOf("megaplay", "vidstream", "vidcloud", "kiwi-stream"),
        hosterDisplayNames = listOf("MegaPlay", "Vidstream", "VidCloud", "Kiwi-Stream"),
    )
