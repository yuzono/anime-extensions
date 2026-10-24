package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import keiyoushi.annotation.Source

@Source
abstract class Anikoto :
    AnikotoTheme(
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"), // seed/fallback only
    ) {
    override val hasSourceFilter = true
    override val hasEpisodeFilter = true
}
