# hanime.tv URL Patterns

This document provides comprehensive documentation of all URL patterns and routing structures used by hanime.tv.

---

## Overview

hanime.tv uses Vue Router with HTML5 History mode for client-side routing. All routes are handled by the single-page
application (SPA) architecture.

---

## Primary Routes

### Home Page

```
URL: https://hanime.tv/home
```

The main landing page featuring:

- Featured video carousel
- Recent uploads grid
- Trending content
- Brand spotlights

**Example:**

```
https://hanime.tv/home
```

---

### Video Detail Page

```
URL: https://hanime.tv/videos/hentai/{slug}
```

Video viewing page with full details.

**Parameters:**

| Parameter | Type   | Description           |
|-----------|--------|-----------------------|
| `slug`    | string | Video slug identifier |

**Example:**

```
https://hanime.tv/videos/hentai/ojousama-gakuen-garcon-eres-tuhan-1
```

**Page Components:**

- Video player
- Video information
- Tag list
- Related videos
- Comments section

---

### Browse Page

```
URL: https://hanime.tv/browse
```

Content discovery and filtering page.

**Query Parameters:**

| Parameter | Type    | Description                 |
|-----------|---------|-----------------------------|
| `page`    | integer | Page number (0-indexed)     |
| `tags`    | string  | Comma-separated tag slugs   |
| `brands`  | string  | Comma-separated brand slugs |
| `order`   | string  | Sort order                  |
| `search`  | string  | Search query                |

**Examples:**

```
https://hanime.tv/browse
https://hanime.tv/browse?page=2&order=-created_at
https://hanime.tv/browse?tags=ahegao,big-boobs&brands=pink-pineapple
```

---

### Search Page

```
URL: https://hanime.tv/search
```

Search interface with advanced filtering.

**Query Parameters:**

| Parameter  | Type     | Description              |
|------------|----------|--------------------------|
| `q`        | string   | Search query             |
| `page`     | integer  | Page number              |
| `tags[]`   | string[] | Tag filters (repeated)   |
| `brands[]` | string[] | Brand filters (repeated) |
| `ordering` | string   | Sort order               |

**Examples:**

```
https://hanime.tv/search?q=example
https://hanime.tv/search?q=example&tags[]=ahegao&ordering=-views
```

---

### Tag Page

```
URL: https://hanime.tv/tags/{tag}
```

Videos filtered by specific tag.

**Parameters:**

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `tag`     | string | Tag slug    |

**Examples:**

```
https://hanime.tv/tags/ahegao
https://hanime.tv/tags/big-boobs
https://hanime.tv/tags/incest
```

**Query Parameters:**

| Parameter  | Type    | Description |
|------------|---------|-------------|
| `page`     | integer | Page number |
| `ordering` | string  | Sort order  |

---

### Brand Page

```
URL: https://hanime.tv/brand/{brand}
```

Videos from a specific studio/brand.

**Parameters:**

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `brand`   | string | Brand slug  |

**Examples:**

```
https://hanime.tv/brand/pink-pineapple
https://hanime.tv/brand/poison
https://hanime.tv/brand/bunny-walker
```

---

## Authentication Routes

### Login Page

```
URL: https://hanime.tv/login
```

User authentication page.

---

### Register Page

```
URL: https://hanime.tv/register
```

New user registration.

---

### Password Reset

```
URL: https://hanime.tv/forgot-password
```

Password recovery flow.

---

## User Routes

### User Profile

```
URL: https://hanime.tv/user/{username}
```

User profile page showing uploads and favorites.

**Parameters:**

| Parameter  | Type   | Description         |
|------------|--------|---------------------|
| `username` | string | User's display name |

**Example:**

```
https://hanime.tv/user/example_user
```

---

### User Favorites

```
URL: https://hanime.tv/user/{username}/favorites
```

User's favorited videos.

---

### User Playlists

```
URL: https://hanime.tv/user/{username}/playlists
```

User's created playlists.

---

## Playlist Routes

### Playlist Detail

```
URL: https://hanime.tv/playlist/{id}
```

View a specific playlist.

**Parameters:**

| Parameter | Type    | Description |
|-----------|---------|-------------|
| `id`      | integer | Playlist ID |

**Example:**

```
https://hanime.tv/playlist/500
```

---

## Utility Routes

### Country Detection

```
URL: https://hanime.tv/country_code
```

Returns user's country code for localization.

**Response:**

```json
{
  "country_code": "US",
  "country_name": "United States"
}
```

---

### Video Player

```
URL: https://hanime.tv/omni-player/index.html
```

Standalone video player iframe.

**Query Parameters:**

| Parameter    | Type    | Description             |
|--------------|---------|-------------------------|
| `poster_url` | string  | URL-encoded poster URL  |
| `c`          | integer | Cache-busting timestamp |

**Example:**

```
https://hanime.tv/omni-player/index.html?poster_url=https%3A%2F%2Fhanime-cdn.com%2Fimages%2Fposters%2Fvideo-pv1.webp&c=1704067200000
```

