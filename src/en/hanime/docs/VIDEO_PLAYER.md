# hanime.tv Video Player Documentation

This document provides comprehensive documentation of the hanime.tv video player architecture and components.

## Overview

hanime.tv uses a custom-built video player system called **omni-player** that combines multiple technologies for video playback, advertising, and streaming.

---

## Player Architecture

### Component Stack

```
┌─────────────────────────────────────────────────────┐
│                   omni-player                        │
│  ┌───────────────────────────────────────────────┐  │
│  │              Video.js Core                     │  │
│  │  ┌─────────────────────────────────────────┐  │  │
│  │  │          HTML5 Video Element             │  │  │
│  │  │  ┌───────────────────────────────────┐  │  │  │
│  │  │  │        Blob URL Streaming          │  │  │  │
│  │  │  └───────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────┐  │
│  │           Google IMA SDK                      │  │
│  │           (Ad Integration)                    │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

---

## omni-player Iframe

### Loading the Player

The video player is loaded via an iframe from the hanime.tv domain:

```html
<iframe 
  src="https://hanime.tv/omni-player/index.html?poster_url={encoded_url}&c={timestamp}"
  allowfullscreen
  allow="autoplay; encrypted-media"
></iframe>
```

### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `poster_url` | string | URL-encoded poster image URL |
| `c` | integer | Cache-busting timestamp (Unix ms) |

### Example

```javascript
const loadPlayer = (posterUrl) => {
  const iframe = document.createElement('iframe');
  const timestamp = Date.now();
  const encodedPoster = encodeURIComponent(posterUrl);
  
  iframe.src = `https://hanime.tv/omni-player/index.html?poster_url=${encodedPoster}&c=${timestamp}`;
  iframe.allow = 'autoplay; encrypted-media';
  iframe.allowFullscreen = true;
  
  return iframe;
};
```

---

## Video.js Integration

### Core Configuration

hanime.tv uses Video.js as the primary video playback engine:

```javascript
// Video.js configuration
const playerConfig = {
  controls: true,
  autoplay: false,
  preload: 'auto',
  fluid: true,
  responsive: true,
  playbackRates: [0.5, 1, 1.5, 2],
  controlBar: {
    children: [
      'playToggle',
      'volumePanel',
      'currentTimeDisplay',
      'timeDivider',
      'durationDisplay',
      'progressControl',
      'playbackRateMenuButton',
      'fullscreenToggle'
    ]
  }
};
```

### Video Element

```html
<video
  id="video-player"
  class="video-js vjs-big-play-centered"
  controls
  preload="auto"
  poster="https://hanime-cdn.com/images/posters/{slug}-pv1.webp"
>
  <source src="{blob_url}" type="video/mp4">
</video>
```

---

## Blob URL Streaming

### Overview

hanime.tv uses Blob URLs for video streaming, which provides:

- Security through obfuscation
- Progressive loading capabilities
- Memory-efficient streaming
- Protection against direct downloads

### Blob URL Creation

```javascript
// Simplified blob URL streaming process
const createBlobStream = async (videoUrl) => {
  // Fetch video data as chunks
  const response = await fetch(videoUrl, {
    headers: {
      'Range': 'bytes=0-' // Request partial content
    }
  });
  
  // Create blob from response
  const blob = await response.blob();
  
  // Generate blob URL
  const blobUrl = URL.createObjectURL(blob);
  
  return blobUrl;
};

// Apply to video element
const blobUrl = await createBlobStream('https://cdn.example.com/video.mp4');
videoElement.src = blobUrl;
```

### Streaming Implementation

The actual implementation uses a more sophisticated approach:

```javascript
// MediaSource API for true streaming
const streamWithMediaSource = async (videoElement, manifestUrl) => {
  const mediaSource = new MediaSource();
  videoElement.src = URL.createObjectURL(mediaSource);
  
  mediaSource.addEventListener('sourceopen', async () => {
    // Create source buffer
    const sourceBuffer = mediaSource.addSourceBuffer('video/mp4; codecs="avc1.42E01E, mp4a.40.2"');
    
    // Fetch and append segments
    const segments = await fetchSegments(manifestUrl);
    
    for (const segment of segments) {
      sourceBuffer.appendBuffer(segment);
      await new Promise(resolve => {
        sourceBuffer.addEventListener('updateend', resolve, { once: true });
      });
    }
    
    mediaSource.endOfStream();
  });
};
```

---

## Google IMA SDK Integration

### Overview

Google IMA (Interactive Media Ads) SDK handles advertisement integration:

- Preroll ads
- Midroll ads
- Postroll ads
- VAST/VPAID ad formats

### Ad Provider

Primary ad provider: **AdTng**

```
https://a.adtng.com/
```

### Implementation

```javascript
// Google IMA SDK initialization
const initIMA = (videoElement, adTagUrl) => {
  // Create ad display container
  const adDisplayContainer = new google.ima.AdDisplayContainer(
    document.getElementById('ad-container'),
    videoElement
  );
  
  // Create ads loader
  const adsLoader = new google.ima.AdsLoader(adDisplayContainer);
  
  // Request ads
  const adsRequest = new google.ima.AdsRequest();
  adsRequest.adTagUrl = adTagUrl;
  adsRequest.linearAdSlotWidth = videoElement.clientWidth;
  adsRequest.linearAdSlotHeight = videoElement.clientHeight;
  
  adsLoader.requestAds(adsRequest);
};

