# hanime.tv CDN Infrastructure Documentation

This document provides comprehensive documentation of all CDN infrastructure used by hanime.tv, including internal and third-party content delivery networks.

---

## Section 1: CDN Overview

hanime.tv employs a distributed CDN architecture with multiple specialized content delivery networks optimized for different asset types.

### CDN Hierarchy

| CDN Domain | Primary Purpose | Asset Types |
|------------|-----------------|-------------|
| `hanime-cdn.com` | Primary static CDN | Images, fonts, JavaScript bundles, player assets |
| `htv-hydaelyn.com` | Video streaming CDN | Encrypted video segments |
| `m3u8s.highwinds-cdn.com` | HLS playlist CDN | HLS manifest files (.m3u8) |
| `community-uploads.highwinds-cdn.com` | Community uploads CDN | Discord-sourced user images |
| `cdnjs.cloudflare.com` | Third-party CDN | video.js, axios libraries |
| `jsdelivr.net` | Third-party CDN | Vue.js framework |
| `fonts.googleapis.com` | Third-party CDN | Google Fonts |
| `vjs.zencdn.net` | Third-party CDN | video.js core |
| `imasdk.googleapis.com` | Third-party CDN | Google IMA SDK |

### CDN Distribution Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     hanime.tv Platform                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  hanime-cdn.com │    │ htv-hydaelyn.com│                    │
│  │  (Static Assets)│    │ (Video Segments)│                    │
│  │                 │    │                 │                    │
│  │  /images/*      │    │  /segs/b0/2/*   │                    │
│  │  /fonts/*       │    │  (AES-128)      │                    │
│  │  /vhtv2/*       │    │                 │                    │
│  │  /omni-player/* │    │                 │                    │
│  └────────┬────────┘    └────────┬────────┘                    │
│           │                      │                              │
│  ┌────────┴────────┐    ┌────────┴────────┐                    │
│  │ highwinds-cdn.com│    │ Third-Party CDNs│                    │
│  │                 │    │                 │                    │
│  │ /m3u8s/*        │    │ cdnjs.cloudflare│                    │
│  │ /community_*    │    │ jsdelivr.net    │                    │
│  │                 │    │ fonts.googleapis│                    │
│  └─────────────────┘    └─────────────────┘                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Section 2: HANIME-CDN.COM

The primary static asset CDN serving all non-video media assets for the hanime.tv platform.

### Base URL

```
https://hanime-cdn.com
```

---

### Image Assets

#### Video Posters

Portrait-format poster images for video thumbnails and previews.

**URL Pattern:**

```
/images/posters/{slug}-pv{version}.{format}
```

**Parameters:**

| Parameter | Type | Description | Values |
|-----------|------|-------------|--------|
| `slug` | string | Video slug identifier | URL-safe slug |
| `version` | integer | Poster version number | `1`, `2`, `3`, etc. |
| `format` | string | Image format | `jpg`, `webp` |

**Examples:**

```
https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv1.jpg
https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv1.webp
https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv2.webp
```

**Kotlin Example:**

```kotlin
/**
 * Builds a poster image URL for video thumbnails.
 *
 * @param slug Video slug identifier (URL-safe)
 * @param version Poster version number (default: 1)
 * @param format Image format (default: webp)
 * @return Complete poster URL
 */
fun buildPosterUrl(
    slug: String,
    version: Int = 1,
    format: String = "webp"
): String {
    require(slug.isNotBlank()) { "Slug must not be blank" }
    require(version > 0) { "Version must be positive" }
    require(format in listOf("jpg", "webp")) { "Format must be jpg or webp" }

    return "https://hanime-cdn.com/images/posters/${slug}-pv${version}.${format}"
}

// Usage
val posterUrl = buildPosterUrl("mako-chan-kaihatsu-nikki-1", version = 1, format = "webp")
// https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv1.webp
```

---

#### Video Covers

Wide-format cover/banner images for video detail pages.

**URL Pattern:**

```
/images/covers/{slug}-cv{version}.{format}
```

**Parameters:**

| Parameter | Type | Description | Values |
|-----------|------|-------------|--------|
| `slug` | string | Video slug identifier | URL-safe slug |
| `version` | integer | Cover version number | `1`, `2`, `3`, etc. |
| `format` | string | Image format | `png`, `webp` |

**Examples:**

```
https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv1.png
https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv1.webp
https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv2.webp
```

**Kotlin Example:**

```kotlin
/**
 * Builds a cover image URL for video detail pages.
 *
 * @param slug Video slug identifier (URL-safe)
 * @param version Cover version number (default: 1)
 * @param format Image format (default: webp)
 * @return Complete cover URL
 */
fun buildCoverUrl(
    slug: String,
    version: Int = 1,
    format: String = "webp"
): String {
    require(slug.isNotBlank()) { "Slug must not be blank" }
    require(version > 0) { "Version must be positive" }
    require(format in listOf("png", "webp")) { "Format must be png or webp" }

    return "https://hanime-cdn.com/images/covers/${slug}-cv${version}.${format}"
}

// Usage
val coverUrl = buildCoverUrl("mako-chan-kaihatsu-nikki-1", version = 1, format = "png")
// https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv1.png
```

---

#### Storyboard Images

Preview storyboard sprites for video timeline scrubbing.

**URL Pattern:**

```
/images/storyboards/{slug}-{resolution}-h{index}x.{format}
```

**Parameters:**

| Parameter | Type | Description | Values |
|-----------|------|-------------|--------|
| `slug` | string | Video slug identifier | URL-safe slug |
| `resolution` | string | Video resolution | `720p`, `1080p` |
| `index` | integer | Horizontal tile index | `1`, `2`, `3`, etc. |
| `format` | string | Image format | `webp` |

**Examples:**

```
https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-720p-h1x.webp
https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-720p-h2x.webp
https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-1080p-h1x.webp
```

**Kotlin Example:**

```kotlin
/**
 * Supported video quality resolutions for storyboards.
 */
enum class VideoQuality(val value: String) {
    QUALITY_720P("720p"),
    QUALITY_1080P("1080p")
}

/**
 * Builds a storyboard image URL for timeline scrubbing.
 *
 * @param slug Video slug identifier
 * @param quality Video resolution quality
 * @param index Horizontal tile index (1-based)
 * @return Complete storyboard URL
 */
fun buildStoryboardUrl(
    slug: String,
    quality: VideoQuality,
    index: Int
): String {
    require(slug.isNotBlank()) { "Slug must not be blank" }
    require(index > 0) { "Index must be positive (1-based)" }

    return "https://hanime-cdn.com/images/storyboards/${slug}-${quality.value}-h${index}x.webp"
}

// Usage
val storyboardUrl = buildStoryboardUrl(
    slug = "mako-chan-kaihatsu-nikki-1",
    quality = VideoQuality.QUALITY_720P,
    index = 1
)
// https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-720p-h1x.webp
```

---

#### Tag Images

Tag category promotional images.

**URL Pattern:**

```
/images/tags/{tag}-vertical.min.jpg
```

**Parameters:**

| Parameter | Type | Description | Values |
|-----------|------|-------------|--------|
| `tag` | string | Tag slug identifier | URL-safe tag slug |

**Examples:**

```
https://hanime-cdn.com/images/tags/ahegao-vertical.min.jpg
https://hanime-cdn.com/images/tags/big-boobs-vertical.min.jpg
https://hanime-cdn.com/images/tags/creampie-vertical.min.jpg
```

**Kotlin Example:**

```kotlin
/**
 * Builds a tag promotional image URL.
 *
 * @param tagName Tag slug identifier (URL-safe)
 * @return Complete tag image URL
 */
