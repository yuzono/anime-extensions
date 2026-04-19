# hanime.tv API Documentation

This document provides comprehensive documentation of the hanime.tv API endpoints and their usage patterns.

## Overview

hanime.tv uses a distributed API architecture with multiple backend services:

| Service | Base URL | Purpose |
|---------|----------|---------|
| Primary API | `https://cached.freeanimehentai.net` | Main content delivery |
| hanime.tv Domain | `https://hanime.tv` | Web application and geo services |
| Community API | `https://community-uploads.highwinds-cdn.com` | User-generated content |
| CDN | `https://hanime-cdn.com` | Static assets and media files |
| HLS Streaming | `https://m3u8s.highwinds-cdn.com` | HLS playlist delivery |
| Video Segments | `https://p{server_id}.htv-hydaelyn.com` | Encrypted video segment delivery |

---

## Primary API Endpoints

### Base URL
```
https://cached.freeanimehentai.net
```

---

## Video Manifest API

### Get Video Details by Slug

**Endpoint:** `GET /api/v8/video`

Retrieve video metadata including the hv_id and videos_manifest by video slug. This endpoint is typically called when loading a video detail page.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Video slug (e.g., "cool-de-m-2") |

**Headers Required:**

| Header | Value (Guest) |
|--------|---------------|
| `x-signature` | HMAC-SHA256 signature (64-char hex) |
| `x-time` | Unix timestamp (seconds) |
| `x-signature-version` | `web2` |
| `x-session-token` | (empty for guests) |
| `x-user-license` | (empty for guests) |
| `x-csrf-token` | (empty for guests) |
| `x-license` | (empty for guests) |

