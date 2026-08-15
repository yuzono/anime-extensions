# Implementation Plan: Nekopoi Anime Extension (Issue #94)

## Overview
Implement the Nekopoi (`https://nekopoi.care`) Indonesian NSFW anime extension for Anikku/Aniyomi following the repository's contributing guidelines, using clean architecture, TDD, and thorough offline fixture tests.

## Architecture Decisions
- Package structure: `eu.kanade.tachiyomi.animeextension.id.nekopoi` under `src/id/nekopoi`.
- Companion files: `Filters.kt` to avoid name repetition as required by current repository conventions.
- Streamlined extraction: Support DoodStream (`playmogo.com`), StreamWish, and generic video embeds.
- Comprehensive date parsing: Indonesian locale formatter `d MMMM yyyy` with safe `tryParse`.

## Task List

### Phase 1: Foundation & Module Scaffolding
- [ ] Task 1: Initialize Gradle module `src/id/nekopoi/build.gradle`, configure icon assets, and wire into settings.

### Phase 2: Filters & Search Models
- [ ] Task 2: Implement `Filters.kt` containing category, genre, and sort filter models.

### Phase 3: Extension Implementation
- [ ] Task 3: Implement `Nekopoi.kt` with Popular, Latest, Search, Details, Episodes, and Video list extraction logic.

### Phase 4: Verification & Test Suite
- [ ] Task 4: Author TDD unit and fixture tests verifying parsing for all flows against offline HTML fixtures.
- [ ] Task 5: Execute full Gradle build (`assembleDebug`), lint, and final PR readiness check.

## Risks & Mitigations
| Risk | Impact | Mitigation |
|------|--------|------------|
| Cloudflare challenge on video embed | Medium | Use multiple player fallbacks and direct mirror domains |
| Dynamic HTML changes | Low | Use structural CSS selectors and multiple fallback extractors |
