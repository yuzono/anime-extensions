# Modular Extension Templating System

A templating system for building extensions that decouples identity, metadata sourcing, and preference management from the extension class itself.

This system coexists with the existing `keiyoushi.utils.Source` base class and `lib-multisrc` themes. Existing extensions are unaffected. New extensions extend `AnimeExtension` instead of `AnimeHttpSource`.

## Package Layout

```
core/src/main/kotlin/keiyoushi/templating/
  AnimeExtension.kt          # Abstract base class — the template
  ExtensionMetadata.kt       # Standardized metadata data class
  MetaproviderContext.kt     # Context object passed to providers/delegates
  MetadataSubProvider.kt     # Interface for pluggable sub-providers
  MetadataProvider.kt        # Per-extension orchestrator (merging + ID resolution)
  AnimeDatabaseCache.kt      # Downloads/caches anime-offline-database, builds ID maps
  LocalAnimeDatabaseProvider.kt  # Reads from cached database (priority 0)
  PreferenceEntry.kt         # Sealed interface: Text / List / Switch / MultiSelect
  PreferenceRegistry.kt       # Auto-managed schema → typed reads + UI + persistence

lib/anilib/src/aniyomi/lib/anilib/
  AniLibMetadataProvider.kt  # Concrete provider wrapping AniLib (priority 20)
  TenraiMetadataProvider.kt  # Jikan v4 compatible (priority 10, reads nativeIds["mal"])
  KitsuMetadataProvider.kt   # Kitsu REST API (priority 15, reads nativeIds["kitsu"])
```

## How It Fits Together

```
                    AnimeExtension (abstract)
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ExtensionMetadata   MetadataProvider  PreferenceRegistry
    (identity + seed)   (resolve chain)  (schema → prefs + UI)
                           │
                    ┌──────┴──────┐
                    ▼             ▼
              Sub-Providers   Delegate (pre-populate)
              (ordered)       (always wins)
```

## Quick Start

### 1. Extend `AnimeExtension`

```kotlin
class MyExtension : AnimeExtension() {
    override val identity = ExtensionMetadata(
        name = "MySite",
        lang = "en",
        baseUrl = "https://mysite.example",
    )
    // ...
}
```

`name`, `lang`, `baseUrl`, and `supportsLatest` are delegated to `identity`. No separate `override val` needed.

### 2. Declare Preferences (Schema)

```kotlin
override val preferenceSchema = listOf(
    PreferenceEntry.List(
        key = "preferred_quality",
        title = "Preferred quality",
        summary = "%s",
        default = "720",
        entries = listOf("1080p", "720p", "480p"),
        entryValues = listOf("1080", "720", "480"),
    ),
    PreferenceEntry.Switch(
        key = "mark_fillers",
        title = "Mark filler episodes",
        summary = "Mark filler episodes in the episode list",
        default = true,
    ),
)
```

That's it. The template auto-generates:
- **UI** — `setupPreferenceScreen()` is already implemented. It calls the existing `keiyoushi.utils.add*Preference` helpers.
- **Typed reads** — `preferenceRegistry["preferred_quality"]` returns the value.
- **Persistence** — reads/writes go through `SharedPreferences` via the existing `PreferenceDelegate` mechanism.

Reading a preference:

```kotlin
val quality = preferenceRegistry["preferred_quality"] as String
val markFillers = preferenceRegistry["mark_fillers"] as Boolean
```

### 3. Add Metadata Providers (Optional)

Sub-providers supply metadata (title, description, thumbnail, genre, status, etc.) from external sources. Each provider implements `MetadataSubProvider`:

```kotlin
class MyScrapeProvider : MetadataSubProvider {
    override val priority = 10  // lower runs first
    override val name = "MyScrapeProvider"

    override suspend fun provide(context: MetaproviderContext): ExtensionMetadata {
        val doc = context.document ?: return ExtensionMetadata()
        return ExtensionMetadata(
            title = doc.selectFirst("h1")?.text(),
            description = doc.selectFirst(".synopsis")?.text(),
            thumbnailUrl = doc.selectFirst("img")?.absUrl("src"),
            genre = doc.select(".genre a").eachText().joinToString(),
        )
    }
}
```