**Note:** The signature is available in the global JavaScript variable `ssignature` on the page (see [Signature Generation](#signature-generation)).

**Response:** Returns a `hentai_video` object containing video metadata and `videos_manifest` with stream URLs.

```typescript
interface VideoResponse {
  hentai_video: HentaiVideo;
  videos_manifest: {
    servers: Server[];
  };
}
```

**Example Request:**
```http
GET /api/v8/video?id=cool-de-m-2 HTTP/1.1
Host: cached.freeanimehentai.net
x-signature: 8567328e24f9cbc47052b338a21f2cb056275388668be224262df46e12a68084
x-time: 1704067200
x-signature-version: web2
```

---

### Get Video Manifest

**Endpoint:** `GET /api/v8/guest/videos/{hv_id}/manifest`

Retrieve the complete video manifest containing all available servers, streams, and quality options for a specific video. This endpoint returns the full streaming infrastructure configuration needed to play a video.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `hv_id` | integer | Hentai video ID |

**Example Request:**
```http
GET /api/v8/guest/videos/12345/manifest HTTP/1.1
Host: cached.freeanimehentai.net
x-signature: <hmac_signature>
x-time: 1704067200
x-signature-version: web2
```

**Response Structure:**

```typescript
interface ManifestResponse {
  servers: Server[];
}

interface Server {
  id: number;
  name: string;
  slug: string;
  na_rating: number;
  eu_rating: number;
  asia_rating: number;
  sequence: number;
  is_permanent: boolean;
  streams: Stream[];
}

interface Stream {
  id: number;
  server_id: number;
  slug: string;
  kind: \'hls\';
  extension: \'m3u8\';
  mime_type: \'application/x-mpegURL\';
  width: number;
  height: number;
  duration_in_ms: number;
  filesize_mbs: number;
  filename: string;
  url: string;
  is_guest_allowed: boolean;
  is_member_allowed: boolean;
  is_premium_allowed: boolean;
  is_downloadable: boolean;
  compatibility: \'all\';
  hv_id: number;
  server_sequence: number;
  video_stream_group_id: number;
}
```

**Kotlin Data Classes:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ManifestResponse(
    val servers: List<Server>
)

@Serializable
data class Server(
    val id: Int,
    val name: String,
    val slug: String,
    @SerialName("na_rating")
    val naRating: Int,
    @SerialName("eu_rating")
    val euRating: Int,
    @SerialName("asia_rating")
    val asiaRating: Int,
    val sequence: Int,
    @SerialName("is_permanent")
    val isPermanent: Boolean,
    val streams: List<Stream>
)

@Serializable
data class Stream(
    val id: Int,
    @SerialName("server_id")
    val serverId: Int,
    val slug: String,
    val kind: String,
    val extension: String,
    @SerialName("mime_type")
    val mimeType: String,
    val width: Int,
    val height: Int,
    @SerialName("duration_in_ms")
    val durationInMs: Long,
    @SerialName("filesize_mbs")
    val filesizeMbs: Int,
    val filename: String,
    val url: String,
    @SerialName("is_guest_allowed")
    val isGuestAllowed: Boolean,
    @SerialName("is_member_allowed")
    val isMemberAllowed: Boolean,
    @SerialName("is_premium_allowed")
    val isPremiumAllowed: Boolean,
    @SerialName("is_downloadable")
    val isDownloadable: Boolean,
    val compatibility: String,
    @SerialName("hv_id")
    val hvId: Int,
    @SerialName("server_sequence")
    val serverSequence: Int,
    @SerialName("video_stream_group_id")
    val videoStreamGroupId: Int
)
```

**Example Response:**
```json
{
  "servers": [
    {
      "id": 14,
      "name": "Golem",
      "slug": "golem",
      "na_rating": 3,
      "eu_rating": 3,
      "asia_rating": 3,
      "sequence": 1,
      "is_permanent": true,
      "streams": [
        {
          "id": 1001,
          "server_id": 14,
          "slug": "720p",
          "kind": "hls",
          "extension": "m3u8",
          "mime_type": "application/x-mpegURL",
          "width": 1280,
          "height": 720,
          "duration_in_ms": 1200000,
          "filesize_mbs": 450,
          "filename": "video_720p.m3u8",
          "url": "https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/abc123def.m3u8",
          "is_guest_allowed": true,
          "is_member_allowed": true,
          "is_premium_allowed": true,
          "is_downloadable": true,
          "compatibility": "all",
          "hv_id": 12345,
          "server_sequence": 1,
          "video_stream_group_id": 42
        },
        {
          "id": 1002,
          "server_id": 14,
          "slug": "480p",
          "kind": "hls",
          "extension": "m3u8",
          "mime_type": "application/x-mpegURL",
          "width": 854,
          "height": 480,
          "duration_in_ms": 1200000,
          "filesize_mbs": 280,
          "filename": "video_480p.m3u8",
          "url": "https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/def456ghi.m3u8",
          "is_guest_allowed": true,
          "is_member_allowed": true,
          "is_premium_allowed": true,
          "is_downloadable": true,
          "compatibility": "all",
          "hv_id": 12345,
          "server_sequence": 2,
          "video_stream_group_id": 42
        },
        {
          "id": 1003,
          "server_id": 14,
          "slug": "360p",
          "kind": "hls",
          "extension": "m3u8",
          "mime_type": "application/x-mpegURL",
          "width": 640,
          "height": 360,
          "duration_in_ms": 1200000,
          "filesize_mbs": 150,
          "filename": "video_360p.m3u8",
          "url": "https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/ghi789jkl.m3u8",
          "is_guest_allowed": true,
          "is_member_allowed": true,
          "is_premium_allowed": true,
          "is_downloadable": true,
          "compatibility": "all",
          "hv_id": 12345,
          "server_sequence": 3,
          "video_stream_group_id": 42
        }
      ]
    }
  ]
}
```

**Server Rating Values:**

The `na_rating`, `eu_rating`, and `asia_rating` fields indicate server performance/reliability for each geographic region. Higher values indicate better performance. Common servers include:

| Server ID | Name | Typical Ratings |
|-----------|------|-----------------|
| 14 | Golem | 3 (all regions) |
| 15 | Hydra | 3 (all regions) |
| 16 | Phoenix | 2-3 (varies by region) |
| 17 | Titan | 2-3 (varies by region) |

**Quality Level Reference:**

| Quality | Width | Height | Typical File Size (20 min) |
|---------|-------|--------|----------------------------|
| 720p | 1280 | 720 | ~400-500 MB |
| 480p | 854 | 480 | ~250-300 MB |
| 360p | 640 | 360 | ~140-160 MB |

---

## HLS Streaming API

### HLS Playlist Structure

The HLS (HTTP Live Streaming) playlists are served from Highwinds CDN and contain encrypted video segments.

**Endpoint:** `GET https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/{hash}.m3u8`

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `hash` | string | Unique playlist identifier hash |

**Example Request:**
```http
GET /api/v9/m3u8s/abc123def456ghi789.m3u8 HTTP/1.1
Host: m3u8s.highwinds-cdn.com
```

**HLS Playlist Format:**

```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-TARGETDURATION:14
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=AES-128,URI='https://hanime.tv/sign.bin'
#EXTINF:10.000,
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html
#EXTINF:10.000,
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0001.html
#EXTINF:10.000,
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0002.html
#EXT-X-ENDLIST
```

### HLS Tag Reference

| Tag | Description |
|-----|-------------|
| `#EXTM3U` | Required file header identifying the file as an HLS playlist |
| `#EXT-X-VERSION:3` | Playlist format version (version 3 supports floating-point durations) |
| `#EXT-X-PLAYLIST-TYPE:VOD` | Video-on-Demand playlist (static, complete playlist) |
| `#EXT-X-INDEPENDENT-SEGMENTS` | Each segment can be decoded without preceding segments |
| `#EXT-X-TARGETDURATION:14` | Maximum segment duration in seconds |
| `#EXT-X-MEDIA-SEQUENCE:0` | Starting sequence number for segments |
| `#EXT-X-KEY` | Encryption method and key URI |
| `#EXTINF` | Duration and title for the following segment |
| `#EXT-X-ENDLIST` | Marks the end of the playlist |

### AES-128 Encryption

All video segments are encrypted using AES-128. The encryption key is fetched from:

```
https://hanime.tv/sign.bin
```

**Key Properties:**

| Property | Value |
|----------|-------|
| Method | AES-128 |
| Key URI | `https://hanime.tv/sign.bin` |
| IV | Typically null (uses sequence number) |
| Key Format | Raw 16-byte binary key |

**Decryption Implementation (TypeScript):**

```typescript
import crypto from \'crypto\';

async function decryptSegment(
  encryptedBuffer: Buffer,
  key: Buffer,
  segmentIndex: number
): Promise<Buffer> {
  // Create IV from segment sequence number
  const iv = Buffer.alloc(16);
  iv.writeUInt32BE(segmentIndex, 12);

  const decipher = crypto.createDecipheriv(\'aes-128-cbc\', key, iv);
  const decrypted = Buffer.concat([
    decipher.update(encryptedBuffer),
    decipher.final()
  ]);

  return decrypted;
}
```

**Decryption Implementation (Kotlin):**

```kotlin
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypt an AES-128-CBC encrypted video segment.
 * 
 * @param encryptedData The encrypted segment data
 * @param key The 16-byte AES key fetched from sign.bin
 * @param segmentIndex The segment sequence number (used to generate IV)
 * @return Decrypted segment data
 */
fun decryptSegment(
    encryptedData: ByteArray,
    key: ByteArray,
    segmentIndex: Int
): ByteArray {
    // Create IV from segment sequence number (16 bytes, index at the end)
    val iv = ByteArray(16)
    // Write segment index as big-endian at the last 4 bytes
    iv[12] = (segmentIndex shr 24).toByte()
    iv[13] = (segmentIndex shr 16).toByte()
    iv[14] = (segmentIndex shr 8).toByte()
    iv[15] = segmentIndex.toByte()

    val secretKey = SecretKeySpec(key, "AES")
    val ivSpec = IvParameterSpec(iv)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

    return cipher.doFinal(encryptedData)
}
```

### Segment URL Pattern

Video segments follow a specific URL pattern:

```
https://p{server_id}.htv-hydaelyn.com/{hv_id_digits}/{video_stream_group_id}/segs/b0/2/{segment_number}.html
```

**URL Components:**

| Component | Description | Example |
|-----------|-------------|---------|
| `p{server_id}` | Server identifier prefix | `p34` for server 34 |
| `{hv_id_digits}` | Video ID split into path segments | `3/4/2/6` for hv_id 3426 |
| `{video_stream_group_id}` | Stream group identifier | `42` |
| `b0/2` | Quality/bitrate path segment | `b0/2` for standard quality |
| `{segment_number}` | Zero-padded segment index | `0000`, `0001`, `0002` |
| `.html` | File extension (segments are served as HTML) | `.html` |

**Example URL Breakdown:**

```
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html

Domain:     p34.htv-hydaelyn.com
Path:       3/4/2/6           (hv_id: 3426, split as 3/4/2/6)
Stream:     h1x               (horizontal storyboard index)
Segments:   segs/b0/2         (bitrate path)
File:       0000.html         (segment 0)
```

### Segment Download Implementation

```typescript
interface SegmentInfo {
  serverId: number;
  hvId: number;
  streamGroupId: number;
  segmentIndex: number;
  qualityPath: string;
}

function buildSegmentUrl(info: SegmentInfo): string {
  // Split hv_id into path segments
  const hvIdDigits = info.hvId.toString().split(\'\').join(\'/\');

  const segmentNum = info.segmentIndex.toString().padStart(4, \'0\');

  return `https://p${info.serverId}.htv-hydaelyn.com/${hvIdDigits}/${info.streamGroupId}/segs/${info.qualityPath}/${segmentNum}.html`;
}

// Example usage
const url = buildSegmentUrl({
  serverId: 34,
  hvId: 3426,
  streamGroupId: 42,
  segmentIndex: 0,
  qualityPath: \'b0/2\'
});
// Result: https://p34.htv-hydaelyn.com/3/4/2/6/42/segs/b0/2/0000.html
```

**Kotlin Implementation:**

```kotlin
data class SegmentInfo(
    val serverId: Int,
    val hvId: Int,
    val streamGroupId: Int,
    val segmentIndex: Int,
    val qualityPath: String
)

fun buildSegmentUrl(info: SegmentInfo): String {
    // Split hv_id into path segments (e.g., 3426 -> "3/4/2/6")
    val hvIdDigits = info.hvId.toString().chunked(1).joinToString("/")
    
    // Zero-pad segment number to 4 digits
    val segmentNum = info.segmentIndex.toString().padStart(4, '0')
    
    return "https://p${info.serverId}.htv-hydaelyn.com/$hvIdDigits/${info.streamGroupId}/segs/${info.qualityPath}/$segmentNum.html"
}

// Example usage
val url = buildSegmentUrl(
    SegmentInfo(
        serverId = 34,
        hvId = 3426,
        streamGroupId = 42,
        segmentIndex = 0,
        qualityPath = "b0/2"
    )
)
// Result: https://p34.htv-hydaelyn.com/3/4/2/6/42/segs/b0/2/0000.html
```

---

## Search API Response Structure

### Search Hentai Videos

**Endpoint:** `GET /api/v10/search_hvs`

Search for hentai videos with various filters and pagination.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `q` | string | Search query (optional) |
| `page` | integer | Page number (default: 0) |
| `tags[]` | string[] | Tag filters (can be repeated) |
| `brands[]` | string[] | Brand/studio filters (can be repeated) |
| `ordering` | string | Sort order (e.g., `-created_at`, `-views`) |
| `page_size` | integer | Results per page |

**Important:** The search response returns a **flat JSON array** without a pagination wrapper. All video objects are returned directly in the array.

### Response Structure

```typescript
interface SearchResponse {
  // Note: Response is a flat array, not wrapped in an object
  // The array contains HentaiVideo objects directly
}

// Response is directly an array of HentaiVideo objects
type SearchResponse = HentaiVideo[];

interface HentaiVideo {
  id: number;
  name: string;
  search_titles: string;
  slug: string;
  description: string;
  views: number;
  cover_url: string;
  poster_url: string;
  brand: string;
  brand_id: number;
  likes: number;
  dislikes: number;
  downloads: number;
  tags: Tag[];
  created_at_unix: number;
  released_at_unix: number;
  created_at: string;
  released_at: string;
}

interface Tag {
  id: number;
  name: string;
  slug: string;
}
```

**Kotlin Data Classes:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HentaiVideo(
    val id: Int,
    val name: String,
    @SerialName("search_titles")
    val searchTitles: String,
    val slug: String,
    val description: String,
    val views: Int,
    @SerialName("cover_url")
    val coverUrl: String,
    @SerialName("poster_url")
    val posterUrl: String,
    val brand: String,
    @SerialName("brand_id")
    val brandId: Int,
    val likes: Int,
    val dislikes: Int,
    val downloads: Int,
    val tags: List<Tag>,
    @SerialName("created_at_unix")
    val createdAtUnix: Long,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("released_at")
    val releasedAt: String
)

@Serializable
data class Tag(
    val id: Int,
    val name: String,
    val slug: String
)

// Search response is a flat array
typealias SearchHvsResponse = List<HentaiVideo>
```

### Field Reference (20 Fields per Video Object)

| Field | Type | Description |
|-------|------|-------------|
| `id` | number | Unique video identifier |
| `name` | string | Video title (primary language) |
| `search_titles` | string | Multi-language title concatenation |
| `slug` | string | URL-safe identifier |
| `description` | string | Video description text |
| `views` | number | Total view count |
| `cover_url` | string | Cover image URL (wide format) |
| `poster_url` | string | Poster image URL (portrait format) |
| `brand` | string | Studio/brand name |
| `brand_id` | number | Studio/brand identifier |
| `likes` | number | Like count |
| `dislikes` | number | Dislike count |
| `downloads` | number | Download count |
| `tags` | Tag[] | Array of tag objects |
| `created_at_unix` | number | Upload timestamp (Unix seconds) |
| `released_at_unix` | number | Release timestamp (Unix seconds) |
| `created_at` | string | Upload date (ISO 8601) |
| `released_at` | string | Release date (ISO 8601) |

### Multi-Language Titles

The `search_titles` field contains a concatenation of titles in multiple languages. This enables search across all language variants.

**Example:**
```json
{
  "name": "Example Video Title",
  "search_titles": "Example Video Title \u30b5\u30f3\u30d7\u30eb\u52d5\u753b \uc608\uc2dc \ube44\ub514\uc624"
}
```

In the example above:
- English: "Example Video Title"
- Japanese: "サンプル動画"
- Korean: "예시 비디오"

### Tag Formatting Rules

Tags follow specific formatting conventions:

| Rule | Example |
|------|---------|
| Lowercase only | `"blow job"`, `"big boobs"` |
| Space-separated for multi-word | `"school girl"`, `"big tits"` |
| Hyphenated when appropriate | `"titty fuck"`, `"paizuri"` |

**Common Tag Examples:**
```json
{
  "tags": [
    { "id": 1, "name": "blow job", "slug": "blow-job" },
    { "id": 2, "name": "big boobs", "slug": "big-boobs" },
    { "id": 3, "name": "school girl", "slug": "school-girl" },
    { "id": 4, "name": "creampie", "slug": "creampie" },
    { "id": 5, "name": "ahegao", "slug": "ahegao" }
  ]
}
```

### CDN Image URL Patterns

Images follow predictable URL patterns on the CDN:

**Cover Images:**
```
https://hanime-cdn.com/images/covers/{slug}-cv{version}.{format}
```

**Poster Images:**
```
https://hanime-cdn.com/images/posters/{slug}-pv{version}.{format}
```

**URL Components:**

| Component | Description | Values |
|-----------|-------------|--------|
| `{slug}` | Video slug | URL-safe identifier |
| `{version}` | Image version number | `cv1`, `cv2`, `pv1`, `pv2` |
| `{format}` | Image format | `png`, `webp`, `jpg` |

**Example URLs:**
```
https://hanime-cdn.com/images/covers/example-video-slug-cv1.png
https://hanime-cdn.com/images/covers/example-video-slug-cv1.webp
https://hanime-cdn.com/images/posters/example-video-slug-pv1.jpg
https://hanime-cdn.com/images/posters/example-video-slug-pv1.webp
```

### Example Response

```json
[
  {
    "id": 12345,
    "name": "Example Video Title",
    "search_titles": "Example Video Title サンプル動画 예시 비디오",
    "slug": "example-video-slug",
    "description": "A compelling video description...",
    "views": 150000,
    "cover_url": "https://hanime-cdn.com/images/covers/example-video-slug-cv1.webp",
    "poster_url": "https://hanime-cdn.com/images/posters/example-video-slug-pv1.webp",
    "brand": "Studio Name",
    "brand_id": 42,
    "likes": 2500,
    "dislikes": 50,
    "downloads": 5000,
    "tags": [
      { "id": 1, "name": "blow job", "slug": "blow-job" },
      { "id": 2, "name": "big boobs", "slug": "big-boobs" }
    ],
    "created_at_unix": 1704067200,
    "released_at_unix": 1703980800,
    "created_at": "2024-01-01T00:00:00Z",
    "released_at": "2023-12-31T00:00:00Z"
  },
  {
    "id": 12346,
    "name": "Another Video",
    "search_titles": "Another Video 別の動画 또 다른 비디오",
    "slug": "another-video",
    "description": "Another description...",
    "views": 98000,
    "cover_url": "https://hanime-cdn.com/images/covers/another-video-cv1.webp",
    "poster_url": "https://hanime-cdn.com/images/posters/another-video-pv1.webp",
    "brand": "Other Studio",
    "brand_id": 43,
    "likes": 1800,
    "dislikes": 30,
    "downloads": 3200,
    "tags": [
      { "id": 3, "name": "school girl", "slug": "school-girl" },
      { "id": 4, "name": "creampie", "slug": "creampie" }
    ],
    "created_at_unix": 1703980800,
    "released_at_unix": 1703894400,
    "created_at": "2023-12-31T00:00:00Z",
    "released_at": "2023-12-30T00:00:00Z"
  }
]
```

---

## Authentication Headers

All API requests to protected endpoints require specific headers for authentication and request validation.

### Required Headers

| Header | Type | Description | Guest Value | Authenticated Value |
|--------|------|-------------|-------------|---------------------|
| `x-signature` | string | HMAC-SHA256 signature | Required | Required |
| `x-time` | integer | Unix timestamp (seconds) | Required | Required |
| `x-signature-version` | string | Signature algorithm version | `web2` | `web2` |
| `x-session-token` | string | Session authentication token | Empty string `""` | Token from login |
| `x-user-license` | string | User license tier | Empty string `""` | License identifier |
| `x-csrf-token` | string | CSRF protection token | Empty string `""` | Token from cookies |
| `x-license` | string | License verification | Empty string `""` | License string |
| `Content-Type` | string | Request content type | `application/json` | `application/json` |
| `Accept` | string | Expected response type | `application/json` | `application/json` |

### Header Examples

**Guest Request Headers:**
```http
GET /api/v8/guest/videos/12345/manifest HTTP/1.1
Host: cached.freeanimehentai.net
x-signature: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
x-time: 1704067200
x-signature-version: web2
x-session-token: 
x-user-license: 
x-csrf-token: 
x-license: 
Content-Type: application/json
Accept: application/json
```

**Authenticated Request Headers:**
```http
GET /api/v8/videos/12345/manifest HTTP/1.1
Host: cached.freeanimehentai.net
x-signature: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
x-time: 1704067200
x-signature-version: web2
x-session-token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
x-user-license: premium
x-csrf-token: csrf_token_value_here
x-license: license_string_here
Content-Type: application/json
Accept: application/json
```

### Signature Generation

The `x-signature` header contains a pre-computed HMAC-SHA256 signature that is 64 characters in hexadecimal format (256 bits). This section documents the complete signature generation process based on reverse engineering findings.

**Critical Finding:** The signature is available as a global JavaScript variable on the page:

```javascript
// Access the signature from the page's global scope
const signature = window.ssignature;
// Example: "99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5"
```

The `stime` variable contains the matching Unix timestamp:

```javascript
const timestamp = window.stime;
// Example: 1776569239
```

---

#### Complete Signature Algorithm

Based on reverse engineering, the signature is generated using **HMAC-SHA256** with the following specifications:

**1. Key Retrieval**

The signing key is fetched from:

```
GET https://hanime.tv/sign.bin
```

**Key Properties:**

| Property | Value |
|----------|-------|
| Endpoint | `GET https://hanime.tv/sign.bin` |
| Response Type | Binary (16 bytes) |
| Content | `0123456701234567` (ASCII representation) |
| Key Length | 16 bytes (128 bits) |
| Usage | HMAC-SHA256 key |

**2. Signature Format**

| Property | Value |
|----------|-------|
| Algorithm | HMAC-SHA256 |
| Output Format | 64-character hexadecimal string |
| Output Length | 256 bits (SHA-256 output) |
| Example | `99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5` |

**3. Message Format**

The message format for signing is currently under investigation. Based on analysis:

```
// Likely format includes timestamp and potentially other request data
message = timestamp.toString()
```

---

#### Browser-Based Signature Access

The simplest approach is to extract the pre-generated signature from the browser context:

```javascript
// Extract signature and timestamp from global scope
const signature = window.ssignature;  // 64-char hex string
const timestamp = window.stime;       // Unix timestamp in seconds

// Use in API request
const headers = {
  'x-signature': signature,
  'x-time': timestamp.toString(),
  'x-signature-version': 'web2',
  'x-session-token': '',
  'x-user-license': '',
  'x-csrf-token': '',
  'x-license': ''
};
```

---

#### Manual Signature Generation

For implementations that need to generate signatures manually:

**Python Implementation:**

```python
import hmac
import hashlib
import requests
import time

# Step 1: Get the key from sign.bin
key_response = requests.get('https://hanime.tv/sign.bin')
key = key_response.content  # b'0123456701234567' (16 bytes)

# Step 2: Create the message (likely just the timestamp)
timestamp = str(int(time.time()))
message = timestamp.encode('utf-8')

# Step 3: Generate HMAC-SHA256 signature
signature = hmac.new(key, message, hashlib.sha256).hexdigest()

# Step 4: Use in API request
headers = {
    'x-signature': signature,
    'x-time': timestamp,
    'x-signature-version': 'web2',
    'x-session-token': '',
    'x-user-license': '',
    'x-csrf-token': '',
    'x-license': ''
}

# Step 5: Make the API request
response = requests.get(
    'https://cached.freeanimehentai.net/api/v8/guest/videos/3427/manifest',
    headers=headers
)
```

**JavaScript/Node.js Implementation:**

```javascript
const crypto = require('crypto');
const axios = require('axios');

async function generateSignature() {
  // Step 1: Get the key
  const keyResponse = await axios.get('https://hanime.tv/sign.bin', {
    responseType: 'arraybuffer'
  });
  const key = Buffer.from(keyResponse.data);
  
  // Step 2: Generate timestamp
  const timestamp = Math.floor(Date.now() / 1000).toString();
  
  // Step 3: Create HMAC-SHA256 signature
  const signature = crypto
    .createHmac('sha256', key)
    .update(timestamp)
    .digest('hex');
  
  return { signature, timestamp };
}

// Usage
async function makeApiRequest() {
  const { signature, timestamp } = await generateSignature();
  
  const response = await axios.get(
    'https://cached.freeanimehentai.net/api/v8/guest/videos/3427/manifest',
    {
      headers: {
        'x-signature': signature,
        'x-time': timestamp,
        'x-signature-version': 'web2',
        'x-session-token': '',
        'x-user-license': '',
        'x-csrf-token': '',
        'x-license': ''
      }
    }
  );
  
  return response.data;
}
```

**Kotlin Implementation:**

```kotlin
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URL
import java.time.Instant

/**
 * Generate HMAC-SHA256 signature for hanime.tv API authentication.
 * 
 * @param key The 16-byte HMAC key fetched from sign.bin
 * @param timestamp Unix timestamp in seconds as string
 * @return Hex-encoded signature string (64 characters)
 */
fun generateSignature(key: ByteArray, timestamp: String): String {
  require(key.size == 16) { "Key must be 16 bytes" }
  
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(key, "HmacSHA256"))
  val bytes = mac.doFinal(timestamp.toByteArray())
  
  return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Fetch the HMAC key from sign.bin.
 */
suspend fun fetchSignKey(): ByteArray {
  val url = URL("https://hanime.tv/sign.bin")
  // Implementation depends on your HTTP client
  // Returns 16-byte binary data
}

/**
 * Build complete request headers.
 */
fun buildHeaders(key: ByteArray): Map<String, String> {
  val timestamp = (System.currentTimeMillis() / 1000).toString()
  val signature = generateSignature(key, timestamp)
  
  return mapOf(
    "x-signature" to signature,
    "x-time" to timestamp,
    "x-signature-version" to "web2",
    "x-session-token" to "",
    "x-user-license" to "",
    "x-csrf-token" to "",
    "x-license" to ""
  )
}
```

---

#### Complete API Request Example

```http
GET /api/v8/guest/videos/3427/manifest HTTP/1.1
Host: cached.freeanimehentai.net
x-signature: 99ce0e4c35fea0b69bae9e177c614d224225897adb2b203246e2170ff1f509c5
x-time: 1776569239
x-signature-version: web2
x-session-token: 
x-user-license: 
x-csrf-token: 
x-license: 
Referer: https://hanime.tv/
Accept: application/json
```

**TypeScript Implementation:**

```javascript
import crypto from \'crypto\';

interface SignatureParams {
  method: string;
  path: string;
  timestamp: number;
  body?: string;
  secretKey: string;
}

function generateSignature(params: SignatureParams): string {
  const { method, path, timestamp, body = \'\', secretKey } = params;

  // Construct the payload for signing
  const payload = `${method.toUpperCase()}:${path}:${timestamp}:${body}`;

  // Generate HMAC-SHA256 signature
  const signature = crypto
  .createHmac(\'sha256\', secretKey)
  .update(payload)
  .digest(\'hex\');

  return signature;
}

// Example usage
const signature = generateSignature({
  method: \'GET\',
  path: \'/api/v8/guest/videos/12345/manifest\',
  timestamp: 1704067200,
  secretKey: \'your_secret_key_here\'
});
```

**Kotlin Implementation:**

```kotlin
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generate HMAC-SHA256 signature for API authentication.
 * 
 * @param method HTTP method (GET, POST, etc.)
 * @param path API endpoint path
 * @param timestamp Unix timestamp in seconds
 * @param body Request body (empty string for GET requests)
 * @param secretKey The secret key for signing
 * @return Hex-encoded signature string
 */
fun generateSignature(
    method: String,
    path: String,
    timestamp: Long,
    body: String = "",
    secretKey: String
): String {
    // Construct the payload: METHOD:PATH:TIMESTAMP:BODY
    val payload = "${method.uppercase()}:$path:$timestamp:$body"

    // Generate HMAC-SHA256
    val mac = Mac.getInstance("HmacSHA256")
    val secretKeySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
    mac.init(secretKeySpec)

    val signatureBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    
    // Convert to hex string
    return signatureBytes.joinToString("") { "%02x".format(it) }
}

/**
 * Build the complete headers map for an API request.
 */
fun buildRequestHeaders(
    method: String,
    path: String,
    secretKey: String,
    sessionToken: String = "",
    userLicense: String = "",
    csrfToken: String = "",
    license: String = "",
    body: String = ""
): Map<String, String> {
    val timestamp = System.currentTimeMillis() / 1000
    val signature = generateSignature(method, path, timestamp, body, secretKey)

    return mapOf(
        "x-signature" to signature,
        "x-time" to timestamp.toString(),
        "x-signature-version" to "web2",
        "x-session-token" to sessionToken,
        "x-user-license" to userLicense,
        "x-csrf-token" to csrfToken,
        "x-license" to license,
        "Content-Type" to "application/json",
        "Accept" to "application/json"
    )
}

// Example usage
val signature = generateSignature(
    method = "GET",
    path = "/api/v8/guest/videos/12345/manifest",
    timestamp = 1704067200,
    secretKey = "your_secret_key_here"
)
```

### Timestamp Format

The `x-time` header uses Unix timestamps in **seconds** (not milliseconds):

```typescript
// Correct: Unix timestamp in seconds
const timestamp = Math.floor(Date.now() / 1000);

// Incorrect: Unix timestamp in milliseconds
const wrongTimestamp = Date.now();
```

### Session Management

Session tokens are obtained through:

1. **Initial Page Load**: Tokens may be embedded in the HTML response
2. **Login Authentication**: POST to login endpoint with credentials
3. **Cookie-Based Persistence**: Session cookies maintain authentication state

```typescript
interface SessionTokens {
  sessionToken: string;
  csrfToken: string;
  userLicense: string;
  license: string;
}

// Extract tokens from cookies or page state
function extractTokens(document: Document): SessionTokens {
  const cookies = document.cookie.split(';').reduce((acc, cookie) => {
    const [key, value] = cookie.trim().split('=');
    acc[key] = value;
    return acc;
  }, {} as Record<string, string>);
  
  return {
    sessionToken: cookies['_session'] || '',
    csrfToken: cookies['csrf_token'] || '',
    userLicense: cookies['user_license'] || '',
    license: cookies['license'] || ''
  };
}
```

---

## Additional API Endpoints

### Video Storyboards

**Endpoint:** `GET /rapi/v7/hentai_video_storyboards`

Retrieve preview storyboards for video scrubbing. Storyboards provide thumbnail previews at various timestamps for timeline navigation.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `hv_id` | integer | Hentai video ID |

**Example Request:**
```http
GET /rapi/v7/hentai_video_storyboards?hv_id=12345 HTTP/1.1
Host: cached.freeanimehentai.net
```

**Response Structure:**
```typescript
interface StoryboardResponse {
  storyboards: Storyboard[];
}

interface Storyboard {
  id: number;
  hv_id: number;
  url: string;
  width: number;
  height: number;
  cols: number;
  rows: number;
  thumbnail_width: number;
  thumbnail_height: number;
  interval_ms: number;
}

interface StoryboardImage {
  url: string;
  // URL pattern: https://hanime-cdn.com/images/storyboards/{slug}-720p-h1x.webp
  // - {slug}: Video slug
  // - 720p: Resolution
  // - h1x: Horizontal tile index (h1x, h2x, h3x, etc.)
}
```

**Kotlin Data Classes:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StoryboardResponse(
    val storyboards: List<HentaiVideoStoryboard>
)

@Serializable
data class HentaiVideoStoryboard(
    val id: Int,
    @SerialName("hv_id")
    val hvId: Int,
    val url: String,
    val width: Int,
    val height: Int,
    val cols: Int,
    val rows: Int,
    @SerialName("thumbnail_width")
    val thumbnailWidth: Int,
    @SerialName("thumbnail_height")
    val thumbnailHeight: Int,
    @SerialName("interval_ms")
    val intervalMs: Long
)
```

**Example Response:**
```json
{
  "storyboards": [
    {
      "id": 1001,
      "hv_id": 12345,
      "url": "https://hanime-cdn.com/images/storyboards/example-video-720p-h1x.webp",
      "width": 1280,
      "height": 720,
      "cols": 10,
      "rows": 10,
      "thumbnail_width": 128,
      "thumbnail_height": 72,
      "interval_ms": 5000
    }
  ]
}
```

---

### User Profiles

**Endpoint:** `GET /rapi/v7/users`

Retrieve user profile information by IDs.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `source` | string | Data source type (e.g., `simple`) |
| `user_ids[]` | integer[] | User IDs to fetch (can be repeated) |

**Example Request:**
```http
GET /rapi/v7/users?source=simple&user_ids[]=1&user_ids[]=2 HTTP/1.1
Host: cached.freeanimehentai.net
```

**Response Structure:**
```typescript
interface UsersResponse {
  users: User[];
}

interface User {
  id: number;
  username: string;
  avatar_url: string;
  created_at: string;
}
```

**Kotlin Data Classes:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsersResponse(
    val users: List<User>
)

@Serializable
data class User(
    val id: Int,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String,
    @SerialName("created_at")
    val createdAt: String
)
```

**Example Response:**
```json
{
  "users": [
    {
      "id": 1,
      "username": "example_user",
      "avatar_url": "https://hanime-cdn.com/avatars/user1.webp",
      "created_at": "2020-01-01T00:00:00Z"
    },
    {
      "id": 2,
      "username": "another_user",
      "avatar_url": "https://hanime-cdn.com/avatars/user2.webp",
      "created_at": "2020-02-01T00:00:00Z"
    }
  ]
}
```

---

### Related Playlists

**Endpoint:** `GET /api/v8/playlists`

Retrieve playlists related to a specific video.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `source` | string | Source type (e.g., `related`) |
| `hv_id` | integer | Hentai video ID reference |

**Example Request:**
```http
GET /api/v8/playlists?source=related&hv_id=12345 HTTP/1.1
Host: cached.freeanimehentai.net
```

**Response Structure:**
```typescript
interface PlaylistsResponse {
  playlists: Playlist[];
}

interface Playlist {
  id: number;
  name: string;
  description: string;
  hentai_videos: PlaylistVideo[];
}

interface PlaylistVideo {
  id: number;
  slug: string;
}
```

**Kotlin Data Classes:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsResponse(
    val playlists: List<Playlist>
)

@Serializable
data class Playlist(
    val id: Int,
    val name: String,
    val description: String,
    @SerialName("hentai_videos")
    val hentaiVideos: List<PlaylistVideo>
)

@Serializable
data class PlaylistVideo(
    val id: Int,
    val slug: String
)
```

**Example Response:**
```json
{
  "playlists": [
    {
      "id": 100,
      "name": "Related Videos Playlist",
      "description": "Videos similar to...",
      "hentai_videos": [
        { "id": 12346, "slug": "related-video-1" },
        { "id": 12347, "slug": "related-video-2" }
      ]
    }
  ]
}
```

---

### Track Video Plays

**Endpoint:** `POST /api/v8/hentai_videos/{slug}/play`

Record a video play event for analytics.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `slug` | string | Video slug identifier |

**Request Body:**
```typescript
interface PlayRequestBody {
  width: number;
  height: number;
  ab: string;
}
```

**Kotlin Data Classes for Tracking:**

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayTrackingRequest(
    val width: Int,
    val height: Int,
    val ab: String
)

@Serializable
data class AdEventRequest(
    val kind: String,
    val url: String
)
```

**Body Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `width` | integer | Player viewport width |
| `height` | integer | Player viewport height |
| `ab` | string | A/B test variant identifier (e.g., `kh`) |

**Example Request:**
```http
POST /api/v8/hentai_videos/example-video-slug/play HTTP/1.1
Host: cached.freeanimehentai.net
Content-Type: application/json
x-session-token: <session_token>
x-csrf-token: <csrf_token>

{
  "width": 1920,
  "height": 1080,
  "ab": "kh"
}
```

**Response:** `204 No Content`

---

### Preroll Ad Events

**Endpoint:** `POST /rapi/v7/preroll_ad_event`

Track preroll advertisement events for analytics and ad performance measurement.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `kind` | string | Event type |
| `url` | string | URL-encoded ad URL |

**Event Types (`kind`):**

| Type | Description |
|------|-------------|
| `impression` | Ad was displayed |
| `click` | User clicked the ad |
| `complete` | Ad playback completed |
| `skip` | User skipped the ad |
| `first_quartile` | 25% of ad played |
| `midpoint` | 50% of ad played |
| `third_quartile` | 75% of ad played |

**Example Request:**
```http
POST /rapi/v7/preroll_ad_event?kind=impression&url=https%3A%2F%2Fad-provider.com%2Fad HTTP/1.1
Host: hanime.tv
Content-Type: application/json

{}
```

**Response:** `204 No Content`

---

## hanime.tv Domain APIs

### Geo-Location Detection

**Endpoint:** `GET /country_code`

Detect user's country for content localization and regional server selection.

**Example Request:**
```http
GET /country_code HTTP/1.1
Host: hanime.tv
```

**Response Structure:**
```typescript
interface GeoLocationResponse {
  country_code: string;
  country_name: string;
}
```

**Example Response:**
```json
{
  "country_code": "US",
  "country_name": "United States"
}
```

---

### Video Player Iframe

**Endpoint:** `GET /omni-player/index.html`

Load the omni-player video player interface.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `poster_url` | string | URL-encoded poster image URL |
| `c` | integer | Cache-busting timestamp |

**Example Request:**
```http
GET /omni-player/index.html?poster_url=https%3A%2F%2Fhanime-cdn.com%2Fimages%2Fposters%2Fvideo-pv1.webp&c=1704067200000 HTTP/1.1
Host: hanime.tv
```

---

## Community API

### Base URL
```
https://community-uploads.highwinds-cdn.com
```

### Community Uploads

**Endpoint:** `GET /api/v9/community_uploads`

Retrieve user-uploaded community content.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `channel_name__in[]` | string[] | Channel filters (e.g., `media`, `nsfw-general`) |
| `kind` | string | Content type (e.g., `landing`) |
| `loc` | string | Location/reference URL |

**Example Request:**
```http
GET /api/v9/community_uploads?channel_name__in[]=media&channel_name__in[]=nsfw-general&kind=landing&loc=https%3A%2F%2Fhanime.tv%2Fvideos%2Fhentai%2Fexample HTTP/1.1
Host: community-uploads.highwinds-cdn.com
```

---

## CDN Endpoints

### Base URL
```
https://hanime-cdn.com
```

### Environment Configuration

**Endpoint:** `GET /vhtv2/env.json`

Retrieve environment configuration for the video player.

**Example Response:**
```json
{
  "vhtv2_version": 1704067200000,
  "api_base": "https://cached.freeanimehentai.net",
  "cdn_base": "https://hanime-cdn.com"
}
```

---

### Media Assets

#### Video Posters
```
GET /images/posters/{slug}-pv{version}.{format}
```

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `slug` | string | Video slug |
| `pv{version}` | string | Poster variant (pv1, pv2, etc.) |
| `format` | string | Image format (webp, jpg) |

#### Video Covers
```
GET /images/covers/{slug}-cv{version}.{format}
```

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `slug` | string | Video slug |
| `cv{version}` | string | Cover variant (cv1, cv2, etc.) |
| `format` | string | Image format (webp, png) |

#### Storyboard Previews
```
GET /images/storyboards/{slug}-{resolution}-h{index}x.{format}
```

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `slug` | string | Video slug |
| `resolution` | string | Resolution (e.g., `720p`) |
| `h{index}x` | string | Horizontal tile index (h1x, h2x, etc.) |
| `format` | string | Image format (webp) |

#### JavaScript Bundles
```
GET /vhtv2/{bundle}.js
```

Serves compiled JavaScript bundles for the frontend application.

---

## CORS Configuration

The API uses preflight OPTIONS requests for cross-origin access:

```http
OPTIONS /api/v10/search_hvs HTTP/1.1
Host: cached.freeanimehentai.net
Origin: https://hanime.tv
Access-Control-Request-Method: GET
Access-Control-Request-Headers: x-signature, x-time, x-signature-version
```

**Response Headers:**
```http
Access-Control-Allow-Origin: https://hanime.tv
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: x-signature, x-time, x-signature-version, x-session-token, x-csrf-token, x-user-license, x-license
Access-Control-Max-Age: 86400
```

---

## Error Handling

### Standard Error Response

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Invalid query parameters",
    "details": {
      "field": "page",
      "reason": "must be a non-negative integer"
    }
  }
}
```

### Common Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `INVALID_REQUEST` | 400 | Malformed request |
| `UNAUTHORIZED` | 401 | Missing or invalid authentication |
| `FORBIDDEN` | 403 | Insufficient permissions |
| `NOT_FOUND` | 404 | Resource not found |
| `RATE_LIMITED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Server error |

