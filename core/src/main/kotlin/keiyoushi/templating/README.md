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
  PreferenceEntry.kt         # Sealed interface: EditTextPreference / ListPreference / SwitchPreferenceCompat / MultiSelectListPreference
  PreferenceRegistry.kt       # Auto-managed schema → typed reads + UI + persistence
  CommonPreferences.kt       # Factory methods for common preference patterns

  # Utility Mixins (implement on extensions for common patterns)
  MirrorSupport.kt           # Multi-domain/mirror site support
  ServerToggle.kt            # Video server/hoster selection
  BilingualTitle.kt          # English/Romaji title language toggle
  TypeToggle.kt              # Sub/Dub/Raw video type selection

  # Utility Functions (standalone helpers)
  VideoUtils.kt              # Video sorting and filtering
  ScoreDisplay.kt            # Star rating display (★★★★☆)
  StatusUtils.kt             # Anime status parsing (Ongoing/Completed)
  ElementExtensions.kt       # Jsoup Element helpers (getImageUrl, getInfo)

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

    Utility Mixins (opt-in via interface implementation):
    ┌──────────────┬──────────────┬──────────────┬──────────────┐
    │ MirrorSupport│ ServerToggle │ BilingualTitle│  TypeToggle  │
    │ (domains)    │ (hosters)    │ (lang)       │ (sub/dub)    │
    └──────────────┴──────────────┴──────────────┴──────────────┘

    Standalone Utilities:
    ┌──────────────┬──────────────┬──────────────┬──────────────┐
    │  VideoUtils  │ ScoreDisplay │ StatusUtils  │ElementExt.   │
    │ (sort/filter)│ (★★★★☆)     │ (ongoing)    │ (getImageUrl)│
    └──────────────┴──────────────┴──────────────┴──────────────┘
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
    PreferenceEntry.ListPreference(
        key = "preferred_quality",
        title = "Preferred quality",
        summary = "%s",
        default = "720",
        entries = listOf("1080p", "720p", "480p"),
        entryValues = listOf("1080", "720", "480"),
    ),
    PreferenceEntry.SwitchPreferenceCompat(
        key = "mark_fillers",
        title = "Mark filler episodes",
        summary = "Mark filler episodes in the episode list",
        default = true,
    ),
)
```

That's it. The template auto-generates:
- **UI** — `setupPreferenceScreen()` is already implemented. It calls the existing `keiyoushi.utils.add*Preference` helpers.
- **Typed reads** — `getString("preferred_quality")` returns the value without casting.
- **Persistence** — reads/writes go through `SharedPreferences` via the existing `PreferenceDelegate` mechanism.

Reading a preference:

```kotlin
val quality = getString("preferred_quality", "720")
val markFillers = getBoolean("mark_fillers")
val port = getInt("port", 8080)
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

**Reverse ID Resolution:** If a source only provides a native ID (MAL or Kitsu) without an AniList ID, the orchestrator automatically looks up the corresponding AniList ID via the database's reverse index. This allows the full provider chain to work even when starting from a MAL or Kitsu ID:

```kotlin
// Source provides only MAL ID → orchestrator resolves AniList ID automatically
val context = MetaproviderContext(
    baseUrl = "https://example.com",
    nativeIds = mapOf("mal" to 100),
)
// After orchestration:
// context.anilistId = 12345  (resolved from MAL ID)
// context.nativeIds["kitsu"] = 200  (resolved from AniList ID)
// context.nativeIds["anidb"] = 300  (resolved from AniList ID)
```

Providers that use a different ID space read their native ID from `context.nativeIds`:

```kotlin
// Convenience accessors available
val malId = context.getMalId()          // reads nativeIds["mal"]
val kitsuId = context.getKitsuId()      // reads nativeIds["kitsu"]
val anidbId = context.getAnidbId()      // reads nativeIds["anidb"]
```

Key convention: `"mal"` for MyAnimeList, `"kitsu"` for Kitsu, `"anidb"` for AniDB, etc.

### MetaproviderContext Builder

For complex context construction, use the Builder pattern:

