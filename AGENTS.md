# AGENTS.md

@CONTRIBUTING.md

> **Import note:** `@CONTRIBUTING.md` is expanded automatically by agents that support `@`-imports (Claude Code, Gemini CLI, OpenCode, Pi, etc. – see [agents.md#faq](https://agents.md) and [pi#6291](https://github.com/earendil-works/pi/issues/6291)). If your harness does not expand it, read `CONTRIBUTING.md` directly – it is the source of truth. This file is a concise, agent-oriented summary.

## Project

Yuzono Anikku/Aniyomi anime extensions – Kotlin + Jsoup/OkHttp scrapers. Each extension is a Gradle module `src/<lang>/<source>`; shared CMS logic lives as themes in `lib-multisrc/<theme>`; shared helpers in `lib/` and `core` (`keiyoushi.utils`, `keiyoushi.network`).

## Commands

**Always scope a task to a module.** The build has ~330 modules (250 extensions + 73 libs + 9 themes);
an unscoped `./gradlew <task>` configures every one of them.

- Verify a change (fast – no APK link/package step): `./gradlew :src:<lang>:<source>:compileDebugKotlin`
  (e.g. `./gradlew :src:en:anizone:compileDebugKotlin`, `./gradlew :lib-multisrc:anikototheme:compileDebugKotlin`)
- Build an installable APK: `./gradlew :src:<lang>:<source>:assembleDebug`
- Lint/format: `./gradlew :<module>:spotlessCheck` (CI) / `:<module>:spotlessApply` (local)
- Build all: `./gradlew assembleDebug` – avoid; narrow `settings.gradle.kts` instead (see below)

Module paths are colon-separated and mirror the directory layout: `:src:en:miruro`,
`:lib-multisrc:anikototheme`, `:core`.

### Tests

The repo's only unit tests are in `core/src/test/kotlin/keiyoushi/utils/`, `lib/unpacker/test/kotlin/`
and `lib/megaextractor/test/`. Extensions have none – they are verified by compiling and running
against the live site (`CONTRIBUTING.md#submitting-the-changes` ~ `1435`).

```bash
./gradlew --continue :core:testDebugUnitTest :lib:unpacker:testDebugUnitTest :lib:megaextractor:testDebugUnitTest
./gradlew :core:testDebugUnitTest --tests 'keiyoushi.utils.UrlUtilsTest'
```

### Loading a subset of modules

`settings.gradle.kts` calls `loadAllIndividualExtensions()`, which includes every extension under
`src/`. To work on a few, comment it out and list them instead – do not commit the narrowed file:

```kotlin
// loadAllIndividualExtensions()
loadIndividualExtension("en", "miruro")
```

`lib/` and `lib-multisrc/` are always loaded wholesale.

## Structure

- `src/<lang>/<source>/build.gradle.kts` – extension metadata in the `yuzono { }` block (`name`, `className`, `versionCode`, `contentWarning`, `libVersion`, optional `torrent`, `deeplink { }`, plus `theme`/`baseUrl` for themed extensions)
- `src/<lang>/<source>/src/eu/kanade/tachiyomi/animeextension/<lang>/<source>/` – source code (package must match)
- `lib-multisrc/<theme>/build.gradle.kts` – theme base (`alias(kei.plugins.multisrc)`, `yuzono { baseVersionCode; libVersion; deeplink { } }`)
- `lib-multisrc/<theme>/src/.../multisrc/<theme>/` – abstract theme class `extends AnimeHttpSource`
- `lib/` – reusable libs (`lib-cryptoaes`, `lib-playlistutils`, extractors, etc.)
- `core` – `keiyoushi.utils` (`parseAs`, `toJsonRequestBody`, `tryParse`, `extractNextJs`, `absUrl`) and `keiyoushi.network` (`rateLimit`, `addCookie`, `OkHttpClient.get`/`post`) – use these, no custom JSON/regex/date/interceptor helpers

## Conventions

See `CONTRIBUTING.md` for full rules. Critical for agents:

- Check `lib/` and `core` (`keiyoushi.utils` for parsing/prefs, `keiyoushi.network` for clients) first – reuse existing libs/helpers instead of custom boilerplate (`parseAs`/`toJsonRequestBody`/`tryParse`/`extractNextJs`/`absUrl`, `rateLimit`/`addCookie`, `lib-cryptoaes`/etc.). See `CONTRIBUTING.md#core-dependencies`.
- Kotlin + Android; web scraping via CSS selectors, OkHttp, Jsoup.
- Do not use `data class` for `@Serializable` DTOs unless needed; camelCase fields, `@SerialName` only when JSON key differs.
- Use `response.parseAs<T>()`, `response.asJsoup()`, `SimpleDateFormat(...).tryParse()`, `element.absUrl("href")` + `setUrlWithoutDomain()`.
- No hardcoded `User-Agent`, no `Thread.sleep()`, no manual Cloudflare checks, no `buildJsonObject` for requests.
- Preserve `id` when renaming `name`/`lang`; keep package name stable.
- New or migrated extensions target extensions-lib 16: extend `AnimeHttpSource` directly (not the `keiyoushi.utils.AnimeHttpLegacySource`/`ParsedAnimeHttpLegacySource` shims), implement hosters (`getHosterList`/`getVideoList(hoster)`), use the named `Video(videoUrl =, videoTitle =, ...)` constructor, and set `libVersion = "16"` in `build.gradle.kts`. See `CONTRIBUTING.md#extensions-lib-16`.

### `keiyoushi.network` client helpers

- `rateLimit(permits, period, interval) { url -> Boolean }` replaces the deprecated
  `eu.kanade.tachiyomi.network.interceptor.rateLimit`/`rateLimitHost`. The predicate receives an
  `HttpUrl`, so compare against a **host** (`it.host == "api.example.com"`), never a full URL.
- `addCookie(...)` replaces the removed `lib-cookieinterceptor`.
- `OkHttpClient.get`/`post`/`put`/`head` are suspend helpers; `get`/`head` default to a 10-minute
  `CacheControl`, and all default to `ensureSuccess = true` (throws on non-2xx).
- Many overloads use Kotlin **context parameters** (`context(source: AnimeHttpSource)`). Inside a
  class that *is* an `AnimeHttpSource` the implicit receiver satisfies them, so `client.get(url)` and
  `.addCookie("k" to "v")` work with no `with(...)` wrapper and pick up `headers`/`baseUrl`.
- A source whose `baseUrl` is preference-backed must rebuild everything derived from it when the
  preference changes. Use `keiyoushi.utils.LazyMutable` (a read/write lazy delegate) for `client`,
  `headers` and anything built from them, and reassign them all in the `baseUrl` setter – a plain
  `by lazy` keeps serving the stale client. See `lib-multisrc/anikototheme/.../AnikotoTheme.kt`.
- Do not eagerly initialize `client` in a theme base class: the initializer runs before subclass
  constructors, so an overridden `open val rateLimit` reads `0`.
- Do not create `android.*` objects (e.g. `LruCache`) in a source's constructor or property
  initializers – use `by lazy`. The repo publisher instantiates sources on a plain JVM where they
  throw `Stub!`, which blocks the publish for every extension. See `CONTRIBUTING.md#misc-notes`.

## Referring to commits

When you mention a commit while talking to the user (chat replies, summaries, reports), give its short hash followed by
its subject in parentheses, with no quotes, like `git log --format=reference`. Shorten long subjects with `…`, and drop
a trailing PR reference such as `(#17034)` so the parentheses don't nest: `d239a86987` (small cleanup in build logic),
`8573282cf4` (Set output apk filename with variant api…). A bare hash can't be recognized at a glance. This is about
conversation only; it doesn't apply to commit messages or PR descriptions.

## Porting upstream changes

Build logic, CI, `core` and docs are mostly cherry-picked from the manga repos: `yuzono/tachiyomi-extensions` (a copy of
`keiyoushi/extensions-source`) and `Secozzi/aniyomi-extensions` – see the `cherrypick-log` skill. Pick with
`git cherry-pick -x` so the message keeps its `(cherry picked from commit …)` line, and qualify the upstream PR reference
in the subject – `(keiyoushi/extensions-source#16992)`, not a bare `(#16992)`, which GitHub would link to this repo's own
PR. Keep upstream's code and layout so later picks still apply, and change only what is manga- or upstream-specific:

- **Manga → anime:** `eu.kanade.tachiyomi.extension` → `eu.kanade.tachiyomi.animeextension` (package, namespace,
  `tachiyomi.animeextension.*` meta-data, icon names); `Source`/`HttpSource`/`SManga`/`SChapter`/`fetchSearchManga`
  → `AnimeSource`/`AnimeHttpSource`/`SAnime`/`SEpisode`/`getSearchAnime`; intent action `eu.kanade.tachiyomi.SEARCH`
  → `eu.kanade.tachiyomi.ANIMESEARCH`; manga wording in docs → anime/episode wording.
- **keiyoushi/yuzono manga → Aniyomi:** `Tachiyomi: <name>` / `tachiyomi-<lang>.<ext>-v….apk` → `Aniyomi: <name>` /
  `aniyomi-…`; keiyoushi or manga-repo URLs, store name, badge and signing key → this repo's (`yuzono/anime-repo`,
  `Yūzōnō`); the Gradle DSL block is `yuzono { }` here, `keiyoushi { }` upstream.
- **Versions:** `libVersion` is `"14"`/`"16"` (versionName `<lib>.<code>`), not upstream's `"1.4"`; drop KMK-only
  fields (`kmkVersionCode`, `// KMK -->` blocks).
- **Index schema:** `.github/scripts/index_pb2.py` follows the Aniyomi spec (`aniyomiorg/extensions-lib`
  `index/index.proto`), not Mihon's – `Extension.isTorrent = 8`, `sources = 9`, `Source.message = 6`. Keep these
  numbers when a pick regenerates the bindings.
- **CI:** `build_pull_request.yml` has an anime-only `publish` job ("Check extension repo") that runs `create-repo.py`
  and `merge-repo.py` the same way `build_push.yml` does. Upstream has no such job, so mirror into it any dependency or
  argument change a pick makes to `build_push.yml`.

**Torrent is the exception:** it is anime-only and has no manga counterpart, so no upstream change carries it – keep it
when resolving conflicts and add it to any rewritten script. `torrent = true` in `yuzono { }` → the
`tachiyomi.animeextension.torrent` meta-data (Anikku's `ExtensionLoader`) → `torrent` in `index.min.json` (Anikku's
`ExtensionApi`) and `isTorrent` in `index.json`/`index.pb`.

**A pick commit holds only that upstream change**, plus its `O` row in `cherrypick_log.md`. Uncommitted changes that
are already in the tree when you pick, such as notes added to `cherrypick_log.md` or work on another file, stay out of
it, staged or not:
1. Read the commit's row first, Notes included. Then stash those changes with
   `git stash push -m "unrelated to <hash>"`. `git cherry-pick` refuses to start with staged changes, and a dirty
   `cherrypick_log.md` would otherwise be committed along with the row.
2. Stage only the pick's own files: `git add <path>` for each resolved file. Never use `git add -A`, `git add -u` or
   `git commit -a` during a pick.
3. After the commit, or as soon as you stop without one, restore your changes with `git stash pop`. If the pick is
   still in progress, run `git cherry-pick --abort` first: `git stash pop` fails while it has unresolved conflicts.
   If the pop conflicts, keep both sides, leave the result unstaged and `git stash drop`. A note on the picked row and
   its new `O` mark conflict like this.

Untracked files can stay where they are, unless the pick adds a file at the same path.

## Versioning – bump once per PR, theme bump propagates

Source of truth: `CONTRIBUTING.md:281-310` (individual) + `CONTRIBUTING.md:1179-1255` (themes) + `gradle/build-logic/src/main/kotlin/PluginExtension.kt:124-132`:

```kotlin
val versionCodeProvider = themeExtension.flatMap { themeKeiyoushi ->
    // ...
    themeKeiyoushi.baseVersionCode.zip(keiyoushi.versionCode) { base, ext -> base + ext }
}.orElse(keiyoushi.versionCode)
```

Rules:

- Individual extension (no theme): increment `versionCode` by **1** if code affecting users changed. Bump **once per PR** – do not increment multiple times across commits.
- Theme (`lib-multisrc/<theme>/build.gradle.kts`): increment `baseVersionCode` by **1** when theme logic changes.
- When `baseVersionCode` is bumped, **do not** bump `versionCode` for extensions using that theme in the same PR – the addition already bumps every extension's effective version code. Only bump the extension's `versionCode` when the extension itself changed independently of the theme.
- Checklist mirrors this: `CONTRIBUTING.md#pull-request-checklist` ~ `1458-1459` and `.github/pull_request_template.md:3-4`.

## CI – what it bumps and builds for you

From `.github/workflows/build_pull_request.yml` + `.github/scripts/`:

- Changing anything under `lib/` makes CI run `bump-versions.py`, which **auto-bumps every dependent
  extension**. Do not bump those by hand – only bump the module you actually edited.
- CI builds only the changed modules (`generate-build-matrices.py` → `<module>:assembleDebug`), never
  the whole tree.
- Head-commit message prefixes: `[skip bumping version]` skips the auto-bump; `[skip yuzono ci]` skips
  the bump *and* the build.

## Boundaries

- Do not change `versionName` manually (generated `<libVersion>.<versionCode>`; set `libVersion = "14"` unless the extension uses the lib 16 API, and never lower than its theme's `libVersion`).
- Do not commit `web_hi_res_512.png` (delete after Icon Generator).
- Do not push to `upstream` (`no_pushing` – fork workflow `CONTRIBUTING.md:171-203`). Use `origin` (your fork) for PRs.
- Never commit secrets, keystore, or `local.properties`.

## PR Instructions

- Follow `CONTRIBUTING.md#submitting-the-changes` ~ `1435-1466` checklist; test build in Android Studio.
- Title: keep concise; reference issues (`Closes #xyz`).
- One version bump per module per PR as above.