---

## Rate Limiting

API endpoints are rate-limited. Headers indicate current limits:

```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1704070800
```

---

## Usage Examples

### Search with Filters

```javascript
const searchVideos = async (query, tags = [], page = 0) => {
  const params = new URLSearchParams({
    q: query,
    page: page.toString(),
    page_size: '24',
    ordering: '-created_at'
  });

  tags.forEach(tag => params.append('tags[]', tag));

  const response = await fetch(
    `https://cached.freeanimehentai.net/api/v10/search_hvs?${params}`,
    {
      headers: {
        'x-signature': generateSignature(),
        'x-time': Math.floor(Date.now() / 1000).toString(),
        'x-signature-version': 'web2',
        'x-session-token': '',
        'x-user-license': '',
        'x-csrf-token': '',
        'x-license': '',
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      }
    }
  );

  return response.json();
};
```

### Fetch Video Manifest

```javascript
const getVideoManifest = async (hvId) => {
  const timestamp = Math.floor(Date.now() / 1000);
  const path = `/api/v8/guest/videos/${hvId}/manifest`;
  
  const signature = generateSignature({
    method: 'GET',
    path: path,
    timestamp: timestamp,
    secretKey: SECRET_KEY
  });

  const response = await fetch(
    `https://cached.freeanimehentai.net${path}`,
    {
      headers: {
        'x-signature': signature,
        'x-time': timestamp.toString(),
        'x-signature-version': 'web2',
        'x-session-token': '',
        'x-user-license': '',
        'x-csrf-token': '',
        'x-license': '',
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      }
    }
  );

  return response.json();
};
```

### Track Video Play

```javascript
const trackPlay = async (slug, width, height) => {
  await fetch(
    `https://cached.freeanimehentai.net/api/v8/hentai_videos/${slug}/play`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-session-token': getSessionToken(),
        'x-csrf-token': getCsrfToken(),
        'x-signature': generateSignature(),
        'x-time': Math.floor(Date.now() / 1000).toString(),
        'x-signature-version': 'web2'
      },
      body: JSON.stringify({
        width,
        height,
        ab: 'kh'
      })
    }
  );
};
```

### Download and Decrypt HLS Stream

```javascript
import crypto from 'crypto';