fun buildTagUrl(tagName: String): String {
    require(tagName.isNotBlank()) { "Tag name must not be blank" }

    return "https://hanime-cdn.com/images/tags/${tagName}-vertical.min.jpg"
}

// Usage
val tagUrl = buildTagUrl("ahegao")
// https://hanime-cdn.com/images/tags/ahegao-vertical.min.jpg
```

---

### JavaScript Bundles

#### Main Application Bundles

**URL Pattern:**

```
/vhtv2/{hash}.js
```

**Known Bundle Hashes:**

| Bundle Hash | Description |
|-------------|-------------|
| `ef036f2.js` | Main application chunk |
| `a37eda4.js` | Vendor utilities |
| `b28452f.js` | Core framework |
| `40c99ce.js` | UI components |
| `c1eb2c5.js` | Router configuration |
| `61b74ab.js` | State management |
| `f8e4cc2.js` | API client |

**Examples:**

```
https://hanime-cdn.com/vhtv2/ef036f2.js
https://hanime-cdn.com/vhtv2/a37eda4.js
https://hanime-cdn.com/vhtv2/b28452c.js
```

---

#### Vendor Bundle

Third-party library bundle.

**URL Pattern:**

```
/js/vendor.min.js
```

**Example:**

```
https://hanime-cdn.com/js/vendor.min.js
```

---

#### Environment Configuration

**URL Pattern:**

```
/vhtv2/env.json
```

**Response Structure:**

```typescript
interface EnvironmentConfig {
  vhtv2_version: number; // Unix timestamp (milliseconds)
}
```

**Example Response:**

```json
{
  "vhtv2_version": 1704067200000
}
```

**Example Request:**

```http
GET /vhtv2/env.json HTTP/1.1
Host: hanime-cdn.com
```

---

### Font Assets

#### Whitney Font Family

Custom Discord-style Whitney font weights.

**URL Pattern:**

```
/fonts/whitney-{weight}.woff
```

**Available Weights:**

| Weight | Description |
|--------|-------------|
| `300` | Light |
| `400` | Regular |
| `500` | Medium |
| `600` | Semibold |
| `700` | Bold |

**Examples:**

```
https://hanime-cdn.com/fonts/whitney-300.woff
https://hanime-cdn.com/fonts/whitney-400.woff
https://hanime-cdn.com/fonts/whitney-500.woff
https://hanime-cdn.com/fonts/whitney-600.woff
https://hanime-cdn.com/fonts/whitney-700.woff
```

---

### Player Assets (omni-player)

The omni-player video player assets are hosted at a dedicated path.

**Base Path:**

```
/omni-player/
```

#### Player JavaScript

**URL Pattern:**

```
/omni-player/js/app.{hash}.js
```

**Example:**

```
https://hanime-cdn.com/omni-player/js/app.a1b2c3d4.js
```

#### Player CSS

**URL Pattern:**

```
/omni-player/css/app.{hash}.css
```

**Example:**

```
https://hanime-cdn.com/omni-player/css/app.a1b2c3d4.css
```

---

## Section 3: HTV-HYDAELYN.COM (Video Segments)

The video segment CDN serves encrypted HLS video segments for streaming playback.

### Base URL

```
https://p{server_id}.htv-hydaelyn.com
```

---

### URL Pattern

**Full Pattern:**

```
https://p{server_id}.htv-hydaelyn.com/{hv_id_path}/{video_stream_group_id}/segs/b0/2/{segment_number}.html
```

**URL Components:**

| Component | Description | Example |
|-----------|-------------|---------|
| `p{server_id}` | Server identifier prefix | `p34`, `p14` |
| `{hv_id_path}` | Video ID split into path segments | `3/4/2/6` for hv_id 3426 |
| `{video_stream_group_id}` | Stream group identifier | `h1x`, `42` |
| `b0/2` | Quality/bitrate path segment | `b0/2` |
| `{segment_number}` | Zero-padded segment index | `0000`, `0001`, `0002` |
| `.html` | File extension | `.html` |

---

### URL Construction

#### hv_id Path Splitting

The `hv_id` is split into individual digits for the path:

```typescript
/**
 * Splits a video ID into a path structure
 * @param hvId - The hentai video ID
 * @returns Path string with digits separated by slashes
 */
function splitHvIdToPath(hvId: number): string {
  return hvId.toString().split('').join('/');
}

// Examples:
splitHvIdToPath(3426);  // Returns: "3/4/2/6"
splitHvIdToPath(12345); // Returns: "1/2/3/4/5"
splitHvIdToPath(789);   // Returns: "7/8/9"
```

#### Segment Number Formatting

Segment numbers are zero-padded to 4 digits:

```typescript
/**
 * Formats segment index as zero-padded string
 * @param segmentIndex - The segment index (0-based)
 * @returns Zero-padded segment number
 */
function formatSegmentNumber(segmentIndex: number): string {
  return segmentIndex.toString().padStart(4, '0');
}

// Examples:
formatSegmentNumber(0);   // Returns: "0000"
formatSegmentNumber(1);   // Returns: "0001"
formatSegmentNumber(42);  // Returns: "0042"
formatSegmentNumber(999); // Returns: "0999"
```

---

### Complete URL Examples

```
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0001.html
https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0002.html
https://p14.htv-hydaelyn.com/1/2/3/4/5/h2x/segs/b0/2/0000.html
```

**Kotlin Example:**

```kotlin
/**
 * Data class holding segment URL parameters.
 */
data class SegmentParams(
    val serverId: Int,
    val hvId: Int,
    val streamGroupId: String,
    val segmentIndex: Int,
    val qualityPath: String = "b0/2"
)

/**
 * Splits a video ID into a path structure.
 * Example: 3426 -> "3/4/2/6"
 */
private fun splitHvIdToPath(hvId: Int): String {
    return hvId.toString().toCharArray().joinToString("/")
}

/**
 * Formats segment index as zero-padded 4-digit string.
 * Example: 0 -> "0000", 42 -> "0042"
 */
private fun formatSegmentNumber(segmentIndex: Int): String {
    return segmentIndex.toString().padStart(4, '0')
}

/**
 * Builds a video segment URL for htv-hydaelyn.com.
 *
 * @param params Segment URL parameters
 * @return Complete segment URL
 */
fun buildSegmentUrl(params: SegmentParams): String {
    require(params.serverId > 0) { "Server ID must be positive" }
    require(params.hvId > 0) { "HV ID must be positive" }
    require(params.segmentIndex >= 0) { "Segment index must be non-negative" }
    require(params.streamGroupId.isNotBlank()) { "Stream group ID must not be blank" }

    val hvIdPath = splitHvIdToPath(params.hvId)
    val segmentNum = formatSegmentNumber(params.segmentIndex)

    return "https://p${params.serverId}.htv-hydaelyn.com/${hvIdPath}/${params.streamGroupId}/segs/${params.qualityPath}/${segmentNum}.html"
}