```kotlin
val context = MetaproviderContext.builder("https://example.com")
    .anilistId(12345)
    .nativeId("mal", 100)
    .animeUrl("/anime/123")
    .httpClient(client)
    .headers(headers)
    .document(doc)
    .preferences(preferences)
    .context(appContext)
    .extra("customKey", customValue)
    .build()
```

Or use the factory method:

```kotlin
val context = MetaproviderContext.fromAnime(
    baseUrl = "https://example.com",
    animeUrl = "/anime/123",
    httpClient = client,
    headers = headers,
    preferences = preferences,
    context = appContext,
)
```

#### Built-in Providers

| Provider | Priority | ID Source | Description |
|---|---|---|---|
| `LocalAnimeDatabaseProvider` | 0 | `anilistId` or `nativeIds["mal"]`/`nativeIds["kitsu"]` | Reads from cached anime-offline-database (no network). Resolves AniList ID from native IDs when needed. |
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

The simplest approach uses the convenience method that builds the context for you:

```kotlin
override suspend fun getAnimeDetails(anime: SAnime): SAnime {
    val response = client.newCall(animeDetailsRequest(anime)).await()
    val document = response.asJsoup()

    val meta = resolveMetadataFor(anime, document = document)
    return meta.toSAnimeFallback(anime)
}
```

For full control, build the context manually:

```kotlin
override suspend fun getAnimeDetails(anime: SAnime): SAnime {
    val response = client.newCall(animeDetailsRequest(anime)).await()
    val document = response.asJsoup()

    val context = MetaproviderContext.builder(baseUrl)
        .anilistId(anilistIdFromUrl(anime.url))
        .animeUrl(anime.url)
        .httpClient(client)
        .headers(headers)
        .document(document)
        .preferences(preferences)
        .context(context)
        .build()
    val meta = resolveMetadata(context)

    return meta.applyToAnime(anime)
}
```

## Full Example

### When No IDs Are Available

The provider chain depends on IDs to look up external metadata. If a source has **no AniList, MAL, or Kitsu ID**, every built-in provider returns empty metadata silently. The only metadata path is the delegate.

| Provider | Required ID | Returns when missing |
|---|---|---|
| `LocalAnimeDatabaseProvider` | `anilistId` or `nativeIds["mal"]`/`nativeIds["kitsu"]` | empty |
| `TenraiMetadataProvider` | `nativeIds["mal"]` | empty |
| `KitsuMetadataProvider` | `nativeIds["kitsu"]` | empty |
| `AniLibMetadataProvider` | `anilistId` | empty |

Sources that only have a MAL ID (but no AniList ID) can still use `LocalAnimeDatabaseProvider` and `TenraiMetadataProvider` — the orchestrator automatically resolves the AniList ID from the MAL ID, then populates all other native IDs. Similarly, sources with no external IDs at all should populate metadata entirely from their own API response via the delegate:

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

The `ExtensionMetadata.nativeIds` map is a `Map<String, Int>` that carries provider-specific IDs (MAL, Kitsu, AniDB, etc.). The orchestrator automatically populates the context's native IDs when an AniList ID is available, and also resolves the AniList ID when only a native ID is provided. When a source only has a different ID type, you can include the IDs in your delegate's returned metadata — the orchestrator will use them to resolve the AniList ID and populate other native IDs automatically.

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

This pattern allows the orchestrator to automatically resolve the AniList ID from the MAL ID, then populate all other native IDs (Kitsu, AniDB) from the database. The `LocalAnimeDatabaseProvider` and `TenraiMetadataProvider` will then have access to the full set of IDs.

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