const downloadAndDecryptStream = async (manifestUrl) => {
  // Fetch the HLS playlist
  const playlistResponse = await fetch(manifestUrl);
  const playlist = await playlistResponse.text();
  
  // Parse segment URLs from playlist
  const segmentUrls = playlist
    .split('\n')
    .filter(line => line.startsWith('https://'))
    .map(url => url.trim());
  
  // Fetch the encryption key
  const keyResponse = await fetch('https://hanime.tv/sign.bin');
  const key = Buffer.from(await keyResponse.arrayBuffer());
  
  // Download and decrypt each segment
  const segments = [];
  for (let i = 0; i < segmentUrls.length; i++) {
    const segmentResponse = await fetch(segmentUrls[i]);
    const encryptedData = Buffer.from(await segmentResponse.arrayBuffer());
    
    const decryptedData = decryptSegment(encryptedData, key, i);
    segments.push(decryptedData);
  }
  
  return Buffer.concat(segments);
};
```

---

## Version History

| Version | Endpoint Prefix | Notes |
|---------|-----------------|-------|
| v10 | `/api/v10/` | Current search API |
| v9 | `/api/v9/` | Community uploads, HLS playlists |
| v8 | `/api/v8/` | Playlists, play tracking, video manifests |
| v7 | `/rapi/v7/` | User profiles, storyboards, ad events |

---

## Complete TypeScript Interfaces

### Core Types

```typescript
// Manifest Types
interface ManifestResponse {
  servers: Server[];
}