// Usage
val segmentUrl = buildSegmentUrl(
    SegmentParams(
        serverId = 34,
        hvId = 3426,
        streamGroupId = "h1x",
        segmentIndex = 0
    )
)
// https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html
```

---

### AES-128 Encryption

All video segments are encrypted using AES-128 encryption.

#### Encryption Key Retrieval

**Key URL:**

```
https://hanime.tv/sign.bin
```

**Key Properties:**

| Property | Value |
|----------|-------|
| Method | AES-128 |
| Key URI | `https://hanime.tv/sign.bin` |
| IV | Sequence number (default) |
| Key Format | Raw 16-byte binary key |

#### Decryption Implementation

```typescript
import crypto from 'crypto';

interface SegmentDecryptOptions {
  encryptedBuffer: Buffer;
  key: Buffer;
  segmentIndex: number;
}

/**
 * Decrypts an AES-128 encrypted video segment
 * @param options - Decryption options
 * @returns Decrypted segment buffer
 */
async function decryptSegment(
  options: SegmentDecryptOptions
): Promise<Buffer> {
  const { encryptedBuffer, key, segmentIndex } = options;

  // Create IV from segment sequence number
  const iv = Buffer.alloc(16);
  iv.writeUInt32BE(segmentIndex, 12);

  const decipher = crypto.createDecipheriv('aes-128-cbc', key, iv);
  const decrypted = Buffer.concat([
    decipher.update(encryptedBuffer),
    decipher.final()
  ]);

  return decrypted;
}

// Usage example
const keyResponse = await fetch('https://hanime.tv/sign.bin');
const key = Buffer.from(await keyResponse.arrayBuffer());

const segmentResponse = await fetch(
  'https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html'
);
const encryptedData = Buffer.from(await segmentResponse.arrayBuffer());

const decryptedSegment = await decryptSegment({
  encryptedBuffer: encryptedData,
  key: key,
  segmentIndex: 0
});
```

---

### TypeScript Interface

```typescript
interface SegmentUrlParams {
  serverId: number;
  hvId: number;
  streamGroupId: number | string;
  segmentIndex: number;
  qualityPath?: string;
}

/**
 * Constructs a video segment URL
 * @param params - Segment URL parameters
 * @returns Complete segment URL
 */
function buildSegmentUrl(params: SegmentUrlParams): string {
  const {
    serverId,
    hvId,
    streamGroupId,
    segmentIndex,
    qualityPath = 'b0/2'
  } = params;

  const hvIdPath = hvId.toString().split('').join('/');
  const segmentNum = segmentIndex.toString().padStart(4, '0');

  return `https://p${serverId}.htv-hydaelyn.com/${hvIdPath}/${streamGroupId}/segs/${qualityPath}/${segmentNum}.html`;
}

// Example usage
const segmentUrl = buildSegmentUrl({
  serverId: 34,
  hvId: 3426,
  streamGroupId: 'h1x',
  segmentIndex: 0
});
// Returns: https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html
```

---

## Section 4: HIGHWINDS CDN (HLS & Community)

Highwinds CDN provides HLS playlist delivery and community upload hosting.

### HLS Playlist CDN

#### Base URL

```
https://m3u8s.highwinds-cdn.com
```

#### URL Pattern

```
/api/v9/m3u8s/{hash}.m3u8
```

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `hash` | string | Unique playlist identifier hash |

**Example:**

```
https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/abc123def456ghi789.m3u8
```

**Kotlin Example:**

```kotlin
/**
 * Builds an HLS playlist URL for highwinds CDN.
 *
 * @param hash Unique playlist identifier hash
 * @return Complete HLS playlist URL
 */
fun buildHlsPlaylistUrl(hash: String): String {
    require(hash.isNotBlank()) { "Hash must not be blank" }

    return "https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/${hash}.m3u8"
}

// Usage
val hlsUrl = buildHlsPlaylistUrl("abc123def456")
// https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/abc123def456.m3u8
```

#### HLS Playlist Structure

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

---

### Community Uploads CDN

#### Base URL

```
https://community-uploads.highwinds-cdn.com
```

#### Endpoint

```
GET /api/v9/community_uploads
```

#### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `channel_name__in[]` | string[] | Channel filters (repeatable) |
| `kind` | string | Content type filter |
| `loc` | string | Location/reference URL (URL-encoded) |

**Common Channels:**

| Channel | Description |
|---------|-------------|
| `media` | General media uploads |
| `nsfw-general` | NSFW general content |

**Example Request:**

```http
GET /api/v9/community_uploads?channel_name__in[]=media&channel_name__in[]=nsfw-general&kind=landing&loc=https%3A%2F%2Fhanime.tv%2Fvideos%2Fhentai%2Fexample HTTP/1.1
Host: community-uploads.highwinds-cdn.com
```

#### Response Structure

```typescript
interface CommunityUploadResponse {
  proxy_url: string;       // CDN proxy URL for the image
  thumbnail_url: string;   // Thumbnail image URL
  width: number;           // Original image width
  height: number;          // Original image height
  filesize: number;        // File size in bytes
  created_at_unix: number; // Upload timestamp (Unix seconds)
  discord_channel_id: string;
  discord_message_id: string;
  discord_user_id: string;
}
```

#### Example Response

```json
[
  {
    "proxy_url": "https://community-uploads.highwinds-cdn.com/uploads/example123.webp",
    "thumbnail_url": "https://community-uploads.highwinds-cdn.com/thumbs/example123.webp",
    "width": 1920,
    "height": 1080,
    "filesize": 524288,
    "created_at_unix": 1704067200,
    "discord_channel_id": "123456789",
    "discord_message_id": "987654321",
    "discord_user_id": "111222333"
  }
]
```

---

### Video Stream Group Naming Convention

HLS streams use a standardized naming convention for quality levels:

| Naming Pattern | Resolution | Description |
|----------------|------------|-------------|
| `{slug}-h1x` | 720p | High quality (720p) |
| `{slug}-h2x` | 480p | Medium quality (480p) |
| `{slug}-h3x` | 360p | Low quality (360p) |

**Examples:**

```
mako-chan-kaihatsu-nikki-1-h1x  // 720p stream
mako-chan-kaihatsu-nikki-1-h2x  // 480p stream
mako-chan-kaihatsu-nikki-1-h3x  // 360p stream
```

---

## Section 5: Third-Party CDNs

hanime.tv relies on several third-party CDNs for external libraries and services.

### video.js (v7.20.2)

#### ZenCDN (Primary)

```
https://vjs.zencdn.net/7.20.2/video.min.js
https://vjs.zencdn.net/7.20.2/video-js.css
```

#### Cloudflare (Fallback)

```
https://cdnjs.cloudflare.com/ajax/libs/video.js/7.20.2/video.min.js
https://cdnjs.cloudflare.com/ajax/libs/video.js/7.20.2/video-js.min.css
```

---

### axios (v0.19.2)

```
https://cdnjs.cloudflare.com/ajax/libs/axios/0.19.2/axios.min.js
```

---

### Vue.js (v2.7.16)

#### Cloudflare

```
https://cdnjs.cloudflare.com/ajax/libs/vue/2.7.16/vue.min.js
https://cdnjs.cloudflare.com/ajax/libs/vue/2.7.16/vue.common.min.js
```

#### jsDelivr

```
https://cdn.jsdelivr.net/npm/vue@2.7.16/dist/vue.min.js
https://cdn.jsdelivr.net/npm/vue@2.7.16/dist/vue.runtime.min.js
```

---

### Google Fonts

#### API Endpoint

```
https://fonts.googleapis.com/css2?family={font_family}&display=swap
```

**Example:**

```
https://fonts.googleapis.com/css2?family=Roboto:wght@400;700&display=swap
```

#### Static Font Files

```
https://fonts.gstatic.com/s/{font}/{version}/{font_file}.woff2
```

---

### Cloudflare Analytics

```
https://static.cloudflareinsights.com/beacon.min.js
```

---

### Google IMA SDK

```
https://imasdk.googleapis.com/js/sdkloader/ima3.js
```

---

### Complete Third-Party CDN Reference Table

| Library | Version | CDN Provider | URL |
|---------|---------|--------------|-----|
| video.js | 7.20.2 | ZenCDN | `vjs.zencdn.net/7.20.2/video.min.js` |
| video.js | 7.20.2 | Cloudflare | `cdnjs.cloudflare.com/ajax/libs/video.js/7.20.2/` |
| axios | 0.19.2 | Cloudflare | `cdnjs.cloudflare.com/ajax/libs/axios/0.19.2/axios.min.js` |
| Vue.js | 2.7.16 | Cloudflare | `cdnjs.cloudflare.com/ajax/libs/vue/2.7.16/` |
| Vue.js | 2.7.16 | jsDelivr | `cdn.jsdelivr.net/npm/vue@2.7.16/` |
| Google Fonts | - | Google | `fonts.googleapis.com` |
| Font Files | - | Google | `fonts.gstatic.com` |
| Cloudflare Analytics | - | Cloudflare | `static.cloudflareinsights.com/beacon.min.js` |
| IMA SDK | - | Google | `imasdk.googleapis.com/js/sdkloader/ima3.js` |

---

## Section 6: CDN URL Patterns Reference

### Quick Reference Table

| Asset Type | CDN Domain | URL Pattern | Example |
|------------|------------|-------------|---------|
| Video Poster | `hanime-cdn.com` | `/images/posters/{slug}-pv{v}.{fmt}` | `.../posters/video-pv1.webp` |
| Video Cover | `hanime-cdn.com` | `/images/covers/{slug}-cv{v}.{fmt}` | `.../covers/video-cv1.png` |
| Storyboard | `hanime-cdn.com` | `/images/storyboards/{slug}-{res}-h{i}x.webp` | `.../video-720p-h1x.webp` |
| Tag Image | `hanime-cdn.com` | `/images/tags/{tag}-vertical.min.jpg` | `.../tags/ahegao-vertical.min.jpg` |
| App Bundle | `hanime-cdn.com` | `/vhtv2/{hash}.js` | `.../vhtv2/ef036f2.js` |
| Vendor Bundle | `hanime-cdn.com` | `/js/vendor.min.js` | `.../js/vendor.min.js` |
| Environment | `hanime-cdn.com` | `/vhtv2/env.json` | `.../vhtv2/env.json` |
| Font | `hanime-cdn.com` | `/fonts/whitney-{weight}.woff` | `.../fonts/whitney-400.woff` |
| Player JS | `hanime-cdn.com` | `/omni-player/js/app.{hash}.js` | `.../omni-player/js/app.abc.js` |
| Player CSS | `hanime-cdn.com` | `/omni-player/css/app.{hash}.css` | `.../omni-player/css/app.abc.css` |
| Video Segment | `htv-hydaelyn.com` | `/p{id}/{hv_path}/{grp}/segs/b0/2/{seg}.html` | `.../p34/3/4/2/6/h1x/segs/b0/2/0000.html` |
| HLS Playlist | `highwinds-cdn.com` | `/api/v9/m3u8s/{hash}.m3u8` | `.../m3u8s/abc123.m3u8` |
| Community | `highwinds-cdn.com` | `/api/v9/community_uploads` | `.../community_uploads?channel...` |

---

### TypeScript Interfaces for URL Construction

```typescript
// ============================================================
// CDN URL Builder - Complete TypeScript Implementation
// ============================================================

