# hanime.tv Data Models

This document defines the data structures and models used throughout the hanime.tv platform.

---

## Video Model

### Hentai Video

The primary content object representing a video.

```typescript
interface HentaiVideo {
  // Identifiers
  id: number;                    // Unique numeric identifier (also referenced as hv_id)
  slug: string;                  // URL-friendly identifier (e.g., "example-video-title")
  
  // Basic Information
  name: string;                  // Video title
  description: string;           // Full description (HTML supported)
  
  // Media URLs
  poster_url: string;            // Poster image URL
  cover_url: string;             // Cover/banner image URL
  
  // Statistics
  views: number;                 // Total view count
  downloads: number;             // Total download count
  likes: number;                 // Total like count
  
  // Timestamps
  created_at: string;            // ISO 8601 datetime when uploaded
  released_at: string;           // Release date (YYYY-MM-DD)
  
  // Duration
  duration_in_ms: number;        // Duration in milliseconds
  duration_string?: string;      // Human-readable duration (e.g., "20:30")
  
  // Status
  is_public: boolean;            // Public visibility flag
  is_censored?: boolean;         // Censorship status
  
  // Relationships
  brand: string | Brand;         // Studio/brand name or object
  tags: Tag[];                   // Associated tags
  
  // Extended Properties (optional)
  storyboards?: string[];        // Storyboard image URLs
  sources?: VideoSource[];       // Video source URLs by quality
  previews?: Preview[];          // Preview clip URLs
  
  // User Interaction
  is_liked?: boolean;            // Whether current user liked
  is_favorited?: boolean;        // Whether current user favorited
  playlist_ids?: number[];       // Associated playlist IDs
}
```

### Example Response

```json
{
  "id": 12345,
  "slug": "ojousama-gakuen-garçon-eres-tuhan-1",
  "name": "Ojousama Gakuen Garçon Eres Tuh An 1",
  "description": "<p>Video description with <strong>HTML</strong> formatting...</p>",
  "poster_url": "https://hanime-cdn.com/images/posters/ojousama-gakuen-garcon-eres-tuhan-1-pv1.webp",
  "cover_url": "https://hanime-cdn.com/images/covers/ojousama-gakuen-garcon-eres-tuhan-1-cv1.webp",
  "views": 250000,
  "downloads": 15000,
  "likes": 8500,
  "created_at": "2024-01-15T10:30:00Z",
  "released_at": "2024-01-15",
  "duration_in_ms": 1200000,
  "is_public": true,
  "brand": "Pink Pineapple",
  "tags": [
    {"id": 5, "name": "Ahegao", "slug": "ahegao"},
    {"id": 12, "name": "Big Boobs", "slug": "big-boobs"}
  ]
}
```

---

## User Model

### User

User account information.

```typescript
interface User {
  // Identifiers
  id: number;                    // Unique user ID
  username: string;              // Display name
  
  // Profile
  avatar_url?: string;           // Avatar image URL
  banner_url?: string;           // Profile banner URL
  bio?: string;                  // User biography
  
  // Timestamps
  created_at: string;            // Account creation datetime
  
  // Statistics
  uploads_count?: number;        // Number of uploads
  favorites_count?: number;      // Number of favorites
  playlists_count?: number;      // Number of playlists
  
  // Status
  is_verified?: boolean;         // Verified badge status
  is_premium?: boolean;          // Premium subscription status
  is_following?: boolean;        // Whether current user follows
  
  // Social
  followers_count?: number;      // Follower count
  following_count?: number;      // Following count
}
```

### Example Response

```json
{
  "id": 10042,
  "username": "example_user",
  "avatar_url": "https://hanime-cdn.com/avatars/10042.webp",
  "banner_url": "https://hanime-cdn.com/banners/10042.webp",
  "bio": "Anime enthusiast and content creator",
  "created_at": "2020-05-15T12:00:00Z",
  "uploads_count": 25,
  "favorites_count": 150,
  "playlists_count": 5,
  "is_verified": false,
  "is_premium": true,
  "followers_count": 1250,
  "following_count": 85
}
```

---

## Tag Model

### Tag

Classification tag for videos.

```typescript
interface Tag {
  id: number;                    // Unique tag ID
  name: string;                  // Display name
  slug: string;                  // URL-friendly identifier
  
  // Metadata
  description?: string;          // Tag description
  category?: string;             // Tag category (e.g., "fetish", "genre")
  video_count?: number;          // Number of videos with this tag
  
  // Hierarchy
  parent_id?: number;            // Parent tag ID (for nested tags)
  children?: Tag[];              // Child tags
}
```

### Example

```json
{
  "id": 5,
  "name": "Ahegao",
  "slug": "ahegao",
  "description": "Exaggerated facial expressions",
  "category": "fetish",
  "video_count": 5420
}
```

---

## Brand Model