Register providers on the extension:

```kotlin
override val metadataSubProviders = listOf(
    LocalAnimeDatabaseProvider(priority = 0),  // local cache first
    TenraiMetadataProvider(priority = 10),      // enrich with MAL data
    KitsuMetadataProvider(priority = 15),       // enrich with Kitsu data
    AniLibMetadataProvider(priority = 20),      // enrich with AniList data
)
```

#### AniList ID as Master ID

All providers receive `MetaproviderContext.anilistId` — the universal AniList ID that acts as the consistent unique input for the entire provider chain. The `MetadataProvider` orchestrator automatically resolves native IDs (MAL, Kitsu, AniDB) from the cached anime-offline-database before providers run:

```kotlin
// The orchestrator automatically populates nativeIds:
// context.nativeIds["mal"] = 20      (MAL ID)
// context.nativeIds["kitsu"] = 20    (Kitsu ID)
// context.nativeIds["anidb"] = 1     (AniDB ID)
```

Providers that use a different ID space read their native ID from `context.nativeIds`:

```kotlin
// In TenraiMetadataProvider:
val malId = context.nativeIds["mal"] ?: return ExtensionMetadata()
```

Key convention: `"mal"` for MyAnimeList, `"kitsu"` for Kitsu, `"anidb"` for AniDB, etc.

#### Built-in Providers

| Provider | Priority | ID Source | Description |
|---|---|---|---|
| `LocalAnimeDatabaseProvider` | 0 | `anilistId` | Reads from cached anime-offline-database (no network) |
| `TenraiMetadataProvider` | 10 | `nativeIds["mal"]` | Jikan v4 compatible REST API |
| `KitsuMetadataProvider` | 15 | `nativeIds["kitsu"]` | Kitsu REST API |
| `AniLibMetadataProvider` | 20 | `anilistId` | AniList GraphQL via AniLib |

### 4. Use a Pre-Populate Delegate (Optional)

If the extension already knows some metadata (e.g. from an API response), pass a delegate that runs **before** providers. Delegate fields always win — providers can only fill `null` gaps:

```kotlin
override val metadataDelegate = { ctx: MetaproviderContext ->
    ExtensionMetadata(
        title = apiResponse.title,
        isNsfw = true,
    )
}
```

### 5. Resolve Metadata at Runtime

```kotlin
override suspend fun getAnimeDetails(anime: SAnime): SAnime {
    val response = client.newCall(animeDetailsRequest(anime)).await()
    val document = response.asJsoup()

    val context = MetaproviderContext(
        baseUrl = baseUrl,
        anilistId = anilistIdFromUrl(anime.url),  // resolve once from source URL
        animeUrl = anime.url,
        httpClient = client,
        headers = headers,
        document = document,
        preferences = preferences,
        context = applicationContext,
    )
    val meta = resolveMetadata(context)

    return SAnime.create().apply {
        title = meta.title ?: anime.title
        description = meta.description
        thumbnail_url = meta.thumbnailUrl
        genre = meta.genre
        status = meta.status ?: SAnime.UNKNOWN
    }
}
```

## Full Example

### When No IDs Are Available

The provider chain depends on IDs to look up external metadata. If a source has **no AniList, MAL, or Kitsu ID**, every built-in provider returns empty metadata silently. The only metadata path is the delegate.

| Provider | Required ID | Returns when missing |
|---|---|---|
| `LocalAnimeDatabaseProvider` | `anilistId` | empty |
| `TenraiMetadataProvider` | `nativeIds["mal"]` | empty |
| `KitsuMetadataProvider` | `nativeIds["kitsu"]` | empty |
| `AniLibMetadataProvider` | `anilistId` | empty |

Sources that only have a MAL ID (but no AniList ID) can still use Tenrai — the delegate must populate `nativeIds["mal"]` manually. Similarly, sources with no external IDs at all should populate metadata entirely from their own API response via the delegate:

