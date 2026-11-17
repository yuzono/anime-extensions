@file:OptIn(AnimiruOnly::class)

package keiyoushi.source

import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.annotation.AnimiruOnly
import keiyoushi.source.stream.AnimiruProxyServer
import keiyoushi.source.stream.StreamSources

/**
 * Keeps [HttpServer] out of [AniSource]: Anikku reflects over its methods (`checkHasHosters`)
 * and has no such class.
 */
abstract class AniSourceBase internal constructor() : AnimeHttpSource() {

    internal abstract fun servesStreams(): Boolean

    final override fun createHttpServer(): HttpServer? {
        if (!servesStreams() || !AppFeatures.animiruHttpServer) return null
        StreamSources.register(this as AniSource)
        return AnimiruProxyServer()
    }
}