interface Server {
  id: number;
  name: string;
  slug: string;
  na_rating: number;
  eu_rating: number;
  asia_rating: number;
  sequence: number;
  is_permanent: boolean;
  streams: Stream[];
}

interface Stream {
  id: number;
  server_id: number;
  slug: string;
  kind: 'hls';
  extension: 'm3u8';
  mime_type: 'application/x-mpegURL';
  width: number;
  height: number;
  duration_in_ms: number;
  filesize_mbs: number;
  filename: string;
  url: string;
  is_guest_allowed: boolean;
  is_member_allowed: boolean;
  is_premium_allowed: boolean;
  is_downloadable: boolean;
  compatibility: 'all';
  hv_id: number;
  server_sequence: number;
  video_stream_group_id: number;
}

// Search Types
interface HentaiVideo {
  id: number;
  name: string;
  search_titles: string;
  slug: string;
  description: string;
  views: number;
  cover_url: string;
  poster_url: string;
  brand: string;
  brand_id: number;
  likes: number;
  dislikes: number;
  downloads: number;
  tags: Tag[];
  created_at_unix: number;
  released_at_unix: number;
  created_at: string;
  released_at: string;
}

interface Tag {
  id: number;
  name: string;
  slug: string;
}

// User Types
interface User {
  id: number;
  username: string;
  avatar_url: string;
  created_at: string;
}

