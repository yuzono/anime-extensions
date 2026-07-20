// Contributed by @SahooShuvranshu (aka its_crystal.alive)
package eu.kanade.tachiyomi.animeextension.en.anichi

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

/**
 * Anichi Extension Source
 * Contributed by @SahooShuvranshu (aka its_crystal.alive)
 */
class Anichi :
    AnikotoTheme(
        "en",
        "Anichi",
        domainEntries = listOf(
            "anichi.to",
        ),
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"), // seed/fallback only
    )