// Ad tag URL format
const adTagUrl = 'https://a.adtng.com/get?tag=preroll&format=vast&vpos=preroll';
```

### Ad Event Tracking

```javascript
// Track ad events
const trackAdEvent = (eventType, adUrl) => {
  const encodedUrl = encodeURIComponent(adUrl);
  
  fetch(`https://hanime.tv/rapi/v7/preroll_ad_event?kind=${eventType}&url=${encodedUrl}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    }
  });
};

// Event types
const adEvents = {
  impression: 'impression',
  start: 'start',
  firstQuartile: 'first_quartile',
  midpoint: 'midpoint',
  thirdQuartile: 'third_quartile',
  complete: 'complete',
  click: 'click',
  skip: 'skip'
};
```

---

## Player Features

### Quality Selection

Videos are available in multiple quality levels:

| Quality | Resolution | Bitrate |
|---------|------------|---------|
| 240p | 426x240 | ~500 kbps |
| 360p | 640x360 | ~1000 kbps |
| 480p | 854x480 | ~2000 kbps |
| 720p | 1280x720 | ~4000 kbps |
| 1080p | 1920x1080 | ~8000 kbps |

### Playback Controls

```javascript
// Player control methods
const playerControls = {
  play: () => player.play(),
  pause: () => player.pause(),
  seek: (time) => player.currentTime(time),
  setVolume: (vol) => player.volume(vol),
  mute: () => player.muted(true),
  unmute: () => player.muted(false),
  setQuality: (quality) => player.quality(quality),
  setPlaybackRate: (rate) => player.playbackRate(rate),
  enterFullscreen: () => player.requestFullscreen(),
  exitFullscreen: () => player.exitFullscreen()
};
```

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Space` | Play/Pause |
| `K` | Play/Pause |
| `F` | Toggle fullscreen |
| `M` | Toggle mute |
| `Left Arrow` | Seek backward 10s |
| `Right Arrow` | Seek forward 10s |
| `Up Arrow` | Volume up |
| `Down Arrow` | Volume down |
| `0-9` | Seek to percentage |

---

## Poster and Thumbnail System

### Poster Images

```javascript
// Poster URL format
const getPosterUrl = (slug, variant = 1) => {
  return `https://hanime-cdn.com/images/posters/${slug}-pv${variant}.webp`;
};
```

### Storyboard Thumbnails

Storyboards provide video preview on seek bar hover:

```javascript
// Storyboard URL format
const getStoryboardUrl = (slug, resolution, index) => {
  return `https://hanime-cdn.com/images/storyboards/${slug}-${resolution}-h${index}x.webp`;
};

// Example
const storyboard = getStoryboardUrl('example-video', '720p', 1);
// https://hanime-cdn.com/images/storyboards/example-video-720p-h1x.webp
```

---

## Player Events

### Video Events

```javascript
// Event listeners
player.on('play', () => {
  console.log('Video started playing');
  trackPlay();
});

player.on('pause', () => {
  console.log('Video paused');
  trackPause();
});

player.on('ended', () => {
  console.log('Video ended');
  trackComplete();
});

player.on('timeupdate', () => {
  const progress = player.currentTime() / player.duration();
  trackProgress(progress);
});

player.on('error', (e) => {
  console.error('Player error:', e);
  handleError(e);
});
```

### Analytics Events

```javascript
// Track video play
const trackVideoPlay = async (slug, width, height) => {
  await fetch(`https://cached.freeanimehentai.net/api/v8/hentai_videos/${slug}/play`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-session-token': getSessionToken(),
      'x-csrf-token': getCsrfToken()
    },
    body: JSON.stringify({
      width: width,
      height: height,
      ab: 'kh'
    })
  });
};
```

---

## Error Handling

### Common Errors

```javascript
// Error codes and handling
const errorHandling = {
  MEDIA_ERR_ABORTED: {
    code: 1,
    message: 'Video playback aborted',
    action: 'retry'
  },
  MEDIA_ERR_NETWORK: {
    code: 2,
    message: 'Network error occurred',
    action: 'checkConnection'
  },
  MEDIA_ERR_DECODE: {
    code: 3,
    message: 'Video decoding failed',
    action: 'fallbackQuality'
  },
  MEDIA_ERR_SRC_NOT_SUPPORTED: {
    code: 4,
    message: 'Video format not supported',
    action: 'useAlternativeSource'
  }
};