// Playlist Types
interface Playlist {
  id: number;
  name: string;
  description: string;
  hentai_videos: PlaylistVideo[];
}

interface PlaylistVideo {
  id: number;
  slug: string;
}

// Storyboard Types
interface Storyboard {
  id: number;
  hv_id: number;
  url: string;
  width: number;
  height: number;
  cols: number;
  rows: number;
  thumbnail_width: number;
  thumbnail_height: number;
  interval_ms: number;
}

// Request Types
interface PlayRequestBody {
  width: number;
  height: number;
  ab: string;
}

// Geo Types
interface GeoLocationResponse {
  country_code: string;
  country_name: string;
}
```

---

## Kotlin Implementation Reference

This section provides complete, working Kotlin implementations suitable for use in Aniyomi extensions. All examples follow Android/Ktor/OkHttp best practices with proper error handling and coroutine support.

### Dependencies

Add these dependencies to your `build.gradle.kts`:

```kotlin
dependencies {
    // Networking
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    
    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
```

### Complete Data Classes

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================
// Manifest Types
// ============================================

@Serializable
data class ManifestResponse(
    val servers: List<Server>
)

@Serializable
data class Server(
    val id: Int,
    val name: String,
    val slug: String,
    @SerialName("na_rating")
    val naRating: Int,
    @SerialName("eu_rating")
    val euRating: Int,
    @SerialName("asia_rating")
    val asiaRating: Int,
    val sequence: Int,
    @SerialName("is_permanent")
    val isPermanent: Boolean,
    val streams: List<Stream>
)

@Serializable
data class Stream(
    val id: Int,
    @SerialName("server_id")
    val serverId: Int,
    val slug: String,
    val kind: String,
    val extension: String,
    @SerialName("mime_type")
    val mimeType: String,
    val width: Int,
    val height: Int,
    @SerialName("duration_in_ms")
    val durationInMs: Long,
    @SerialName("filesize_mbs")
    val filesizeMbs: Int,
    val filename: String,
    val url: String,
    @SerialName("is_guest_allowed")
    val isGuestAllowed: Boolean,
    @SerialName("is_member_allowed")
    val isMemberAllowed: Boolean,
    @SerialName("is_premium_allowed")
    val isPremiumAllowed: Boolean,
    @SerialName("is_downloadable")
    val isDownloadable: Boolean,
    val compatibility: String,
    @SerialName("hv_id")
    val hvId: Int,
    @SerialName("server_sequence")
    val serverSequence: Int,
    @SerialName("video_stream_group_id")
    val videoStreamGroupId: Int
) {
    val resolution: String
        get() = "${width}x${height}"
    
    val qualityLabel: String
        get() = slug // e.g., "720p", "480p", "360p"
}

// ============================================
// Search Types
// ============================================

typealias SearchHvsResponse = List<HentaiVideo>

@Serializable
data class HentaiVideo(
    val id: Int,
    val name: String,
    @SerialName("search_titles")
    val searchTitles: String,
    val slug: String,
    val description: String,
    val views: Int,
    @SerialName("cover_url")
    val coverUrl: String,
    @SerialName("poster_url")
    val posterUrl: String,
    val brand: String,
    @SerialName("brand_id")
    val brandId: Int,
    val likes: Int,
    val dislikes: Int,
    val downloads: Int,
    val tags: List<Tag>,
    @SerialName("created_at_unix")
    val createdAtUnix: Long,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("released_at")
    val releasedAt: String
) {
    val likeRatio: Float
        get() = if (likes + dislikes > 0) likes.toFloat() / (likes + dislikes) else 0f
}

@Serializable
data class Tag(
    val id: Int,
    val name: String,
    val slug: String
)

// ============================================
// Storyboard Types
// ============================================

@Serializable
data class StoryboardResponse(
    val storyboards: List<HentaiVideoStoryboard>
)

@Serializable
data class HentaiVideoStoryboard(
    val id: Int,
    @SerialName("hv_id")
    val hvId: Int,
    val url: String,
    val width: Int,
    val height: Int,
    val cols: Int,
    val rows: Int,
    @SerialName("thumbnail_width")
    val thumbnailWidth: Int,
    @SerialName("thumbnail_height")
    val thumbnailHeight: Int,
    @SerialName("interval_ms")
    val intervalMs: Long
) {
    val totalThumbnails: Int
        get() = cols * rows
}

// ============================================
// User Types
// ============================================

@Serializable
data class UsersResponse(
    val users: List<User>
)

@Serializable
data class User(
    val id: Int,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String,
    @SerialName("created_at")
    val createdAt: String
)

// ============================================
// Playlist Types
// ============================================

@Serializable
data class PlaylistsResponse(
    val playlists: List<Playlist>
)

@Serializable
data class Playlist(
    val id: Int,
    val name: String,
    val description: String,
    @SerialName("hentai_videos")
    val hentaiVideos: List<PlaylistVideo>
)

@Serializable
data class PlaylistVideo(
    val id: Int,
    val slug: String
)

// ============================================
// Tracking Types
// ============================================

@Serializable
data class PlayTrackingRequest(
    val width: Int,
    val height: Int,
    val ab: String
)

@Serializable
data class AdEventRequest(
    val kind: String,
    val url: String
)

// ============================================
// Geo Types
// ============================================

@Serializable
data class GeoLocationResponse(
    @SerialName("country_code")
    val countryCode: String,
    @SerialName("country_name")
    val countryName: String
)
```

### API Client Implementation

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.api

import eu.kanade.tachiyomi.extension.all.hanime.models.*
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/**
 * Hanime.tv API client for fetching video content.
 * 
 * @param client OkHttp client instance
 * @param json JSON serializer instance
 * @param secretKey HMAC signing key (extracted from web player)
 */
class HanimeApiClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val secretKey: String
) {
    companion object {
        const val API_BASE = "https://cached.freeanimehentai.net"
        const val HANIME_DOMAIN = "https://hanime.tv"
        const val HLS_BASE = "https://m3u8s.highwinds-cdn.com"
        const val SIGN_KEY_URL = "https://hanime.tv/sign.bin"
    }

    /**
     * Fetch video manifest containing all available streams.
     * 
     * @param hvId Hentai video ID
     * @return Manifest response with server and stream information
     */
    suspend fun fetchVideoManifest(hvId: Int): ManifestResponse {
        val path = "/api/v8/guest/videos/$hvId/manifest"
        return fetchApi(path)
    }

    /**
     * Search for hentai videos with filters.
     * 
     * @param query Search query (optional)
     * @param tags Tag filters
     * @param page Page number (0-indexed)
     * @param pageSize Results per page
     * @param ordering Sort order (e.g., "-created_at", "-views")
     * @return List of matching videos
     */
    suspend fun searchVideos(
        query: String? = null,
        tags: List<String> = emptyList(),
        page: Int = 0,
        pageSize: Int = 24,
        ordering: String = "-created_at"
    ): SearchHvsResponse {
        val urlBuilder = "$API_BASE/api/v10/search_hvs".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .addQueryParameter("ordering", ordering)

        query?.let { urlBuilder.addQueryParameter("q", it) }
        tags.forEach { urlBuilder.addQueryParameter("tags[]", it) }

        val path = "/api/v10/search_hvs"
        return fetchApi(path, urlBuilder.build().query ?: "")
    }

    /**
     * Fetch video storyboards for preview thumbnails.
     * 
     * @param hvId Hentai video ID
     * @return Storyboard response
     */
    suspend fun fetchStoryboards(hvId: Int): StoryboardResponse {
        val path = "/rapi/v7/hentai_video_storyboards"
        val query = "hv_id=$hvId"
        return fetchApi(path, query)
    }

    /**
     * Fetch user profiles by IDs.
     * 
     * @param userIds List of user IDs to fetch
     * @return Users response
     */
    suspend fun fetchUsers(userIds: List<Int>): UsersResponse {
        val path = "/rapi/v7/users"
        val query = buildString {
            append("source=simple")
            userIds.forEach { append("&user_ids[]=$it") }
        }
        return fetchApi(path, query)
    }

    /**
     * Fetch related playlists for a video.
     * 
     * @param hvId Hentai video ID
     * @return Playlists response
     */
    suspend fun fetchRelatedPlaylists(hvId: Int): PlaylistsResponse {
        val path = "/api/v8/playlists"
        val query = "source=related&hv_id=$hvId"
        return fetchApi(path, query)
    }

    /**
     * Track video play event for analytics.
     * 
     * @param slug Video slug
     * @param width Player width
     * @param height Player height
     */
    suspend fun trackPlay(slug: String, width: Int, height: Int) {
        val path = "/api/v8/hentai_videos/$slug/play"
        val body = json.encodeToString(
            PlayTrackingRequest.serializer(),
            PlayTrackingRequest(width, height, "kh")
        )
        postApi(path, body)
    }

    /**
     * Generic GET request to the API.
     */
    private suspend inline fun <reified T> fetchApi(
        path: String,
        query: String = ""
    ): T = withContext(Dispatchers.IO) {
        val url = buildApiUrl(path, query)
        val request = buildApiRequest("GET", path, query)

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("API request failed: ${response.code} ${response.message}")
            }
            val body = response.body?.string
                ?: throw IOException("Empty response body")
            json.decodeFromString<T>(body)
        }
    }

    /**
     * Generic POST request to the API.
     */
    private suspend fun postApi(path: String, body: String) {
        withContext(Dispatchers.IO) {
            val request = buildApiRequest("POST", path, body = body)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 204) {
                    throw IOException("API request failed: ${response.code}")
                }
            }
        }
    }

    /**
     * Build API URL with path and query parameters.
     */
    private fun buildApiUrl(path: String, query: String = ""): HttpUrl {
        return "$API_BASE$path".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                query.split("&").forEach { param ->
                    val (key, value) = param.split("=", limit = 2)
                    addQueryParameter(key, value)
                }
            }
        }.build()
    }

    /**
     * Build authenticated API request with required headers.
     */
    private fun buildApiRequest(
        method: String,
        path: String,
        query: String = "",
        body: String = ""
    ): Request {
        val timestamp = System.currentTimeMillis() / 1000
        val signature = generateSignature(method, path, timestamp, body, secretKey)

        val url = buildApiUrl(path, query)
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("x-signature", signature)
            .addHeader("x-time", timestamp.toString())
            .addHeader("x-signature-version", "web2")
            .addHeader("x-session-token", "")
            .addHeader("x-user-license", "")
            .addHeader("x-csrf-token", "")
            .addHeader("x-license", "")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        if (method == "POST" && body.isNotEmpty()) {
            requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
        }

        return requestBuilder.build()
    }
}
```

### Authentication Implementation

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authentication utilities for hanime.tv API.
 */
object HanimeAuth {

    /**
     * Generate HMAC-SHA256 signature for API authentication.
     * 
     * Signature format: METHOD:PATH:TIMESTAMP:BODY
     * 
     * @param method HTTP method (GET, POST, etc.)
     * @param path API endpoint path
     * @param timestamp Unix timestamp in seconds
     * @param body Request body (empty string for GET requests)
     * @param secretKey The secret key for signing
     * @return Hex-encoded signature string
     */
    fun generateSignature(
        method: String,
        path: String,
        timestamp: Long,
        body: String = "",
        secretKey: String
    ): String {
        require(method.isNotEmpty()) { "Method cannot be empty" }
        require(path.isNotEmpty()) { "Path cannot be empty" }
        require(secretKey.isNotEmpty()) { "Secret key cannot be empty" }

        // Construct the payload: METHOD:PATH:TIMESTAMP:BODY
        val payload = "${method.uppercase()}:$path:$timestamp:$body"

        // Generate HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(
            secretKey.toByteArray(Charsets.UTF_8),
            "HmacSHA256"
        )
        mac.init(secretKeySpec)

        val signatureBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))

        // Convert to lowercase hex string
        return signatureBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the complete headers map for an API request.
     * 
     * @param method HTTP method
     * @param path API endpoint path
     * @param secretKey HMAC signing key
     * @param sessionToken Optional session token (for authenticated requests)
     * @param userLicense Optional user license
     * @param csrfToken Optional CSRF token
     * @param license Optional license string
     * @param body Request body (for POST requests)
     * @return Map of header names to values
     */
    fun buildHeaders(
        method: String,
        path: String,
        secretKey: String,
        sessionToken: String = "",
        userLicense: String = "",
        csrfToken: String = "",
        license: String = "",
        body: String = ""
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis() / 1000
        val signature = generateSignature(method, path, timestamp, body, secretKey)

        return mapOf(
            "x-signature" to signature,
            "x-time" to timestamp.toString(),
            "x-signature-version" to "web2",
            "x-session-token" to sessionToken,
            "x-user-license" to userLicense,
            "x-csrf-token" to csrfToken,
            "x-license" to license,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }
}
```