/**
 * CDN Domain Configuration
 */
interface CDNConfig {
  primary: string;
  videoSegments: string;
  hlsPlaylists: string;
  communityUploads: string;
}

const CDN_DOMAINS: CDNConfig = {
  primary: 'https://hanime-cdn.com',
  videoSegments: 'https://p{serverId}.htv-hydaelyn.com',
  hlsPlaylists: 'https://m3u8s.highwinds-cdn.com',
  communityUploads: 'https://community-uploads.highwinds-cdn.com'
};

/**
 * Image format types
 */
type ImageFormat = 'jpg' | 'png' | 'webp';

/**
 * Video quality levels
 */
type VideoQuality = '240p' | '360p' | '480p' | '720p' | '1080p';

/**
 * Font weight values
 */
type FontWeight = 300 | 400 | 500 | 600 | 700;

// ------------------------------------------------------------
// hanime-cdn.com URL Builders
// ------------------------------------------------------------

/**
 * Builds a poster image URL
 */
function buildPosterUrl(
  slug: string,
  version: number = 1,
  format: ImageFormat = 'webp'
): string {
  return `${CDN_DOMAINS.primary}/images/posters/${slug}-pv${version}.${format}`;
}

/**
 * Builds a cover image URL
 */
function buildCoverUrl(
  slug: string,
  version: number = 1,
  format: ImageFormat = 'webp'
): string {
  return `${CDN_DOMAINS.primary}/images/covers/${slug}-cv${version}.${format}`;
}

/**
 * Builds a storyboard image URL
 */
function buildStoryboardUrl(
  slug: string,
  resolution: VideoQuality,
  index: number
): string {
  return `${CDN_DOMAINS.primary}/images/storyboards/${slug}-${resolution}-h${index}x.webp`;
}

/**
 * Builds a tag image URL
 */
function buildTagUrl(tag: string): string {
  return `${CDN_DOMAINS.primary}/images/tags/${tag}-vertical.min.jpg`;
}

/**
 * Builds a JavaScript bundle URL
 */
function buildBundleUrl(hash: string): string {
  return `${CDN_DOMAINS.primary}/vhtv2/${hash}.js`;
}

/**
 * Builds the environment config URL
 */
function buildEnvConfigUrl(): string {
  return `${CDN_DOMAINS.primary}/vhtv2/env.json`;
}

/**
 * Builds a font URL
 */
function buildFontUrl(weight: FontWeight): string {
  return `${CDN_DOMAINS.primary}/fonts/whitney-${weight}.woff`;
}

/**
 * Builds a player asset URL
 */
function buildPlayerAssetUrl(
  type: 'js' | 'css',
  hash: string
): string {
  return `${CDN_DOMAINS.primary}/omni-player/${type === 'js' ? 'js' : 'css'}/app.${hash}.${type}`;
}

// ------------------------------------------------------------
// htv-hydaelyn.com URL Builders
// ------------------------------------------------------------

/**
 * Segment URL parameters
 */
interface SegmentUrlParams {
  serverId: number;
  hvId: number;
  streamGroupId: string | number;
  segmentIndex: number;
  qualityPath?: string;
}

/**
 * Builds a video segment URL
 */
function buildSegmentUrl(params: SegmentUrlParams): string {
  const {
    serverId,
    hvId,
    streamGroupId,
    segmentIndex,
    qualityPath = 'b0/2'
  } = params;

  const hvIdPath = hvId.toString().split('').join('/');
  const segmentNum = segmentIndex.toString().padStart(4, '0');

  return `https://p${serverId}.htv-hydaelyn.com/${hvIdPath}/${streamGroupId}/segs/${qualityPath}/${segmentNum}.html`;
}

/**
 * Generates all segment URLs for a video
 */
