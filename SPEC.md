# Specification: Nekopoi Anime Extension (Issue #94)

## 1. Overview
- **Issue Reference:** [yuzono/anime-extensions#94](https://github.com/yuzono/anime-extensions/issues/94)
- **Source Name:** Nekopoi
- **Base URL:** `https://nekopoi.care`
- **Language:** Indonesian (`id`)
- **Content Type:** 18+ / NSFW (`isNsfw = true`)
- **Module Path:** `src/id/nekopoi`
- **Package Name:** `eu.kanade.tachiyomi.animeextension.id.nekopoi`

## 2. Extension Architecture & Conventions
- Conforms strictly to repository `CONTRIBUTING.md` standards.
- Class `Nekopoi` extends `AnimeHttpSource` and `ConfigurableAnimeSource`.
- Companion files: `Filters.kt` (no redundant prefix per naming rule).
- Shared utilities: `keiyoushi.utils.tryParse`, `keiyoushi.utils.getPreferencesLazy`, `keiyoushi.utils.parallelCatchingFlatMapBlocking`.
- Extractors: `DoodExtractor` (`lib:doodextractor`), `StreamWishExtractor` (`lib:streamwishextractor`), `VidHideExtractor` (`lib:vidhideextractor`).

## 3. Endpoints & Data Extraction Specifications

### 3.1. Popular Anime
- **Request:** `GET("$baseUrl/category/hentai/page/$page/", headers)` (or `GET("$baseUrl/hentai-list/", headers)` for page 1)
- **Parser:** Extracts `List<SAnime>` from `.nk-search-results ul li a.nk-search-item`. Extracts `title`, `thumbnail_url` from `.nk-search-thumb` CSS background image, and `url` via `setUrlWithoutDomain`.
- **Pagination:** Checked via `nav.pagination .nav-links a.next.page-numbers` or `.page-numbers`.

### 3.2. Latest Updates
- **Request:** `GET("$baseUrl/page/$page/", headers)`
- **Parser:** Extracts `List<SAnime>` from episode releases on the home/feed pagination or `.nk-search-results`.
- **Pagination:** Extracted from page navigation.

### 3.3. Search & Filters
- **Request Logic:**
  - If text query present: `GET("$baseUrl/page/$page/?s=$query&post_type=anime", headers)` or `GET("$baseUrl/search/$query/page/$page/", headers)`
  - If genre filter selected: `GET("$baseUrl/genres/$genreSlug/page/$page/", headers)`
  - If category filter selected: `GET("$baseUrl/category/$categorySlug/page/$page/", headers)`
- **Filters (`Filters.kt`):**
  - Category Filter (Hentai, 2D Animation, 3D Hentai, JAV, JAV Cosplay)
  - Genre Filter (Action, Ahegao, Anal, Big Oppai, Blowjob, Creampie, etc.)
  - Sort / Order Filter where applicable.

### 3.4. Anime Details
- **Parser:**
  - Title: `.nk-series-synopsis > b` or `h1` or `.nk-post-header h1`
  - Thumbnail: background-image url from `.nk-series-poster` or `meta[property="og:image"]`
  - Synopsis / Description: `.nk-series-synopsis p` or `.nk-post-body p.separator`
  - Status: Parsed from `Status: Completed/Ongoing`
  - Producer: Parsed from `Produser:`
  - Genres: Parsed from `Genre:`

### 3.5. Episode List
- **Parser:**
  - From series page: extracts `.nk-episode-grid ul li a.nk-episode-card`
  - Title: `.nk-episode-card-title` text
  - Episode Number: parsed from `.nk-episode-badge` (e.g. `Ep 1` -> 1F) or title
  - Upload Date: parsed using Indonesian date format `d MMMM yyyy` with `SimpleDateFormat(..., Locale("id", "ID"))`
  - Order: Descending list of episodes (newest first).

### 3.6. Video Extraction
- **Parser:**
  - Reads iframe streams from `#nk-player .nk-player-frame iframe` (`#nk-stream-1`, `#nk-stream-2`, `#nk-stream-3`)
  - Supported embed hosts:
    - `playmogo.com` -> DoodStream extractor via direct or `d000d.com` mirror
    - `streampoi.com` -> Stream extractor / StreamWish
    - Generic iframe fallback / direct video source
  - Video qualities sorted according to user preference in `ConfigurableAnimeSource`.

## 4. Acceptance Criteria & Verification
- [x] Extension builds cleanly with `./gradlew src:id:nekopoi:assembleDebug`.
- [x] Unit tests cover Search, Popular, Latest, Details, Episodes, and Video list parsing against offline fixtures.
- [x] Extension icon asset placed under `res/mipmap-*`.
- [x] `isNsfw = true` explicitly defined in `build.gradle`.
