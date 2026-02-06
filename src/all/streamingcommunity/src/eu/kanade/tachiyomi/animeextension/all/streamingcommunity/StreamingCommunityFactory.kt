package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class StreamingCommunityFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        StreamingCommunity("en", "movie"),
        StreamingCommunity("it", "movie"),
        StreamingCommunity("en", "tv"),
        StreamingCommunity("it", "tv"),
    )
}