**Important:** When you provide `nativeIds` in the delegate, they are merged into the context for providers. The orchestrator will also populate IDs from the anime-offline-database if an AniList ID is available — your delegate's IDs take precedence for any keys you set. If no AniList ID is provided but a MAL or Kitsu ID is present, the orchestrator automatically resolves the AniList ID from the database's reverse index, then populates all other native IDs. Providers can then read their specific ID from the map:

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
        PreferenceEntry.ListPreference(
            key = "preferred_quality",
            title = "Preferred quality",
            summary = "%s",
            default = "720",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        ),
        PreferenceEntry.SwitchPreferenceCompat(
            key = "mark_fillers",
            title = "Mark filler episodes",
            summary = "Mark filler episodes in the episode list",
            default = true,
        ),
        PreferenceEntry.EditTextPreference(
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
    // 2. Resolves AniList ID from native IDs if needed (MAL/Kitsu → AniList)
    // 3. Resolves native IDs (MAL, Kitsu, AniDB) from AniList ID
    // 4. Populates context.nativeIds before providers run
    // 5. Runs delegate first, then providers in priority order
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
| `PreferenceEntry.EditTextPreference` | EditTextPreference | `String` | `addEditTextPreference()` |
| `PreferenceEntry.ListPreference` | ListPreference | `String` | `addListPreference()` |
| `PreferenceEntry.SwitchPreferenceCompat` | SwitchPreferenceCompat | `Boolean` | `addSwitchPreference()` |
| `PreferenceEntry.MultiSelectListPreference` | MultiSelectListPreference | `Set<String>` | `addSetPreference()` |

## Common Preferences

Use `CommonPreferences` factory methods to create frequently-used preferences with sensible defaults:

```kotlin
override val preferenceSchema = listOf(
    CommonPreferences.quality(),           // preferred_quality
    CommonPreferences.server(              // preferred_server
        default = "Server1",
        entries = listOf("Server1", "Server2"),
    ),
    CommonPreferences.domain(              // preferred_domain
        default = "site1.com",
        entries = listOf("site1.com", "site2.com"),
        entryValues = listOf("https://site1.com", "https://site2.com"),
    ),
    CommonPreferences.hosterSelection(),   // hoster_selection
    CommonPreferences.titleLanguage(),     // preferred_title_lang
    CommonPreferences.typeToggle(),        // preferred_type
    CommonPreferences.markFillers(),       // mark_fillers
)
```

## Utility Mixins

Mixins provide reusable behavior via interface implementation. Add them to your extension class to get standard patterns for free.

### MirrorSupport

For extensions supporting multiple mirror sites/domains:

```kotlin
class MyExtension : AnimeExtension(), MirrorSupport {
    override val identity = ExtensionMetadata(
        name = "MySite",
        lang = "en",
        baseUrl = "https://site1.com",  // default
    )

    override val mirrorSupportEntries = listOf("site1.com", "site2.com", "site3.com")
    override val mirrorSupportDefault = "site1.com"

    override fun onMirrorChanged(newDomain: String) {
        // Rebuild client with new rate-limit host
        client = network.client.newBuilder()
            .rateLimitHost(newDomain.toHttpUrl(), permits = 1, period = 1L, TimeUnit.SECONDS)
            .build()
    }
}
```

### ServerToggle

For extensions with video server/hoster selection:

```kotlin
class MyExtension : AnimeExtension(), ServerToggle {
    override val serverToggleEntries = listOf("Server1", "Server2", "Server3")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val allVideos = fetchVideosFromSource(episode)
        return filterVideos(allVideos)  // Auto-filters by enabled servers
    }
}
```

### BilingualTitle

For extensions with English/Romaji title toggle:

```kotlin
class MyExtension : AnimeExtension(), BilingualTitle {
    override fun popularAnimeFromElement(element: Element): SAnime {
        val enTitle = element.selectFirst(".en-title")?.text()
        val jpTitle = element.selectFirst(".jp-title")?.text()
        return SAnime.create().apply {
            title = selectTitle(enTitle, jpTitle)  // Auto-selects based on preference
        }
    }
}
```

### TypeToggle

For extensions with Sub/Dub/Raw type selection:

```kotlin
class MyExtension : AnimeExtension(), TypeToggle {
    override fun videoListParse(response: Response): List<Video> {
        val allVideos = parseAllVideos(response)
        val type = getPreferredType()  // Returns "Sub", "Dub", etc.
        return allVideos.filter { it.quality.contains(type, ignoreCase = true) }
    }
}
```

## Standalone Utilities

### VideoUtils

```kotlin
import keiyoushi.templating.sortByQuality
import keiyoushi.templating.filterByExcludedServers
import keiyoushi.templating.deduplicate
import keiyoushi.templating.filterAndSort

// Sort by quality preference
val sorted = videos.sortByQuality("1080")

// Filter by excluded servers
val filtered = videos.filterByExcludedServers(setOf("BadServer1", "BadServer2"))

// Remove duplicate videos (same quality + URL)
val unique = videos.deduplicate()

// All-in-one: exclude servers, filter by type, deduplicate, sort
val result = videos.filterAndSort(
    preferredQuality = "1080",
    excludedServers = setOf("SlowServer"),
    allowedTypes = setOf("Sub", "Dub"),
    deduplicate = true,
)
```

### ScoreDisplay

```kotlin
import keiyoushi.templating.toStarRating

val score = "8.5"
val display = score.toStarRating()  // "★★★★☆ 8.5"
val starsOnly = score.toStarsOnly()  // "★★★★☆"
```

### StatusUtils

```kotlin
import keiyoushi.templating.parseAnimeStatus
import keiyoushi.templating.toStatusString
import keiyoushi.templating.isOngoing
import keiyoushi.templating.isCompleted

// Parse from string (supports 8+ languages)
val status = "Currently Airing".parseAnimeStatus()  // SAnime.ONGOING
val status2 = "完了".parseAnimeStatus()             // SAnime.COMPLETED
val status3 = "em andamento".parseAnimeStatus()     // SAnime.ONGOING

// Convert back to display string
val label = SAnime.ONGOING.toStatusString()  // "Ongoing"

// Quick status checks (extension functions on Int)
if (status.isOngoing()) { /* ... */ }
if (status.isCompleted()) { /* ... */ }
if (status.isAired()) { /* COMPLETED or ONGOING */ }
if (status.isActive()) { /* ONGOING or ON_HIATUS */ }
```

### ElementExtensions

```kotlin
import keiyoushi.templating.getImageUrl
import keiyoushi.templating.getInfo
import keiyoushi.templating.extractMetadata
import keiyoushi.templating.getOpenGraphData

// Image extraction (handles lazy-loading, srcset, data-src)
val thumbnail = element.selectFirst("img")?.getImageUrl()
val allImages = element.getImageUrls()

// Info extraction from details pages
val status = infoElement.getInfo("Status:")  // "Ongoing"
val genres = infoElement.getInfoList("Genre:")

// Bulk metadata extraction
val metadata = element.extractMetadata()  // map of Status, Genre, Type, etc.

// Open Graph / meta tags
val ogTitle = element.getOpenGraphData("title")
val metaDesc = element.getMetaContent("description")
```

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

## Robustness & Error Handling

The framework is designed to be resilient to failures in providers, delegates, and external data sources.

### Provider Error Isolation

If a `MetadataSubProvider` throws an exception, it is caught, logged, and returns empty metadata. The remaining providers continue normally:

```kotlin
// Provider 1 crashes → logged, skipped
// Provider 2 runs normally → its metadata is still merged
override val metadataSubProviders = listOf(
    FlakyProvider(priority = 10),  // if this throws, chain continues
    ReliableProvider(priority = 20),
)
```

### Delegate Error Isolation

If the `metadataDelegate` throws, it returns empty metadata and providers proceed as if no delegate was set.

### Metadata Caching

`MetadataProvider` caches resolved metadata for 5 minutes per AniList ID (or nativeIds hash). Repeated calls to `resolveMetadata()` for the same anime skip the full provider chain. Call `metadataProvider.clearCache()` to invalidate.

### Lifecycle Hooks

`AnimeExtension` provides overridable hooks for initialization and cleanup:

```kotlin
class MyExtension : AnimeExtension() {
    override fun onInit() {
        // Called once when extension is created
        // Setup database connections, load configs, etc.
    }

    override fun onDestroy() {
        // Called when extension is torn down
        // Close connections, cancel jobs, etc.
    }

    override fun onPreferenceChanged(key: String, newValue: Any?): Boolean {
        // Called when user changes a preference
        // Return false to reject the change
        // Return true (default) to accept
        return true
    }
}
```

### ExtensionMetadata Convenience Methods

`ExtensionMetadata` provides direct conversion to `SAnime`:

```kotlin
val meta = resolveMetadataFor(anime)

// Create new SAnime from metadata (null fields become empty strings)
val newAnime = meta.toSAnime()

// Create new SAnime with fallback to another anime for null fields
val mergedAnime = meta.toSAnimeFallback(anime)

// Apply metadata to an existing SAnime (null fields preserve original)
meta.applyToAnime(anime)
```