function generateAllSegmentUrls(
  params: Omit<SegmentUrlParams, 'segmentIndex'>,
  segmentCount: number
): string[] {
  return Array.from({ length: segmentCount }, (_, i) =>
    buildSegmentUrl({ ...params, segmentIndex: i })
  );
}

// ------------------------------------------------------------
// highwinds-cdn.com URL Builders
// ------------------------------------------------------------

/**
 * Builds an HLS playlist URL
 */
function buildHlsPlaylistUrl(hash: string): string {
  return `${CDN_DOMAINS.hlsPlaylists}/api/v9/m3u8s/${hash}.m3u8`;
}

/**
 * Community upload query parameters
 */
interface CommunityUploadParams {
  channels?: ('media' | 'nsfw-general')[];
  kind?: string;
  location?: string;
}

/**
 * Builds a community uploads API URL
 */
function buildCommunityUploadsUrl(params: CommunityUploadParams = {}): string {
  const searchParams = new URLSearchParams();

  if (params.channels) {
    params.channels.forEach(channel => {
      searchParams.append('channel_name__in[]', channel);
    });
  }

  if (params.kind) {
    searchParams.set('kind', params.kind);
  }

  if (params.location) {
    searchParams.set('loc', params.location);
  }

  const queryString = searchParams.toString();
  return `${CDN_DOMAINS.communityUploads}/api/v9/community_uploads${queryString ? `?${queryString}` : ''}`;
}

// ------------------------------------------------------------
// Third-Party CDN URL Builders
// ------------------------------------------------------------

/**
 * Third-party library URLs
 */
const ThirdPartyCDN = {
  videoJs: {
    primary: 'https://vjs.zencdn.net/7.20.2/video.min.js',
    fallback: 'https://cdnjs.cloudflare.com/ajax/libs/video.js/7.20.2/video.min.js',
    css: 'https://vjs.zencdn.net/7.20.2/video-js.css'
  },
  axios: 'https://cdnjs.cloudflare.com/ajax/libs/axios/0.19.2/axios.min.js',
  vue: {
    cloudflare: 'https://cdnjs.cloudflare.com/ajax/libs/vue/2.7.16/vue.min.js',
    jsdelivr: 'https://cdn.jsdelivr.net/npm/vue@2.7.16/dist/vue.min.js'
  },
  googleFonts: {
    css: (family: string) => `https://fonts.googleapis.com/css2?family=${family}&display=swap`
  },
  cloudflareAnalytics: 'https://static.cloudflareinsights.com/beacon.min.js',
  imaSdk: 'https://imasdk.googleapis.com/js/sdkloader/ima3.js'
};

// ============================================================
// Usage Examples
// ============================================================

// Poster URL
const posterUrl = buildPosterUrl('mako-chan-kaihatsu-nikki-1', 1, 'webp');
// https://hanime-cdn.com/images/posters/mako-chan-kaihatsu-nikki-1-pv1.webp

// Cover URL
const coverUrl = buildCoverUrl('mako-chan-kaihatsu-nikki-1', 1, 'png');
// https://hanime-cdn.com/images/covers/mako-chan-kaihatsu-nikki-1-cv1.png

// Storyboard URL
const storyboardUrl = buildStoryboardUrl('mako-chan-kaihatsu-nikki-1', '720p', 1);
// https://hanime-cdn.com/images/storyboards/mako-chan-kaihatsu-nikki-1-720p-h1x.webp

// Segment URL
const segmentUrl = buildSegmentUrl({
  serverId: 34,
  hvId: 3426,
  streamGroupId: 'h1x',
  segmentIndex: 0
});
// https://p34.htv-hydaelyn.com/3/4/2/6/h1x/segs/b0/2/0000.html

// HLS Playlist URL
const hlsUrl = buildHlsPlaylistUrl('abc123def456');
// https://m3u8s.highwinds-cdn.com/api/v9/m3u8s/abc123def456.m3u8

// Community Uploads URL
const communityUrl = buildCommunityUploadsUrl({
  channels: ['media', 'nsfw-general'],
  kind: 'landing',
  location: 'https://hanime.tv/videos/hentai/example'
});
// https://community-uploads.highwinds-cdn.com/api/v9/community_uploads?channel_name__in[]=media&channel_name__in[]=nsfw-general&kind=landing&loc=https%3A%2F%2Fhanime.tv%2Fvideos%2Fhentai%2Fexample
```

---

### JavaScript Module Export

```typescript
// cdn-urls.ts - Complete module export

export {
  // Configuration
  CDN_DOMAINS,
  CDNConfig,
  
  // Types
  ImageFormat,
  VideoQuality,
  FontWeight,
  SegmentUrlParams,
  CommunityUploadParams,
  
  // hanime-cdn.com builders
  buildPosterUrl,
  buildCoverUrl,
  buildStoryboardUrl,
  buildTagUrl,
  buildBundleUrl,
  buildEnvConfigUrl,
  buildFontUrl,
  buildPlayerAssetUrl,
  
  // htv-hydaelyn.com builders
  buildSegmentUrl,
  generateAllSegmentUrls,
  
  // highwinds-cdn.com builders
  buildHlsPlaylistUrl,
  buildCommunityUploadsUrl,
  
  // Third-party CDN URLs
  ThirdPartyCDN
};
```

---

## Cache Headers Reference

### hanime-cdn.com Cache Policy

```http
# Static images (posters, covers, storyboards, tags)
Cache-Control: public, max-age=31536000, immutable

# JavaScript bundles
Cache-Control: public, max-age=604800

# Fonts
Cache-Control: public, max-age=31536000, immutable

# Environment config
Cache-Control: no-cache

# Player assets
Cache-Control: public, max-age=86400
```

### htv-hydaelyn.com Cache Policy

```http
# Video segments
Cache-Control: public, max-age=86400
```

### highwinds-cdn.com Cache Policy

```http
# HLS playlists
Cache-Control: public, max-age=300

# Community uploads
Cache-Control: public, max-age=3600
```

---

## Error Handling

### Common CDN Error Codes

| HTTP Status | Description | Resolution |
|-------------|-------------|------------|
| 403 | Forbidden / Access Denied | Check referer headers, token validity |
| 404 | Asset Not Found | Verify URL pattern, asset existence |
| 502 | Bad Gateway | CDN server issue, retry with backoff |
| 503 | Service Unavailable | CDN overload, retry after delay |

### Retry Strategy

```typescript
/**
 * Fetches a CDN resource with automatic retry
 */
async function fetchWithRetry(
  url: string,
  options: RequestInit = {},
  maxRetries: number = 3
): Promise<Response> {
  const delays = [1000, 2000, 4000]; // Exponential backoff

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      const response = await fetch(url, options);
      
      if (response.ok) {
        return response;
      }
      
      // Don't retry on 4xx errors (except 429)
      if (response.status >= 400 && response.status < 500 && response.status !== 429) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      
      throw new Error(`HTTP ${response.status}`);
    } catch (error) {
      if (attempt === maxRetries - 1) {
        throw error;
      }
      
      await new Promise(resolve => setTimeout(resolve, delays[attempt]));
    }
  }

  throw new Error('Max retries exceeded');
}
```

---

## Kotlin CDN Utilities Reference

This section provides complete, production-ready Kotlin utilities for working with hanime.tv CDN infrastructure in Aniyomi extensions.

### CDN URL Builder Object

```kotlin
package eu.kanade.tachiyomi.animeextension.en.hanime.cdn

