# AGENTS.md

@CONTRIBUTING.md

> **Import note:** `@CONTRIBUTING.md` is expanded automatically by agents that support `@`-imports (Claude Code, Gemini CLI, OpenCode, Pi, etc. – see [agents.md#faq](https://agents.md) and [pi#6291](https://github.com/earendil-works/pi/issues/6291)). If your harness does not expand it, read `CONTRIBUTING.md` directly – it is the source of truth. This file is a concise, agent-oriented summary.

## Project

Yuzono Anikku/Aniyomi anime extensions – Kotlin + Jsoup/OkHttp scrapers. Each extension is a Gradle module `src/<lang>/<source>`; shared CMS logic lives as themes in `lib-multisrc/<theme>`; shared helpers in `lib/` and `core` (`keiyoushi.utils`, `keiyoushi.network`).

## Commands

**Always scope a task to a module.** The build has ~400 modules (307 extensions + 79 libs + 12 themes);
an unscoped `./gradlew <task>` configures every one of them.

- Verify a change (fast – no APK link/package step): `./gradlew :src:<lang>:<source>:compileDebugKotlin`
  (e.g. `./gradlew :src:en:anizone:compileDebugKotlin`, `./gradlew :lib-multisrc:anikototheme:compileDebugKotlin`)
- Build an installable APK: `./gradlew :src:<lang>:<source>:assembleDebug`
- Lint/format: `./gradlew :<module>:spotlessCheck` (CI) / `:<module>:spotlessApply` (local)
- Build all: `./gradlew assembleDebug` – avoid; narrow `settings.gradle.kts` instead (see below)

Module paths are colon-separated and mirror the directory layout: `:src:en:miruro`,
`:lib-multisrc:anikototheme`, `:core`.

### Tests

`core/src/test/kotlin/keiyoushi/utils/` holds the repo's only unit tests (`NextJsTest`, `UrlUtilsTest`).
Extensions have none – they are verified by compiling and running against the live site
(`CONTRIBUTING.md#submitting-the-changes` ~ `1369`).

```bash
./gradlew :core:testDebugUnitTest --tests 'keiyoushi.utils.UrlUtilsTest'
```

Known-broken: `:core:compileDebugUnitTestKotlin` fails – the tests import `kotlin.test.*` but
`core/build.gradle.kts` only declares `testImplementation(libs.junit)`. Adding a `kotlin-test`
dependency fixes it; until then the test task cannot run.

### Loading a subset of modules

`settings.gradle.kts` calls `loadAllIndividualExtensions()`, which includes every extension under
`src/`. To work on a few, comment it out and list them instead – do not commit the narrowed file:

```kotlin
// loadAllIndividualExtensions()
loadIndividualExtension("en", "miruro")
```

`lib/` and `lib-multisrc/` are always loaded wholesale.

## Structure

- `src/<lang>/<source>/build.gradle` – extension metadata (`extName`, `extClass`, `extVersionCode` **or** `themePkg`+`overrideVersionCode`, `isNsfw`)
- `src/<lang>/<source>/src/eu/kanade/tachiyomi/animeextension/<lang>/<source>/` – source code (package must match)
- `lib-multisrc/<theme>/build.gradle.kts` – theme base (`baseVersionCode`, `alias(kei.plugins.multisrc)`)
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

## Versioning – bump once per PR, theme bump propagates

Source of truth: `CONTRIBUTING.md:287-309` (individual) + `CONTRIBUTING.md:1124-1176` (themes) + `gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt:61`:

```kotlin
versionCode = if (theme == null) extVersionCode else theme.baseVersionCode + overrideVersionCode
```

Rules:

- Individual extension (no theme): increment `extVersionCode` by **1** if code affecting users changed. Bump **once per PR** – do not increment multiple times across commits.
- Theme (`lib-multisrc/<theme>/build.gradle.kts`): increment `baseVersionCode` by **1** when theme logic changes.
- When `baseVersionCode` is bumped, **do not** bump `overrideVersionCode` for extensions using that theme in the same PR – the addition already bumps every extension's effective `versionCode`. Only bump `overrideVersionCode` when the individual extension itself changed independently of the theme.
- Checklist mirrors this: `CONTRIBUTING.md#pull-request-checklist` ~ `1376-1377` and `.github/pull_request_template.md:3-4`.

## CI – what it bumps and builds for you

From `.github/workflows/build_pull_request.yml` + `.github/scripts/`:

- Changing anything under `lib/` makes CI run `bump-versions.py`, which **auto-bumps every dependent
  extension**. Do not bump those by hand – only bump the module you actually edited.
- CI builds only the changed modules (`generate-build-matrices.py` → `<module>:assembleDebug`), never
  the whole tree.
- Head-commit message prefixes: `[skip bumping version]` skips the auto-bump; `[skip yuzono ci]` skips
  the bump *and* the build.

## Boundaries

- Do not change `versionName` manually (generated `14.<versionCode>`).
- Do not commit `web_hi_res_512.png` (delete after Icon Generator).
- Do not push to `upstream` (`no_pushing` – fork workflow `CONTRIBUTING.md:170-196`). Use `origin` (your fork) for PRs.
- Never commit secrets, keystore, or `local.properties`.

## PR Instructions

- Follow `CONTRIBUTING.md#submitting-the-changes` ~ `1353-1384` checklist; test build in Android Studio.
- Title: keep concise; reference issues (`Closes #xyz`).
- One version bump per module per PR as above.
