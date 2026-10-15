package aniyomi.lib.anilib

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import keiyoushi.templating.ExtensionMetadata
import keiyoushi.templating.MetadataSubProvider
import keiyoushi.templating.MetaproviderContext
import keiyoushi.templating.stripHtml
import okhttp3.OkHttpClient

/**
 * [MetadataSubProvider] backed by the [AniLib] AniList GraphQL client.
 *
 * Uses [MetaproviderContext.anilistId] as the universal master ID to
 * fetch a [MediaSnapshot] via [AniLib.fetchMediaDetails], then maps
 * the relevant fields into [ExtensionMetadata].
 *
 * Only fills fields that the snapshot actually provides — everything
 * else returns `null` so the [keiyoushi.templating.MetadataProvider]
 * merge chain can fill gaps from other providers or the delegate.
 *
 * @param priority Lower runs earlier. Defaults to 20 (after a typical
 *   higher-priority scrape/search provider at 10).
 * @param titleStyle Passed to [AniLib.resolveTitle]. One of
 *   `"userPreferred"`, `"romaji"`, `"english"`, `"native"`.
 * @param cacheTtlMs Cache time-to-live for AniLib's SharedPreferences
 *   caching. Defaults to 15 minutes.
 */
class AniLibMetadataProvider(
    override val priority: Int = 20,
    override val name: String = "AniLibMetadataProvider",
    private val titleStyle: String = "userPreferred",
    private val cacheTtlMs: Long = 15 * 60_000L,
) : MetadataSubProvider {

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val anilistId = context.anilistId
            ?: return ExtensionMetadata()

        val client: OkHttpClient = context.httpClient
            ?: AniLib.run { defaultClient }

        val prefs: SharedPreferences? = context.preferences

        val snapshot = AniLib.fetchMediaDetails(
            client = client,
            anilistId = anilistId,
            prefs = prefs,
            cacheTtlMs = cacheTtlMs,
        ) ?: return ExtensionMetadata()

        val title = AniLib.resolveTitle(snapshot.title, titleStyle).ifEmpty { null }
        val cover = AniLib.resolveCoverUrl(snapshot.coverImage).ifEmpty { null }
        val studio = AniLib.resolveMainStudio(snapshot.studios).ifEmpty { null }
        val genres = snapshot.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val description = snapshot.description
            ?.takeIf { it.isNotBlank() }
            ?.let { stripHtml(it) }
        val status = mapStatus(snapshot.status)

        return ExtensionMetadata(
            title = title,
            description = description,
            thumbnailUrl = cover,
            author = studio,
            genre = genres,
            status = status,
        )
    }

    private fun mapStatus(status: String?): Int? = when (status?.uppercase()) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "NOT_YET_RELEASED" -> SAnime.LICENSED
        "CANCELLED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        else -> null
    }
}