/**
 * Centralized CDN URL builder for hanime.tv infrastructure.
 * Provides type-safe URL construction for all CDN asset types.
 */
object HanimeCdn {

    // ============================================================
    // CDN Domain Configuration
    // ============================================================

    private const val PRIMARY_CDN = "https://hanime-cdn.com"
    private const val VIDEO_SEGMENT_CDN = "https://p%d.htv-hydaelyn.com"
    private const val HLS_PLAYLIST_CDN = "https://m3u8s.highwinds-cdn.com"
    private const val COMMUNITY_UPLOADS_CDN = "https://community-uploads.highwinds-cdn.com"

    // ============================================================
    // Image Format & Quality Types
    // ============================================================

    /**
     * Supported image formats for CDN assets.
     */
    enum class ImageFormat(val extension: String) {
        JPG("jpg"),
        PNG("png"),
        WEBP("webp");

        companion object {
            fun fromExtension(ext: String): ImageFormat =
                entries.find { it.extension.equals(ext, ignoreCase = true) } ?: WEBP
        }
    }

    /**
     * Video quality levels for storyboards and streams.
     */
    enum class VideoQuality(val resolution: String) {
        QUALITY_240P("240p"),
        QUALITY_360P("360p"),
        QUALITY_480P("480p"),
        QUALITY_720P("720p"),
        QUALITY_1080P("1080p");

        companion object {
            fun fromResolution(res: String): VideoQuality? =
                entries.find { it.resolution.equals(res, ignoreCase = true) }
        }
    }

    // ============================================================
    // hanime-cdn.com URL Builders
    // ============================================================

    /**
     * Builds a video poster image URL.
     *
     * @param slug Video slug identifier (URL-safe)
     * @param version Poster version number
     * @param format Image format
     * @return Complete poster URL
     * @throws IllegalArgumentException if slug is blank or version <= 0
     */
    fun buildPosterUrl(
        slug: String,
        version: Int = 1,
        format: ImageFormat = ImageFormat.WEBP
    ): String {
        require(slug.isNotBlank()) { "Slug must not be blank" }
        require(version > 0) { "Version must be positive" }

        return "$PRIMARY_CDN/images/posters/${slug}-pv${version}.${format.extension}"
    }

    /**
     * Builds a video cover/banner image URL.
     *
     * @param slug Video slug identifier
     * @param version Cover version number
     * @param format Image format
     * @return Complete cover URL
     */
    fun buildCoverUrl(
        slug: String,
        version: Int = 1,
        format: ImageFormat = ImageFormat.WEBP
    ): String {
        require(slug.isNotBlank()) { "Slug must not be blank" }
        require(version > 0) { "Version must be positive" }

        return "$PRIMARY_CDN/images/covers/${slug}-cv${version}.${format.extension}"
    }

    /**
     * Builds a storyboard sprite image URL for timeline scrubbing.
     *
     * @param slug Video slug identifier
     * @param quality Video resolution
     * @param index Horizontal tile index (1-based)
     * @return Complete storyboard URL
     */
    fun buildStoryboardUrl(
        slug: String,
        quality: VideoQuality,
        index: Int
    ): String {
        require(slug.isNotBlank()) { "Slug must not be blank" }
        require(index > 0) { "Index must be positive (1-based)" }

        return "$PRIMARY_CDN/images/storyboards/${slug}-${quality.resolution}-h${index}x.webp"
    }

    /**
     * Builds a tag promotional image URL.
     *
     * @param tagName Tag slug identifier
     * @return Complete tag image URL
     */
    fun buildTagUrl(tagName: String): String {
        require(tagName.isNotBlank()) { "Tag name must not be blank" }

        return "$PRIMARY_CDN/images/tags/${tagName}-vertical.min.jpg"
    }

    /**
     * Builds a JavaScript bundle URL.
     *
     * @param hash Bundle hash identifier
     * @return Complete bundle URL
     */
    fun buildBundleUrl(hash: String): String {
        require(hash.isNotBlank()) { "Bundle hash must not be blank" }

        return "$PRIMARY_CDN/vhtv2/${hash}.js"
    }

    /**
     * Builds the environment configuration URL.
     */
    fun buildEnvConfigUrl(): String = "$PRIMARY_CDN/vhtv2/env.json"

    /**
     * Builds a Whitney font URL.
     *
     * @param weight Font weight (300, 400, 500, 600, 700)
     * @return Complete font URL
     */
    fun buildFontUrl(weight: Int): String {
        require(weight in listOf(300, 400, 500, 600, 700)) {
            "Font weight must be 300, 400, 500, 600, or 700"
        }

        return "$PRIMARY_CDN/fonts/whitney-${weight}.woff"
    }

    /**
     * Builds an omni-player asset URL.
     *
     * @param type Asset type (js or css)
     * @param hash Asset hash
     * @return Complete player asset URL
     */
    fun buildPlayerAssetUrl(type: String, hash: String): String {
        require(type in listOf("js", "css")) { "Type must be 'js' or 'css'" }
        require(hash.isNotBlank()) { "Hash must not be blank" }

        val path = if (type == "js") "js" else "css"
        return "$PRIMARY_CDN/omni-player/${path}/app.${hash}.${type}"
    }

    // ============================================================
    // htv-hydaelyn.com URL Builders
    // ============================================================

    /**
     * Data class for video segment URL parameters.
     */
    data class SegmentParams(
        val serverId: Int,
        val hvId: Int,
        val streamGroupId: String,
        val segmentIndex: Int,
        val qualityPath: String = "b0/2"
    )

    /**
     * Splits a video ID into path segments.
     * Example: 3426 -> "3/4/2/6"
     */
    private fun splitHvIdToPath(hvId: Int): String =
        hvId.toString().toCharArray().joinToString("/")

    /**
     * Formats segment index as zero-padded 4-digit string.
     * Example: 0 -> "0000", 42 -> "0042"
     */
    private fun formatSegmentNumber(segmentIndex: Int): String =
        segmentIndex.toString().padStart(4, '0')

    /**
     * Builds a video segment URL for encrypted HLS streaming.
     *
     * @param params Segment parameters
     * @return Complete segment URL
     */
    fun buildSegmentUrl(params: SegmentParams): String {
        require(params.serverId > 0) { "Server ID must be positive" }
        require(params.hvId > 0) { "HV ID must be positive" }
        require(params.segmentIndex >= 0) { "Segment index must be non-negative" }
        require(params.streamGroupId.isNotBlank()) { "Stream group ID must not be blank" }

        val hvIdPath = splitHvIdToPath(params.hvId)
        val segmentNum = formatSegmentNumber(params.segmentIndex)
        val baseUrl = VIDEO_SEGMENT_CDN.format(params.serverId)

        return "$baseUrl/${hvIdPath}/${params.streamGroupId}/segs/${params.qualityPath}/${segmentNum}.html"
    }

    /**
     * Generates all segment URLs for a video.
     *
     * @param baseParams Base segment parameters (without segmentIndex)
     * @param segmentCount Total number of segments
     * @return List of segment URLs
     */
    fun generateAllSegmentUrls(
        baseParams: SegmentParams,
        segmentCount: Int
    ): List<String> {
        require(segmentCount > 0) { "Segment count must be positive" }

        return (0 until segmentCount).map { index ->
            buildSegmentUrl(baseParams.copy(segmentIndex = index))
        }
    }

