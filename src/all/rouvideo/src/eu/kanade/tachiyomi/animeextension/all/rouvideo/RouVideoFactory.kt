package eu.kanade.tachiyomi.animeextension.all.rouvideo

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class RouVideoFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        RouVideo("all"),
        RouVideo("zh"),
        RouVideo("en"),
        RouVideo("vi"),
    )
}