---

## API URL Patterns

### Primary API

```
Base: https://cached.freeanimehentai.net
```

| Endpoint                            | Method | Description       |
|-------------------------------------|--------|-------------------|
| `/api/v10/search_hvs`               | GET    | Search videos     |
| `/api/v8/hentai_videos/{slug}`      | GET    | Get video details |
| `/api/v8/hentai_videos/{slug}/play` | POST   | Track play event  |
| `/api/v8/playlists`                 | GET    | Get playlists     |
| `/rapi/v7/users`                    | GET    | Get user profiles |

---

### hanime.tv Domain API

```
Base: https://hanime.tv
```

| Endpoint                    | Method | Description  |
|-----------------------------|--------|--------------|
| `/country_code`             | GET    | Geo-location |
| `/rapi/v7/preroll_ad_event` | POST   | Ad tracking  |

---

### Community API

```
Base: https://community-uploads.highwinds-cdn.com
```

| Endpoint                    | Method | Description       |
|-----------------------------|--------|-------------------|
| `/api/v9/community_uploads` | GET    | Community content |

---

## CDN URL Patterns

### Media Assets

```
Base: https://hanime-cdn.com
```

#### Video Posters

```
/images/posters/{slug}-pv{variant}.webp
```

**Parameters:**

| Parameter | Type    | Description                |
|-----------|---------|----------------------------|
| `slug`    | string  | Video slug                 |
| `variant` | integer | Poster number (1, 2, etc.) |

**Example:**

```
https://hanime-cdn.com/images/posters/example-video-pv1.webp
https://hanime-cdn.com/images/posters/example-video-pv2.webp
```

---

#### Video Covers

```
/images/covers/{slug}-cv{variant}.webp
```

**Parameters:**

| Parameter | Type    | Description               |
|-----------|---------|---------------------------|
| `slug`    | string  | Video slug                |
| `variant` | integer | Cover number (1, 2, etc.) |

**Example:**

```
https://hanime-cdn.com/images/covers/example-video-cv1.webp
```

---

#### Storyboard Images

```
/images/storyboards/{slug}-{resolution}-h{index}x.webp
```

**Parameters:**

| Parameter    | Type    | Description              |
|--------------|---------|--------------------------|
| `slug`       | string  | Video slug               |
| `resolution` | string  | Resolution (720p, 1080p) |
| `index`      | integer | Horizontal tile index    |

**Example:**

```
https://hanime-cdn.com/images/storyboards/example-video-720p-h1x.webp
https://hanime-cdn.com/images/storyboards/example-video-720p-h2x.webp
https://hanime-cdn.com/images/storyboards/example-video-1080p-h1x.webp
```

---

#### Avatar Images

```
/avatars/{user_id}.webp
```

**Example:**

```
https://hanime-cdn.com/avatars/10042.webp
```

---

#### Environment Configuration

```
/vhtv2/env.json
```

Returns environment configuration.

**Response:**

```json
{
  "vhtv2_version": 1704067200000
}
```

---

#### JavaScript Bundles

```
/vhtv2/{bundle}.js
```

**Common Bundles:**

| Bundle          | Description           |
|-----------------|-----------------------|
| `vendor.min.js` | Third-party libraries |
| `app.min.js`    | Application code      |
| `chunk.*.js`    | Lazy-loaded modules   |

---

## Ad Provider URLs

### AdTng

```
Base: https://a.adtng.com
```

**Common Patterns:**

```
https://a.adtng.com/get?tag=preroll&format=vast
https://a.adtng.com/get?tag=midroll&format=vast&vpos=midroll
```

---

## URL Construction Helpers

### Video URLs

```javascript
// Construct video page URL
const getVideoUrl = (slug) => {
  return `https://hanime.tv/videos/hentai/${slug}`;
};

// Example
getVideoUrl('example-video-slug');
// https://hanime.tv/videos/hentai/example-video-slug
```

### Poster URLs

```javascript
// Construct poster URL
const getPosterUrl = (slug, variant = 1) => {
  return `https://hanime-cdn.com/images/posters/${slug}-pv${variant}.webp`;
};

// Example
getPosterUrl('example-video', 1);
// https://hanime-cdn.com/images/posters/example-video-pv1.webp
```

### Search URLs

```javascript
// Construct search URL
const getSearchUrl = (params) => {
  const query = new URLSearchParams();
  
  if (params.q) query.set('q', params.q);
  if (params.page) query.set('page', params.page);
  if (params.ordering) query.set('ordering', params.ordering);
  
  params.tags?.forEach(tag => query.append('tags[]', tag));
  params.brands?.forEach(brand => query.append('brands[]', brand));
  
  return `https://hanime.tv/search?${query.toString()}`;
};