    // ============================================================
    // highwinds-cdn.com URL Builders
    // ============================================================

    /**
     * Builds an HLS playlist URL.
     *
     * @param hash Playlist hash identifier
     * @return Complete HLS playlist URL
     */
    fun buildHlsPlaylistUrl(hash: String): String {
        require(hash.isNotBlank()) { "Hash must not be blank" }

        return "$HLS_PLAYLIST_CDN/api/v9/m3u8s/${hash}.m3u8"
    }

    /**
     * Community upload channel types.
     */
    enum class CommunityChannel(val value: String) {
        MEDIA("media"),
        NSFW_GENERAL("nsfw-general")
    }

    /**
     * Data class for community uploads query parameters.
     */
    data class CommunityUploadParams(
        val channels: List<CommunityChannel> = emptyList(),
        val kind: String? = null,
        val location: String? = null
    )

    /**
     * Builds a community uploads API URL with query parameters.
     *
     * @param params Query parameters
     * @return Complete API URL with query string
     */
    fun buildCommunityUploadsUrl(params: CommunityUploadParams = CommunityUploadParams()): String {
        val queryParts = mutableListOf<String>()

        params.channels.forEach { channel ->
            queryParts.add("channel_name__in[]=${channel.value}")
        }

        params.kind?.let { queryParts.add("kind=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        params.location?.let { 
            queryParts.add("loc=${java.net.URLEncoder.encode(it, "UTF-8")}") 
        }

        val queryString = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""

        return "$COMMUNITY_UPLOADS_CDN/api/v9/community_uploads$queryString"
    }
}
```

---

### Image Loading with Coil

```kotlin
package eu.kanade.tachiyomi.animeextension.en.hanime.util

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.InputStream

/**
 * Coil image loader configuration optimized for hanime CDN assets.
 * Implements aggressive caching for static CDN content with immutable assets.
 */
object HanimeImageLoader {

    /**
     * Creates an ImageLoader configured for hanime CDN.
     * 
     * @param client OkHttpClient instance for network requests
     * @return Configured ImageLoader
     */
    fun create(client: OkHttpClient): ImageLoader {
        return ImageLoader.Builder()
            .okHttpClient(client)
            .components {
                add(CdnImageFetcher.Factory())
            }
            .crossfade(true)
            .crossfade(300)
            .respectCacheHeaders(true)
            .build()
    }
}

/**
 * Custom fetcher for hanime CDN images with caching strategy.
 * Handles both WebP and PNG formats with proper MIME type detection.
 */
class CdnImageFetcher(
    private val url: String,
    private val client: OkHttpClient
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/webp,image/png,image/jpeg,*/*;q=0.8")
            .header("Accept-Encoding", "gzip, deflate")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw CdnImageException("Failed to fetch image: HTTP ${response.code}")
        }

        val body = response.body ?: throw CdnImageException("Empty response body")

        // Determine content type from URL or response headers
        val contentType = determineContentType(url, response.header("Content-Type"))

        return FetchResult(
            source = ImageSource(body.byteStream(), client.cache?.directory),
            mimeType = contentType,
            dataSource = DataSource.NETWORK
        )
    }

    private fun determineContentType(url: String, headerContentType: String?): String {
        // Priority: URL extension > Response header > Default
        return when {
            url.endsWith(".webp", ignoreCase = true) -> "image/webp"
            url.endsWith(".png", ignoreCase = true) -> "image/png"
            url.endsWith(".jpg", ignoreCase = true) || url.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            headerContentType != null -> headerContentType
            else -> "image/webp" // Default for hanime CDN
        }
    }

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.contains("hanime-cdn.com") && !data.contains("highwinds-cdn.com")) {
                return null
            }
            return CdnImageFetcher(data, imageLoader.okHttpClient ?: OkHttpClient())
        }
    }
}

/**
 * Exception for CDN image loading errors.
 */
class CdnImageException(message: String) : Exception(message)
```

---

### Video Segment Downloader with Coroutines

```kotlin
package eu.kanade.tachiyomi.animeextension.en.hanime.download

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Progress tracking for segment downloads.
 */
data class DownloadProgress(
    val totalSegments: Int,
    val completedSegments: Int,
    val bytesDownloaded: Long,
    val currentSegment: Int,
    val failedSegments: Set<Int>
) {
    val percentage: Double
        get() = if (totalSegments == 0) 0.0 else (completedSegments.toDouble() / totalSegments) * 100

    val isComplete: Boolean
        get() = completedSegments == totalSegments && failedSegments.isEmpty()
}

/**
 * Download result for a single segment.
 */
sealed interface SegmentDownloadResult {
    data class Success(
        val segmentIndex: Int,
        val file: File,
        val bytesWritten: Long
    ) : SegmentDownloadResult

    data class Failure(
        val segmentIndex: Int,
        val error: Throwable
    ) : SegmentDownloadResult
}

/**
 * Parallel video segment downloader with retry logic and progress tracking.
 * Uses coroutines for efficient concurrent downloads.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val outputDirectory: File,
    private val maxConcurrentDownloads: Int = 4,
    private val maxRetries: Int = 3
) {
    // Exponential backoff delays in milliseconds
    private val retryDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L)

    private val completedCount = AtomicInteger(0)
    private val totalBytes = AtomicLong(0L)
    private val failedSegments = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Downloads all video segments in parallel with progress tracking.
     *
     * @param segmentUrls List of segment URLs to download
     * @param onProgress Progress callback invoked on each segment completion
     * @return Flow of individual segment download results
     */
    fun downloadAll(
        segmentUrls: List<String>,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Flow<SegmentDownloadResult> = channelFlow {
        if (segmentUrls.isEmpty()) {
            close()
            return@channelFlow
        }

        val totalSegments = segmentUrls.size
        val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            // Create semaphore for concurrency control
            val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentDownloads)

            segmentUrls.mapIndexed { index, url ->
                launch(downloadScope) {
                    semaphore.acquire()
                    try {
                        val result = downloadSegmentWithRetry(index, url)

                        when (result) {
                            is SegmentDownloadResult.Success -> {
                                completedCount.incrementAndGet()
                                totalBytes.addAndGet(result.bytesWritten)
                            }
                            is SegmentDownloadResult.Failure -> {
                                failedSegments.add(result.segmentIndex)
                            }
                        }

                        send(result)

                        // Emit progress update
                        onProgress(
                            DownloadProgress(
                                totalSegments = totalSegments,
                                completedSegments = completedCount.get(),
                                bytesDownloaded = totalBytes.get(),
                                currentSegment = index,
                                failedSegments = failedSegments.toSet()
                            )
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }.joinAll()
        } finally {
            downloadScope.cancel()
        }
    }

    /**
     * Downloads a single segment with exponential backoff retry.
     *
     * @param segmentIndex Zero-based segment index
     * @param url Segment URL
     * @return Download result (success or failure)
     */
    private suspend fun downloadSegmentWithRetry(
        segmentIndex: Int,
        url: String
    ): SegmentDownloadResult {
        var lastException: Exception? = null

        repeat(maxRetries + 1) { attempt ->
            try {
                return downloadSegment(segmentIndex, url)
            } catch (e: Exception) {
                lastException = e

                // Don't retry on 4xx errors (except 429)
                if (e is HttpException && e.code in 400..499 && e.code != 429) {
                    break
                }

                // Apply exponential backoff before retry
                if (attempt < maxRetries) {
                    val delay = retryDelays.getOrElse(attempt) { 8_000L }
                    delay(delay)
                }
            }
        }

        return SegmentDownloadResult.Failure(
            segmentIndex = segmentIndex,
            error = lastException ?: Exception("Unknown error")
        )
    }

    /**
     * Downloads a single segment to file.
     *
     * @param segmentIndex Segment index for filename
     * @param url Segment URL
     * @return Success result with file and bytes written
     */
    private suspend fun downloadSegment(
        segmentIndex: Int,
        url: String
    ): SegmentDownloadResult.Success = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "gzip, deflate")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw Exception("Empty response body")

        // Create segment file with zero-padded index
        val segmentFile = File(outputDirectory, "segment_${segmentIndex.toString().padStart(4, '0')}.ts")
        var bytesWritten = 0L

        body.byteStream().use { input ->
            FileOutputStream(segmentFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead
                }
            }
        }

        SegmentDownloadResult.Success(
            segmentIndex = segmentIndex,
            file = segmentFile,
            bytesWritten = bytesWritten
        )
    }
}