```kotlin
override val metadataMergeStrategy = MergeStrategy.FILL_NULLS

override val metadataDelegate = { ctx: MetaproviderContext ->
    val apiData = fetchFromSourceApi(ctx.animeUrl)
    ExtensionMetadata(
        title = apiData.title,
        description = apiData.description,
        thumbnailUrl = apiData.cover,
        genre = apiData.genres.joinToString(),
        status = apiData.status,
    )
}
```

In this scenario the delegate seeds all fields, and the (empty) provider chain has nothing to add.

#### Manually Populating `nativeIds[]`

The `ExtensionMetadata.nativeIds` map is a `Map<String, Int>` that carries provider-specific IDs (MAL, Kitsu, AniDB, etc.). The orchestrator automatically populates the context's native IDs when an AniList ID is available. However, when a source only has a different ID type, you must include the IDs in your delegate's returned metadata.

**Key convention:** `"mal"` for MyAnimeList, `"kitsu"` for Kitsu, `"anidb"` for AniDB, `"anilist"` for AniList.

```kotlin
// Example: Source that only has MAL IDs (no AniList ID)
override val metadataDelegate = { ctx: MetaproviderContext ->
    // Extract MAL ID from source URL
    val malId = ctx.animeUrl?.let { url ->
        val match = Regex("/anime/(\\d+)").find(url)
        match?.groupValues?.get(1)?.toIntOrNull()
    }

    ExtensionMetadata(
        title = sourceTitle,
        description = sourceDescription,
        thumbnailUrl = sourceThumbnail,
        nativeIds = if (malId != null) mapOf("mal" to malId) else emptyMap(),
    )
}
```

This pattern allows the `TenraiMetadataProvider` (which reads `nativeIds["mal"]`) to fetch additional metadata from Tenrai even when you don't have an AniList ID.

**Multiple native IDs:**

```kotlin
// Example: Source that knows both MAL and AniDB IDs
override val metadataDelegate = { ctx: MetaproviderContext ->
    val apiData = fetchFromSourceApi(ctx.animeUrl)

    ExtensionMetadata(
        title = apiData.title,
        description = apiData.description,
        thumbnailUrl = apiData.thumbnail,
        genre = apiData.genres.joinToString(),
        status = apiData.status,
        nativeIds = buildMap {
            apiData.malId?.let { put("mal", it) }
            apiData.anidbId?.let { put("anidb", it) }
            apiData.kitsuId?.let { put("kitsu", it) }
        },
    )
}
```

**Important:** When you provide `nativeIds` in the delegate, they are merged into the context for providers. The orchestrator will also populate IDs from the anime-offline-database if an AniList ID is available — your delegate's IDs take precedence for any keys you set. Providers can then read their specific ID from the map:

```kotlin
// In TenraiMetadataProvider:
val malId = context.nativeIds["mal"] ?: return ExtensionMetadata()
// Use malId to fetch from Tenrai...
```

---

Here's a complete extension setup with all providers and preferences:

```kotlin
class MyAnimeExtension : AnimeExtension() {
    override val identity = ExtensionMetadata(
        name = "MyAnimeSite",
        lang = "en",
        baseUrl = "https://myanime.site",
    )

    override val preferenceSchema = listOf(
        PreferenceEntry.List(
            key = "preferred_quality",
            title = "Preferred quality",
            summary = "%s",
            default = "720",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        ),
        PreferenceEntry.Switch(
            key = "mark_fillers",
            title = "Mark filler episodes",
            summary = "Mark filler episodes in the episode list",
            default = true,
        ),
        PreferenceEntry.Text(
            key = "api_key",
            title = "API Key",
            summary = "Your personal API key",
            default = "",
        ),
    )

    override val metadataSubProviders = listOf(
        LocalAnimeDatabaseProvider(priority = 0),
        TenraiMetadataProvider(priority = 10),
        KitsuMetadataProvider(priority = 15),
        AniLibMetadataProvider(priority = 20),
    )

    override val metadataDelegate = { ctx: MetaproviderContext ->
        // Seed with data from the source's API response
        ExtensionMetadata(
            title = sourceTitle,
            isNsfw = isAdultContent,
        )
    }

    // The orchestrator automatically:
    // 1. Downloads/caches anime-offline-database (7-day expiry)
    // 2. Resolves native IDs (MAL, Kitsu, AniDB) from AniList ID
    // 3. Populates context.nativeIds before providers run
    // 4. Runs delegate first, then providers in priority order
}
```