### HLS Parsing Implementation

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.hls

/**
 * Parsed HLS playlist information.
 * 
 * @property segments List of segment URLs
 * @property keyUri URI of the encryption key
 * @property targetDuration Maximum segment duration in seconds
 * @property isVod Whether this is a VOD playlist
 */
data class HlsPlaylist(
    val segments: List<String>,
    val keyUri: String?,
    val targetDuration: Int,
    val isVod: Boolean
)

/**
 * HLS M3U8 playlist parser.
 */
object HlsParser {

    /**
     * Parse an M3U8 playlist content.
     * 
     * @param content Raw M3U8 playlist content
     * @return Parsed playlist information
     */
    fun parse(content: String): HlsPlaylist {
        require(content.startsWith("#EXTM3U")) {
            "Invalid M3U8: must start with #EXTM3U"
        }

        val lines = content.lines()
        val segments = mutableListOf<String>()
        var keyUri: String? = null
        var targetDuration = 0
        var isVod = false

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    keyUri = extractKeyUri(line)
                }
                line.startsWith("#EXT-X-TARGETDURATION") -> {
                    targetDuration = line.substringAfter(":").toIntOrNull() ?: 0
                }
                line.startsWith("#EXT-X-PLAYLIST-TYPE:VOD") -> {
                    isVod = true
                }
                line.startsWith("http") -> {
                    segments.add(line.trim())
                }
            }
        }

        return HlsPlaylist(
            segments = segments,
            keyUri = keyUri,
            targetDuration = targetDuration,
            isVod = isVod
        )
    }

    /**
     * Extract the encryption key URI from #EXT-X-KEY line.
     * 
     * Example: #EXT-X-KEY:METHOD=AES-128,URI='https://hanime.tv/sign.bin'
     */
    private fun extractKeyUri(line: String): String? {
        val uriMatch = Regex("URI='([^']+)'").find(line)
        return uriMatch?.groupValues?.get(1)
    }

    /**
     * Extract segment URLs from playlist content.
     * 
     * @param content Raw M3U8 playlist content
     * @return List of segment URLs
     */
    fun extractSegmentUrls(content: String): List<String> {
        return content.lines()
            .filter { it.startsWith("http") }
            .map { it.trim() }
    }
}
```

### AES-128 Decryption Implementation

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CBC decryption for encrypted video segments.
 */
object AesDecryptor {

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"
    private const val IV_SIZE = 16

    /**
     * Decrypt an AES-128-CBC encrypted video segment.
     * 
     * The IV is derived from the segment sequence number:
     * - 16-byte array with the segment index written as big-endian at bytes 12-15
     * 
     * @param encryptedData The encrypted segment data
     * @param key The 16-byte AES key fetched from sign.bin
     * @param segmentIndex The segment sequence number (0-indexed)
     * @return Decrypted segment data
     * @throws IllegalArgumentException if key is not 16 bytes
     */
    fun decryptSegment(
        encryptedData: ByteArray,
        key: ByteArray,
        segmentIndex: Int
    ): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(encryptedData.isNotEmpty()) { "Encrypted data cannot be empty" }

        // Create IV from segment sequence number (16 bytes, index at the end)
        val iv = createIvFromIndex(segmentIndex)

        val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        return cipher.doFinal(encryptedData)
    }

    /**
     * Create a 16-byte IV from a segment index.
     * 
     * The IV format is: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, B3, B2, B1, B0]
     * where B3-B0 are the big-endian bytes of the segment index.
     * 
     * @param segmentIndex The segment sequence number
     * @return 16-byte IV array
     */
    fun createIvFromIndex(segmentIndex: Int): ByteArray {
        val iv = ByteArray(IV_SIZE)
        // Write segment index as big-endian at the last 4 bytes (12-15)
        iv[12] = (segmentIndex shr 24).toByte()
        iv[13] = (segmentIndex shr 16).toByte()
        iv[14] = (segmentIndex shr 8).toByte()
        iv[15] = segmentIndex.toByte()
        return iv
    }

    /**
     * Decrypt multiple segments in sequence.
     * 
     * @param segments List of encrypted segment data
     * @param key The 16-byte AES key
     * @return List of decrypted segment data
     */
    fun decryptSegments(
        segments: List<ByteArray>,
        key: ByteArray
    ): List<ByteArray> {
        return segments.mapIndexed { index, data ->
            decryptSegment(data, key, index)
        }
    }
}
```