// Example
getSearchUrl({
  q: 'example',
  tags: ['ahegao', 'big-boobs'],
  ordering: '-views',
  page: 0
});
// https://hanime.tv/search?q=example&tags[]=ahegao&tags[]=big-boobs&ordering=-views&page=0
```

### API URLs

```javascript
// Construct API URL
const getApiUrl = (endpoint, params = {}) => {
  const base = 'https://cached.freeanimehentai.net';
  const query = new URLSearchParams(params).toString();
  
  return `${base}${endpoint}${query ? `?${query}` : ''}`;
};

// Example
getApiUrl('/api/v10/search_hvs', { page: 0, page_size: 24 });
// https://cached.freeanimehentai.net/api/v10/search_hvs?page=0&page_size=24
```

---

## URL Validation

### Slug Validation

```javascript
// Valid slug pattern
const SLUG_PATTERN = /^[a-z0-9-]+$/;

const isValidSlug = (slug) => {
  return SLUG_PATTERN.test(slug) && slug.length >= 3 && slug.length <= 200;
};

// Examples
isValidSlug('example-video-slug');  // true
isValidSlug('Example-Video');       // false (uppercase)
isValidSlug('example_video');       // false (underscore)
```

### Tag Validation

```javascript
// Valid tag slug pattern
const TAG_PATTERN = /^[a-z0-9-]+$/;

const isValidTag = (tag) => {
  return TAG_PATTERN.test(tag) && tag.length >= 2 && tag.length <= 50;
};
```

### Brand Validation

```javascript
// Valid brand slug pattern
const BRAND_PATTERN = /^[a-z0-9-]+$/;

const isValidBrand = (brand) => {
  return BRAND_PATTERN.test(brand) && brand.length >= 2 && brand.length <= 100;
};
```

---

## URL Routing Table

| Path                        | Component      | Meta                    |
|-----------------------------|----------------|-------------------------|
| `/`                         | Redirect       | → `/home`               |
| `/home`                     | Home           | title: 'Home'           |
| `/videos/hentai/:slug`      | VideoDetail    | title: 'Video'          |
| `/browse`                   | Browse         | title: 'Browse'         |
| `/search`                   | Search         | title: 'Search'         |
| `/tags/:tag`                | TagVideos      | title: 'Tag'            |
| `/brand/:brand`             | BrandVideos    | title: 'Brand'          |
| `/login`                    | Login          | title: 'Login'          |
| `/register`                 | Register       | title: 'Register'       |
| `/forgot-password`          | ForgotPassword | title: 'Reset Password' |
| `/user/:username`           | UserProfile    | title: 'Profile'        |
| `/user/:username/favorites` | UserFavorites  | title: 'Favorites'      |
| `/user/:username/playlists` | UserPlaylists  | title: 'Playlists'      |
| `/playlist/:id`             | PlaylistDetail | title: 'Playlist'       |

---

## Query Parameter Reference

### Pagination

| Parameter   | Type    | Default | Description             |
|-------------|---------|---------|-------------------------|
| `page`      | integer | 0       | Page number (0-indexed) |
| `page_size` | integer | 24      | Items per page          |

### Sorting

| Value          | Description      |
|----------------|------------------|
| `-created_at`  | Newest first     |
| `-views`       | Most viewed      |
| `-likes`       | Most liked       |
| `-downloads`   | Most downloaded  |
| `name`         | Alphabetical A-Z |
| `-name`        | Alphabetical Z-A |
| `-released_at` | Newest release   |

### Filters

| Parameter  | Type   | Description                 |
|------------|--------|-----------------------------|
| `tags`     | string | Comma-separated tag slugs   |
| `tags[]`   | string | Single tag (repeatable)     |
| `brands`   | string | Comma-separated brand slugs |
| `brands[]` | string | Single brand (repeatable)   |
| `q`        | string | Search query                |

---

## Complete URL Examples

### Navigation Examples

```
# Home
https://hanime.tv/home

# Video
https://hanime.tv/videos/hentai/mako-chan-kaihatsu-nikki-1

# Browse (page 2, newest)
https://hanime.tv/browse?page=2&order=-created_at

# Search with filters
https://hanime.tv/search?q=school&tags[]=ahegao&tags[]=big-boobs&ordering=-views

# Tag page
https://hanime.tv/tags/ahegao

# Brand page
https://hanime.tv/brand/pink-pineapple

# User profile
https://hanime.tv/user/example_user

# Playlist
https://hanime.tv/playlist/500
```

### API Examples

```
# Search videos
https://cached.freeanimehentai.net/api/v10/search_hvs?page=0&page_size=24&ordering=-created_at

# Get related videos
https://cached.freeanimehentai.net/api/v8/playlists?source=related&hv_id=12345

# Track play
POST https://cached.freeanimehentai.net/api/v8/hentai_videos/example-video/play

# Get users
https://cached.freeanimehentai.net/rapi/v7/users?source=simple&user_ids[]=1&user_ids[]=2
```

### CDN Examples

```
# Poster
https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv1.webp

# Cover
https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv1.webp

# Storyboard
https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-720p-h1x.webp

# Avatar
https://hanime-cdn.com/avatars/10042.webp

# Environment
https://hanime-cdn.com/vhtv2/env.json
```
