# AGENTS.md

@CONTRIBUTING.md

> **Import note:** `@CONTRIBUTING.md` is expanded automatically by agents that support `@`-imports (Claude Code, Gemini CLI, OpenCode, Pi, etc. – see [agents.md#faq](https://agents.md) and [pi#6291](https://github.com/earendil-works/pi/issues/6291)). If your harness does not expand it, read `CONTRIBUTING.md` directly – it is the source of truth. This file is a concise, agent-oriented summary.

## Project

Yuzono Anikku/Aniyomi anime extensions – Kotlin + Jsoup/OkHttp scrapers. Each extension is a Gradle module `src/<lang>/<source>`; shared CMS logic lives as themes in `lib-multisrc/<theme>`; shared helpers in `lib/` and `core/utils` (`keiyoushi.utils`).

## Commands

- Build single extension: `./gradlew src:<lang>:<source>:assembleDebug` (e.g. `./gradlew src:en:anizone:assembleDebug`)
- Build all: `./gradlew assembleDebug` (avoid – loads every module; prefer loading subset in `settings.gradle.kts`)
- Lint/format check: `./gradlew spotlessCheck` (CI) / `./gradlew spotlessApply` (local)
- Test/verify: compile via Android Studio before PR (required by `CONTRIBUTING.md:1306`)

## Structure

- `src/<lang>/<source>/build.gradle` – extension metadata (`extName`, `extClass`, `extVersionCode` **or** `themePkg`+`overrideVersionCode`, `isNsfw`)
- `src/<lang>/<source>/src/eu/kanade/tachiyomi/animeextension/<lang>/<source>/` – source code (package must match)
- `lib-multisrc/<theme>/build.gradle.kts` – theme base (`baseVersionCode`, `alias(kei.plugins.multisrc)`)
- `lib-multisrc/<theme>/src/.../multisrc/<theme>/` – abstract theme class `extends AnimeHttpSource`
- `lib/` – reusable libs (`lib-cookieinterceptor`, `lib-cryptoaes`, etc.)
- `core/utils` – `parseAs`, `toJsonRequestBody`, `tryParse`, `extractNextJs`, `absUrl` – use these, no custom JSON/regex/date helpers

## Conventions

See `CONTRIBUTING.md` for full rules. Critical for agents:

- Kotlin + Android; web scraping via CSS selectors, OkHttp, Jsoup.
- Do not use `data class` for `@Serializable` DTOs unless needed; camelCase fields, `@SerialName` only when JSON key differs.
- Use `response.parseAs<T>()`, `response.asJsoup()`, `SimpleDateFormat(...).tryParse()`, `element.absUrl("href")` + `setUrlWithoutDomain()`.
- No hardcoded `User-Agent`, no `Thread.sleep()`, no manual Cloudflare checks, no `buildJsonObject` for requests.
- Preserve `id` when renaming `name`/`lang`; keep package name stable.

## Versioning – bump once per PR, theme bump propagates

Source of truth: `CONTRIBUTING.md:286-308` (individual) + `CONTRIBUTING.md:1061-1117` (themes) + `gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt:61`:

```kotlin
versionCode = if (theme == null) extVersionCode else theme.baseVersionCode + overrideVersionCode
```

Rules:

- Individual extension (no theme): increment `extVersionCode` by **1** if code affecting users changed. Bump **once per PR** – do not increment multiple times across commits.
- Theme (`lib-multisrc/<theme>/build.gradle.kts`): increment `baseVersionCode` by **1** when theme logic changes.
- When `baseVersionCode` is bumped, **do not** bump `overrideVersionCode` for extensions using that theme in the same PR – the addition already bumps every extension's effective `versionCode`. Only bump `overrideVersionCode` when the individual extension itself changed independently of the theme.
- Checklist mirrors this: `CONTRIBUTING.md:1311-1314` and `.github/pull_request_template.md:3-4`.

## Boundaries

- Do not change `versionName` manually (generated `14.<versionCode>`).
- Do not commit `web_hi_res_512.png` (delete after Icon Generator).
- Do not push to `upstream` (`no_pushing` – fork workflow `CONTRIBUTING.md:168-196`). Use `origin` (your fork) for PRs.
- Never commit secrets, keystore, or `local.properties`.

## PR Instructions

- Follow `CONTRIBUTING.md:1290-1321` checklist; test build in Android Studio.
- Title: keep concise; reference issues (`Closes #xyz`).
- One version bump per module per PR as above.