// Error handler
player.on('error', function() {
  const error = player.error();
  const errorInfo = errorHandling[error.code];
  
  console.error(`Player Error [${error.code}]: ${errorInfo.message}`);
  
  // Implement recovery strategy
  switch (errorInfo.action) {
    case 'retry':
      player.load();
      player.play();
      break;
    case 'checkConnection':
      showNetworkError();
      break;
    case 'fallbackQuality':
      setLowerQuality();
      break;
    case 'useAlternativeSource':
      loadAlternativeSource();
      break;
  }
});
```

---

## Mobile Support

### Touch Controls

```javascript
// Touch event handling
const touchControls = {
  doubleTap: () => toggleFullscreen(),
  swipeLeft: () => seekBackward(10),
  swipeRight: () => seekForward(10),
  swipeUp: () => increaseVolume(),
  swipeDown: () => decreaseVolume(),
  pinch: (scale) => setPlaybackRate(scale)
};
```

### Responsive Design

```css
/* Responsive player container */
.video-container {
  position: relative;
  width: 100%;
  padding-top: 56.25%; /* 16:9 aspect ratio */
}

.video-container video,
.video-container iframe {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}

/* Mobile optimizations */
@media (max-width: 768px) {
  .video-js .vjs-big-play-button {
    font-size: 2em;
  }
  
  .video-js .vjs-control-bar {
    font-size: 10px;
  }
}
```

---

## Security Considerations

### Content Protection

- Blob URLs prevent direct video URL access
- CORS restrictions on API endpoints
- HMAC signatures for API authentication
- Session-based access control

### DRM

While hanime.tv does not implement traditional DRM, it uses:

- Obfuscated video URLs
- Time-limited CDN links
- Referer validation
- Token-based access

---

## Performance Optimization

### Lazy Loading

```javascript
// Lazy load video when in viewport
const lazyLoadObserver = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      initPlayer(entry.target);
      lazyLoadObserver.unobserve(entry.target);
    }
  });
}, {
  rootMargin: '100px'
});

document.querySelectorAll('.video-placeholder').forEach(el => {
  lazyLoadObserver.observe(el);
});
```

### Preloading Strategy

```javascript
// Preload strategy
const preloadConfig = {
  metadata: 'metadata', // Load only metadata initially
  auto: 'auto',         // Load full video
  none: 'none'          // Don't preload
};

// Recommended: metadata preload
videoElement.preload = 'metadata';
```

---

## Integration Example

### Complete Player Setup

```javascript
class HanimePlayer {
  constructor(container, options) {
    this.container = container;
    this.options = options;
    this.player = null;
  }

  async init() {
    // Create iframe
    const iframe = document.createElement('iframe');
    const timestamp = Date.now();
    const encodedPoster = encodeURIComponent(this.options.posterUrl);
    
    iframe.src = `https://hanime.tv/omni-player/index.html?poster_url=${encodedPoster}&c=${timestamp}`;
    iframe.allow = 'autoplay; encrypted-media';
    iframe.allowFullscreen = true;
    iframe.style.width = '100%';
    iframe.style.height = '100%';
    
    this.container.appendChild(iframe);
    
    // Track play event
    this.trackPlay();
    
    return iframe;
  }

  async trackPlay() {
    const { slug, width, height } = this.options;
    
    await fetch(`https://cached.freeanimehentai.net/api/v8/hentai_videos/${slug}/play`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-session-token': this.options.sessionToken,
        'x-csrf-token': this.options.csrfToken
      },
      body: JSON.stringify({
        width: width || window.innerWidth,
        height: height || window.innerHeight,
        ab: 'kh'
      })
    });
  }
}

// Usage
const player = new HanimePlayer(document.getElementById('player-container'), {
  slug: 'example-video-slug',
  posterUrl: 'https://hanime-cdn.com/images/posters/example-video-slug-pv1.webp',
  sessionToken: 'your-session-token',
  csrfToken: 'your-csrf-token'
});

player.init();
```
