package eu.kanade.tachiyomi.animeextension.zh.girigirilove

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

internal fun showFilterPath(type: String, sort: String, genre: String, year: String, page: Int): String = "$type--$sort-$genre-----$page---$year"

internal fun HttpUrl.matchesOrigin(source: HttpUrl): Boolean = scheme == source.scheme && host == source.host && port == source.port

internal fun HttpUrl.isSourceShowRequest(source: HttpUrl): Boolean = matchesOrigin(source) && encodedPath.startsWith("/show/")

private val EPISODE_PATH = Regex("/playGV[0-9]+-[0-9]+-[0-9]+/?")

internal fun HttpUrl.isEpisodeUrl(source: HttpUrl): Boolean = matchesOrigin(source) && EPISODE_PATH.matches(encodedPath) && fragment == null && query == null

internal fun Element.episodeUrl(source: HttpUrl): String? {
    if (attr("href").isBlank()) return null
    val resolved = absUrl("href").toHttpUrlOrNull() ?: return null
    return resolved.takeIf { it.isEpisodeUrl(source) }?.toString()
}