## Merge Strategy

Extensions control how providers merge metadata via `metadataMergeStrategy`. The default is `FILL_NULLS`.

```kotlin
class MyExtension : AnimeExtension() {
    override val identity = ExtensionMetadata(name = "MySite", lang = "en", baseUrl = "https://...")

    // Override the default merge strategy
    override val metadataMergeStrategy = MergeStrategy.OVERRIDE_NON_DELEGATE
    // ...
}
```

### Available Strategies

| Strategy | Delegate | Providers |
|---|---|---|
| `FILL_NULLS` (default) | Seeds metadata, always preserved | Fill only `null` gaps — first writer wins per field |
| `OVERRIDE_ALL` | Can be overridden by any provider | Last writer wins — each provider can overwrite any field |
| `OVERRIDE_NON_DELEGATE` | Fields locked, never overridden | Providers can override each other, but never the delegate |

### Examples

**FILL_NULLS** — safest, delegate always wins:
```
Delegate:    title="A", description=null
Provider 1:  title="B", description="Desc 1"
Provider 2:  title="C", description="Desc 2"
→ Result:    title="A", description="Desc 1"
```

**OVERRIDE_ALL** — last writer wins for everything:
```
Delegate:    title="A", description="Desc D"
Provider 1:  title="B", description="Desc 1"
Provider 2:  title="C", genre="Action"
→ Result:    title="C", description="Desc 1", genre="Action"
```

**OVERRIDE_NON_DELEGATE** — delegate locked, providers fight:
```
Delegate:    title="Locked", description="Desc D"
Provider 1:  title="X", genre="Action"
Provider 2:  title="Y", genre="Comedy"
→ Result:    title="Locked", description="Desc D", genre="Comedy"
```

### Per-Field Semantics

| Field in accumulator | Field in provider | `FILL_NULLS` | `OVERRIDE_ALL` |
|---|---|---|---|
| `"A"` | `"B"` | `"A"` (first wins) | `"B"` (last wins) |
| `null` | `"B"` | `"B"` (fill gap) | `"B"` (set value) |
| `"A"` | `null` | `"A"` (no change) | `"A"` (null = no override) |
| `null` | `null` | `null` | `null` |

Boolean fields (`isNsfw`, `supportsLatest`) use OR / false-wins semantics:
- `isNsfw`: `true` from any provider → `true` (once NSFW, always NSFW)
- `supportsLatest`: `false` from any provider → `false` (disable overrides enable)

## PreferenceEntry Variants

| Variant | Widget | Type | Maps to |
|---|---|---|---|
| `PreferenceEntry.Text` | EditTextPreference | `String` | `addEditTextPreference()` |
| `PreferenceEntry.List` | ListPreference | `String` | `addListPreference()` |
| `PreferenceEntry.Switch` | SwitchPreferenceCompat | `Boolean` | `addSwitchPreference()` |
| `PreferenceEntry.MultiSelect` | MultiSelectListPreference | `Set<String>` | `addSetPreference()` |

## Migration from Existing Extensions

This is an opt-in system. To migrate an existing extension:

1. Change parent class from `AnimeHttpSource` / `ParsedAnimeHttpSource` / `WcoTheme` / etc. to `AnimeExtension`
2. Move `name`, `lang`, `baseUrl` into an `ExtensionMetadata` object assigned to `identity`
3. Convert preference `const val` keys + manual `setupPreferenceScreen` calls into a `preferenceSchema` list
4. (Optional) Extract scraping/API metadata logic into `MetadataSubProvider` classes
5. (Optional) Add a `metadataDelegate` for any metadata known at construction time

The `extClass` manifest contract is preserved — `AnimeExtension` subclasses must have a usable no-arg constructor (same as `AnimeHttpSource` subclasses).

## What This Does NOT Change

- `PluginExtensionLegacy` — unchanged
- `GenerateKeepRulesTask` — unchanged
- All existing extensions and multisrc themes — unchanged
- `keiyoushi.utils.Source` — stays (deprecated, not removed)