### Brand

Studio or production company.

```typescript
interface Brand {
  id: number;                    // Unique brand ID
  name: string;                  // Brand/studio name
  slug: string;                  // URL-friendly identifier
  
  // Metadata
  description?: string;          // Brand description
  logo_url?: string;             // Brand logo URL
  
  // Statistics
  video_count?: number;          // Number of videos
  
  // Status
  is_active?: boolean;           // Whether still producing
}
```

### Example

```json
{
  "id": 15,
  "name": "Pink Pineapple",
  "slug": "pink-pineapple",
  "description": "Japanese animation studio",
  "logo_url": "https://hanime-cdn.com/brands/pink-pineapple.webp",
  "video_count": 850,
  "is_active": true
}
```

---

## Playlist Model

### Playlist

User-created video collection.

```typescript
interface Playlist {
  // Identifiers
  id: number;                    // Unique playlist ID
  name: string;                  // Playlist title
  slug?: string;                 // URL-friendly identifier
  
  // Metadata
  description?: string;          // Playlist description
  thumbnail_url?: string;        // Playlist thumbnail
  
  // Statistics
  video_count: number;           // Number of videos
  
  // Relationships
  user_id: number;               // Creator user ID
  user?: User;                   // Creator info
  hentai_videos: HentaiVideo[];  // Videos in playlist
  
  // Timestamps
  created_at: string;            // Creation datetime
  updated_at?: string;           // Last update datetime
  
  // Status
  is_public: boolean;            // Public visibility
  is_featured?: boolean;         // Featured status
  
  // Source Reference
  source?: string;               // Playlist source type (e.g., "related")
  hv_id?: number;                // Reference video ID for related playlists
}
```

### Example

```json
{
  "id": 500,
  "name": "Related to Example Video",
  "description": "Videos similar to...",
  "thumbnail_url": "https://hanime-cdn.com/images/posters/video-pv1.webp",
  "video_count": 12,
  "user_id": 1,
  "hentai_videos": [
    {"id": 12346, "slug": "related-video-1", "name": "Related Video 1"},
    {"id": 12347, "slug": "related-video-2", "name": "Related Video 2"}
  ],
  "created_at": "2024-01-01T00:00:00Z",
  "is_public": true,
  "source": "related",
  "hv_id": 12345
}
```

---

## Search Results Model

### Search Response

Paginated search results container.

```typescript
interface SearchResponse {
  // Results
  hentai_videos: HentaiVideo[];  // Array of videos
  
  // Pagination
  total_pages: number;           // Total number of pages
  total_count: number;           // Total results count
  current_page: number;          // Current page (0-indexed)
  page_size?: number;            // Results per page
  
  // Filters Applied
  applied_filters?: {
    query?: string;              // Search query
    tags?: string[];             // Tag slugs
    brands?: string[];           // Brand slugs
    ordering?: string;           // Sort order
  };
  
  // Aggregations
  available_tags?: Tag[];        // Tags found in results
  available_brands?: Brand[];    // Brands found in results
}
```

### Example

```json
{
  "hentai_videos": [
    {
      "id": 12345,
      "slug": "example-video",
      "name": "Example Video",
      "poster_url": "https://hanime-cdn.com/images/posters/example-pv1.webp",
      "views": 100000,
      "likes": 5000
    }
  ],
  "total_pages": 10,
  "total_count": 240,
  "current_page": 0,
  "page_size": 24,
  "applied_filters": {
    "query": "",
    "ordering": "-created_at"
  }
}
```

---

## Video Source Model

### Video Source

Quality-specific video URL.

```typescript
interface VideoSource {
  quality: string;               // Quality label (e.g., "720p")
  resolution: string;            // Resolution (e.g., "1280x720")
  url: string;                   // Video URL
  format: string;                // Format (e.g., "mp4")
  filesize?: number;             // File size in bytes
  bitrate?: number;              // Bitrate in kbps
  
  // CDN Info
  cdn?: string;                  // CDN hostname
  is_primary?: boolean;          // Primary source flag
}
```

### Example

```json
{
  "quality": "720p",
  "resolution": "1280x720",
  "url": "https://hanime-cdn.com/videos/example-720p.mp4",
  "format": "mp4",
  "filesize": 524288000,
  "bitrate": 4000,
  "cdn": "hanime-cdn.com",
  "is_primary": true
}
```

---

## Comment Model

### Comment

User comment on videos.

```typescript
interface Comment {
  // Identifiers
  id: number;                    // Unique comment ID
  
  // Content
  content: string;               // Comment text
  
  // Relationships
  user_id: number;               // Author user ID
  user: User;                    // Author info
  video_id: number;              // Video ID
  parent_id?: number;            // Parent comment ID (for replies)
  
  // Statistics
  likes_count: number;           // Like count
  replies_count: number;         // Reply count
  
  // Timestamps
  created_at: string;            // Creation datetime
  updated_at?: string;           // Last edit datetime
  
  // Status
  is_edited?: boolean;           // Edited flag
  is_deleted?: boolean;          // Soft delete flag
  is_pinned?: boolean;           // Pinned status
}
```

