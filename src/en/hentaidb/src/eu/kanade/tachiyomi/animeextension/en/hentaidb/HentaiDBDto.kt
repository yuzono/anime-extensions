package eu.kanade.tachiyomi.animeextension.en.hentaidb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// ============================== Catalog ===============================
// The site ships its whole anime catalog as a single static `/cat-<hash>.js` file
// (`self.CAT={...}`) and filters/sorts/searches it client side.

@Serializable
class CatalogDto(
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val rows: List<CatalogRowDto> = emptyList(),
    val mig: CatalogMigrationDto? = null,
)

@Serializable
class CatalogMigrationDto(
    val t: Map<String, String> = emptyMap(),
    val c: Map<String, Boolean> = emptyMap(),
)

/**
 * s = slug, t = title, a = cover path, y = listed year, st = studio index, e = episode count,
 * en = explicit episode numbers, d = episode durations (seconds), ty = type, sa = status,
 * c = cut (1 censored, 2 uncensored), q = best quality, f48 = 48 fps copy, u = subtitles
 * (1 any, 2 English), ul = subtitle language count, dub = English dub, g = tag indexes,
 * p = popularity rank, k = alternative titles / keywords.
 */
@Serializable
class CatalogRowDto(
    val s: String,
    val t: String,
    val a: String = "",
    val y: Int = 0,
    val st: Int? = null,
    val e: Int = 0,
    val en: List<Int>? = null,
    val d: List<Int> = emptyList(),
    val ty: Int = 0,
    val sa: Int = 0,
    val c: Int = 0,
    val q: Int = 0,
    val f48: Int = 0,
    val u: Int = 0,
    val ul: Int = 0,
    val dub: Int = 0,
    val g: List<Int> = emptyList(),
    val p: Int = 0,
    val k: String = "",
)

// ============================== Episode ===============================
// Watch pages embed the player configuration as `window.EP={...}`.

@Serializable
class EpisodeDataDto(
    val src: List<EpisodeSourceDto> = emptyList(),
    val dub: List<EpisodeSourceDto> = emptyList(),
    val tracks: List<EpisodeTrackDto> = emptyList(),
    val sets: List<List<EpisodeTrackDto>> = emptyList(),
)

@Serializable
class EpisodeSourceDto(
    val file: String,
    val q: String? = null,
    val r: Int? = null,
    val n: Int? = null,
    val c: String? = null,
    val sz: Long? = null,
    val d: Int? = null,
    val t: Int? = null,
    val off: JsonElement? = null,
) {
    val isOffline: Boolean
        get() = (off as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } } ?: false
}

@Serializable
class EpisodeTrackDto(
    val file: String,
    val label: String? = null,
    val kind: String? = null,
)