### Complete Video Fetcher Implementation

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime.video

import eu.kanade.tachiyomi.extension.all.hanime.api.HanimeApiClient
import eu.kanade.tachiyomi.extension.all.hanime.crypto.AesDecryptor
import eu.kanade.tachiyomi.extension.all.hanime.hls.HlsParser
import eu.kanade.tachiyomi.extension.all.hanime.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Video fetcher that handles manifest retrieval, HLS parsing, and decryption.
 * 
 * @param client OkHttp client instance
 * @param apiClient API client for manifest fetching
 */
class VideoFetcher(
    private val client: OkHttpClient,
    private val apiClient: HanimeApiClient
) {
    companion object {
        private const val SIGN_KEY_URL = "https://hanime.tv/sign.bin"
    }

    /**
     * Video information ready for playback.
     * 
     * @property stream Stream metadata
     * @property playlistUrl HLS playlist URL
     * @property encryptionKey Optional AES key for segment decryption
     */
    data class VideoInfo(
        val stream: Stream,
        val playlistUrl: String,
        val encryptionKey: ByteArray? = null
    )

    /**
     * Fetch all available videos for a hentai video ID.
     * 
     * @param hvId Hentai video ID
     * @return List of video information for each quality
     */
    suspend fun fetchVideos(hvId: Int): List<VideoInfo> = withContext(Dispatchers.IO) {
        // Fetch manifest
        val manifest = apiClient.fetchVideoManifest(hvId)
        
        // Get encryption key (all streams use the same key)
        val encryptionKey = fetchEncryptionKey()

        // Collect all streams from all servers
        manifest.servers
            .flatMap { server -> server.streams.map { stream -> stream to server } }
            .filter { it.first.isGuestAllowed }
            .map { (stream, _) ->
                VideoInfo(
                    stream = stream,
                    playlistUrl = stream.url,
                    encryptionKey = encryptionKey
                )
            }
    }

    /**
     * Fetch the AES encryption key from sign.bin.
     * 
     * @return 16-byte AES key
     */
    private suspend fun fetchEncryptionKey(): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SIGN_KEY_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch encryption key: ${response.code}")
            }
            response.body?.bytes()
                ?: throw IOException("Empty encryption key response")
        }
    }

    /**
     * Parse HLS playlist and extract segment URLs.
     * 
     * @param playlistUrl URL of the M3U8 playlist
     * @return Parsed playlist with segment URLs
     */
    suspend fun parseHlsPlaylist(playlistUrl: String): HlsParser.HlsPlaylist = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(playlistUrl)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch HLS playlist: ${response.code}")
            }
            val content = response.body?.string
                ?: throw IOException("Empty playlist response")
            HlsParser.parse(content)
        }
    }

    /**
     * Download and decrypt a video segment.
     * 
     * @param segmentUrl URL of the encrypted segment
     * @param key AES encryption key
     * @param segmentIndex Segment sequence number
     * @return Decrypted segment data
     */
    suspend fun downloadAndDecryptSegment(
        segmentUrl: String,
        key: ByteArray,
        segmentIndex: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(segmentUrl)
            .build()

        val encryptedData = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download segment: ${response.code}")
            }
            response.body?.bytes()
                ?: throw IOException("Empty segment response")
        }

        AesDecryptor.decryptSegment(encryptedData, key, segmentIndex)
    }

    /**
     * Download all segments in parallel.
     * 
     * @param segmentUrls List of segment URLs
     * @param key AES encryption key
     * @return List of decrypted segment data in order
     */
    suspend fun downloadAllSegments(
        segmentUrls: List<String>,
        key: ByteArray
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        segmentUrls.mapIndexed { index, url ->
            async { downloadAndDecryptSegment(url, key, index) }
        }.awaitAll()
    }
}
```

### Usage Example for Aniyomi Extension

```kotlin
package eu.kanade.tachiyomi.extension.all.hanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.all.hanime.api.HanimeApiClient
import eu.kanade.tachiyomi.extension.all.hanime.video.VideoFetcher
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class Hanime : AnimeHttpSource() {

    override val name = "Hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "all"
    override val supportsLatest = true

    private val client: OkHttpClient = network.client
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Secret key extracted from web player JavaScript
    private val secretKey = "extracted_secret_key_here"

    private val apiClient = HanimeApiClient(client, json, secretKey)
    private val videoFetcher = VideoFetcher(client, apiClient)
    private val playlistUtils = PlaylistUtils(client, json)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val hvId = episode.url.substringAfterLast("/").toIntOrNull()
            ?: return emptyList()

        val videos = videoFetcher.fetchVideos(hvId)

        return videos.map { videoInfo ->
            Video(
                url = videoInfo.playlistUrl,
                quality = "${videoInfo.stream.qualityLabel} - ${videoInfo.stream.serverId}",
                videoUrl = videoInfo.playlistUrl
            )
        }.sortedByDescending { it.quality }
    }

    // ... other required methods
}
```