---

## Favorite Model

### Favorite

User favorite/like relationship.

```typescript
interface Favorite {
  id: number;                    // Unique favorite ID
  user_id: number;               // User ID
  video_id: number;              // Video ID
  created_at: string;            // When favorited
  
  // Populated
  video?: HentaiVideo;           // Full video object
}
```

---

## Community Upload Model

### Community Upload

User-generated content.

```typescript
interface CommunityUpload {
  // Identifiers
  id: number;                    // Unique upload ID
  
  // Content
  title: string;                 // Upload title
  description?: string;          // Description
  thumbnail_url: string;         // Thumbnail URL
  
  // Relationships
  user_id: number;               // Uploader ID
  user?: User;                   // Uploader info
  
  // Categorization
  channel_name: string;          // Channel (e.g., "media", "nsfw-general")
  kind: string;                  // Content type (e.g., "landing")
  loc?: string;                  // Reference URL
  
  // Statistics
  views: number;                 // View count
  likes: number;                 // Like count
  
  // Timestamps
  created_at: string;            // Upload datetime
  
  // Status
  is_approved: boolean;          // Moderation status
}
```

---

## Pagination Model

### Pagination Parameters

Standard pagination input.

```typescript
interface PaginationParams {
  page?: number;                 // Page number (0-indexed)
  page_size?: number;            // Results per page (default: 24)
  
  // Ordering
  ordering?: string;             // Sort field
  // Common values:
  // - "-created_at" : Newest first
  // - "-views"      : Most viewed
  // - "-likes"      : Most liked
  // - "name"        : Alphabetical
}
```

### Pagination Response

Standard pagination output.

```typescript
interface PaginationMeta {
  total_count: number;           // Total items
  total_pages: number;           // Total pages
  current_page: number;          // Current page
  page_size: number;             // Items per page
  has_next: boolean;             // Has next page
  has_previous: boolean;         // Has previous page
}
```

---

## Error Response Model

### API Error

Standard error format.

```typescript
interface APIError {
  error: {
    code: string;                // Error code identifier
    message: string;             // Human-readable message
    details?: {                  // Additional context
      field?: string;            // Affected field
      reason?: string;           // Detailed reason
      [key: string]: any;        // Additional details
    };
  };
}
```

### Example

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request parameters",
    "details": {
      "field": "page",
      "reason": "must be a non-negative integer"
    }
  }
}
```

---

## Enum Types

### Video Ordering

```typescript
type VideoOrdering = 
  | '-created_at'      // Newest first
  | '-views'           // Most viewed
  | '-likes'           // Most liked
  | '-downloads'       // Most downloaded
  | 'name'             // Alphabetical A-Z
  | '-name'            // Alphabetical Z-A
  | 'released_at'      // Oldest release first
  | '-released_at';    // Newest release first
```

### Video Quality

```typescript
type VideoQuality = 
  | '240p'
  | '360p'
  | '480p'
  | '720p'
  | '1080p';
```

### Ad Event Kind

```typescript
type AdEventKind = 
  | 'impression'
  | 'start'
  | 'first_quartile'
  | 'midpoint'
  | 'third_quartile'
  | 'complete'
  | 'click'
  | 'skip'
  | 'close'
  | 'pause'
  | 'resume'
  | 'mute'
  | 'unmute'
  | 'error';
```

---

## Utility Types

### Video With Duration String

```typescript
interface VideoWithDurationString extends HentaiVideo {
  duration_string: string;       // Formatted duration (e.g., "20:30")
}

// Helper function
function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  
  if (hours > 0) {
    return `${hours}:${String(minutes % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  }
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}
```

### Minimal Video (Search Result)

```typescript
interface MinimalVideo {
  id: number;
  slug: string;
  name: string;
  poster_url: string;
  views: number;
  likes: number;
  created_at: string;
}
```

---

## Type Guards

```typescript
// Check if object is HentaiVideo
function isHentaiVideo(obj: any): obj is HentaiVideo {
  return (
    typeof obj === 'object' &&
    typeof obj.id === 'number' &&
    typeof obj.slug === 'string' &&
    typeof obj.name === 'string'
  );
}

// Check if object is User
function isUser(obj: any): obj is User {
  return (
    typeof obj === 'object' &&
    typeof obj.id === 'number' &&
    typeof obj.username === 'string'
  );
}

// Check if object is APIError
function isAPIError(obj: any): obj is APIError {
  return (
    typeof obj === 'object' &&
    'error' in obj &&
    typeof obj.error.code === 'string'
  );
}
```