/**
 * HTTP exception for non-successful responses.
 */
class HttpException(val code: Int, message: String) : Exception(message)
```

---

### Community Uploads API Client

```kotlin
package eu.kanade.tachiyomi.animeextension.en.hanime.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * API client for hanime.tv community uploads.
 * Fetches Discord-sourced user uploads from highwinds CDN.
 */
class CommunityUploadsClient(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val BASE_URL = "https://community-uploads.highwinds-cdn.com"
        private const val API_VERSION = "v9"
    }

    /**
     * Fetches community uploads with filters.
     *
     * @param channels Channel filters (media, nsfw-general)
     * @param kind Content type filter
     * @param location Reference URL for context
     * @return List of community uploads
     */
    suspend fun fetchUploads(
        channels: List<String> = emptyList(),
        kind: String? = null,
        location: String? = null
    ): List<CommunityUpload> {
        val urlBuilder = "$BASE_URL/api/$API_VERSION/community_uploads".toHttpUrl().newBuilder()

        // Add channel filters
        channels.forEach { channel ->
            urlBuilder.addQueryParameter("channel_name__in[]", channel)
        }

        // Add optional filters
        kind?.let { urlBuilder.addQueryParameter("kind", it) }
        location?.let { urlBuilder.addQueryParameter("loc", it) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip, deflate")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CommunityUploadsException("Failed to fetch uploads: HTTP ${response.code}")
            }

            val body = response.body ?: throw CommunityUploadsException("Empty response body")
            val responseText = body.string()

            json.decodeFromString<List<CommunityUpload>>(responseText)
        }
    }

    /**
     * Fetches uploads for a specific video page.
     *
     * @param videoUrl Full video page URL
     * @return List of community uploads for the video
     */
    suspend fun fetchUploadsForVideo(videoUrl: String): List<CommunityUpload> {
        return fetchUploads(
            channels = listOf("media", "nsfw-general"),
            kind = "landing",
            location = videoUrl
        )
    }
}

/**
 * Community upload response model.
 */
@Serializable
data class CommunityUpload(
    @SerialName("proxy_url")
    val proxyUrl: String,

    @SerialName("thumbnail_url")
    val thumbnailUrl: String? = null,

    val width: Int,
    val height: Int,
    val filesize: Long,

    @SerialName("created_at_unix")
    val createdAtUnix: Long,

    @SerialName("discord_channel_id")
    val discordChannelId: String,

    @SerialName("discord_message_id")
    val discordMessageId: String,

    @SerialName("discord_user_id")
    val discordUserId: String
) {
    /**
     * Returns the best available URL for display.
     */
    val displayUrl: String
        get() = thumbnailUrl ?: proxyUrl

    /**
     * Human-readable file size.
     */
    val fileSizeFormatted: String
        get() = when {
            filesize < 1024 -> "$filesize B"
            filesize < 1024 * 1024 -> "${filesize / 1024} KB"
            else -> String.format("%.1f MB", filesize / (1024.0 * 1024.0))
        }
}

/**
 * Exception for community uploads API errors.
 */
class CommunityUploadsException(message: String) : Exception(message)
```

---

### Usage Example: Complete Aniyomi Integration

```kotlin
package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animeextension.en.hanime.cdn.HanimeCdn
import eu.kanade.tachiyomi.animeextension.en.hanime.cdn.HanimeCdn.*
import eu.kanade.tachiyomi.animeextension.en.hanime.download.SegmentDownloader
import eu.kanade.tachiyomi.animeextension.en.hanime.api.CommunityUploadsClient
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient

/**
 * Example extension demonstrating CDN utility usage.
 */
class HanimeExtension : AnimePlugin {

    private val client = OkHttpClient.Builder().build()

    // ============================================================
    // CDN URL Building Examples
    // ============================================================

    fun buildThumbnailUrl(videoSlug: String): String {
        return HanimeCdn.buildPosterUrl(
            slug = videoSlug,
            version = 1,
            format = ImageFormat.WEBP
        )
    }

    fun buildBannerUrl(videoSlug: String): String {
        return HanimeCdn.buildCoverUrl(
            slug = videoSlug,
            version = 1,
            format = ImageFormat.PNG
        )
    }

    fun getStoryboardUrls(videoSlug: String): List<String> {
        return listOf(
            HanimeCdn.buildStoryboardUrl(videoSlug, VideoQuality.QUALITY_720P, 1),
            HanimeCdn.buildStoryboardUrl(videoSlug, VideoQuality.QUALITY_720P, 2),
            HanimeCdn.buildStoryboardUrl(videoSlug, VideoQuality.QUALITY_720P, 3)
        )
    }

    // ============================================================
    // Video Segment URL Examples
    // ============================================================

    fun getVideoSegmentUrls(
        serverId: Int,
        hvId: Int,
        streamGroup: String,
        segmentCount: Int
    ): List<String> {
        val baseParams = SegmentParams(
            serverId = serverId,
            hvId = hvId,
            streamGroupId = streamGroup,
            segmentIndex = 0
        )

        return HanimeCdn.generateAllSegmentUrls(baseParams, segmentCount)
    }

    // ============================================================
    // Download with Progress Example
    // ============================================================

    suspend fun downloadVideoSegments(
        segmentUrls: List<String>,
        outputDir: java.io.File
    ) {
        val downloader = SegmentDownloader(
            client = client,
            outputDirectory = outputDir,
            maxConcurrentDownloads = 4,
            maxRetries = 3
        )

        downloader.downloadAll(segmentUrls) { progress ->
            println("Progress: ${progress.percentage.toInt()}% (${progress.completedSegments}/${progress.totalSegments})")
            
            if (progress.failedSegments.isNotEmpty()) {
                println("Failed segments: ${progress.failedSegments}")
            }
        }.collect { result ->
            when (result) {
                is SegmentDownloadResult.Success -> {
                    println("Downloaded segment ${result.segmentIndex}: ${result.bytesWritten} bytes")
                }
                is SegmentDownloadResult.Failure -> {
                    println("Failed segment ${result.segmentIndex}: ${result.error.message}")
                }
            }
        }
    }

    // ============================================================
    // Community Uploads Example
    // ============================================================

    suspend fun getCommunityImages(videoUrl: String): List<String> {
        val apiClient = CommunityUploadsClient(client)

        val uploads = apiClient.fetchUploadsForVideo(videoUrl)

        return uploads.map { it.displayUrl }
    }
}
```
