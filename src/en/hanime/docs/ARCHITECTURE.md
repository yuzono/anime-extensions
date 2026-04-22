# hanime.tv Technical Architecture

This document provides comprehensive documentation of the hanime.tv platform architecture, technologies, and infrastructure.

---

## Technology Stack Overview

### Frontend Framework

| Technology | Version | Source | Purpose |
|------------|---------|--------|---------|
| Vue.js | 2.7.16 | cdn.jsdelivr.net | Core JavaScript framework |
| Vuetify | 1.0.19 | cdn.jsdelivr.net | UI component library |
| Vue Router | 3.6.5 | cdn.jsdelivr.net | Client-side routing |
| Vue LazyLoad | 1.2.6 | cdn.jsdelivr.net | Image lazy loading |
| js-cookie | 3.0.5 | cdn.jsdelivr.net | Cookie handling |
| Vuex | 3.x | - | State management |
| Nuxt.js | (version TBD) | Framework | SSR and app structure |

### Build Tools

| Technology | Purpose |
|------------|---------|
| Webpack | Module bundler |
| Babel | JavaScript transpilation |
| PostCSS | CSS preprocessing |

### External Services

| Service | Purpose |
|---------|---------|
| Cloudflare RUM | Real user monitoring & analytics |
| Google IMA SDK | Advertisement integration |
| AdTng | Ad provider network |

---

## Frontend Architecture

### Vue.js Application Structure

```
hanime.tv/
├── index.html                    # Entry HTML
├── assets/
│   ├── js/
│   │   ├── vendor.min.js         # Third-party libraries
│   │   ├── app.min.js            # Application code
│   │   └── chunk.*.*.js          # Lazy-loaded chunks
│   └── css/
│       └── app.min.css           # Compiled styles
├── omni-player/
│   └── index.html                # Video player iframe
└── pages/                        # Route components
    ├── home/
    ├── videos/
    ├── browse/
    ├── search/
    └── tags/
```

### Vue Component Hierarchy

```
App.vue
├── NavigationBar.vue
│   ├── SearchBar.vue
│   ├── UserMenu.vue
│   └── ThemeToggle.vue
├── Sidebar.vue
│   ├── CategoryList.vue
│   └── TagCloud.vue
├── MainContent.vue (router-view)
│   ├── Home.vue
│   │   ├── Carousel.vue
│   │   ├── FeaturedGrid.vue
│   │   └── RecentUploads.vue
│   ├── VideoDetail.vue
│   │   ├── VideoPlayer.vue
│   │   ├── VideoInfo.vue
│   │   ├── TagList.vue
│   │   └── RelatedVideos.vue
│   ├── Browse.vue
│   │   ├── FilterPanel.vue
│   │   └── VideoGrid.vue
│   └── Search.vue
│       ├── SearchFilters.vue
│       └── SearchResults.vue
└── Footer.vue
```

---

## Vuex Store Structure

### State Management

```javascript
// Store modules
const store = new Vuex.Store({
  modules: {
    auth,           // Authentication state
    videos,         // Video data & cache
    playlists,      // User playlists
    ui,             // UI state (modals, theme)
    search,         // Search state & filters
    player          // Video player state
  }
});
```

### Module: auth

```javascript
const auth = {
  state: {
    isAuthenticated: false,
    user: null,
    sessionToken: null,
    csrfToken: null,
    preferences: {}
  },
  
  mutations: {
    SET_USER(state, user) {
      state.user = user;
      state.isAuthenticated = !!user;
    },
    SET_TOKENS(state, { session, csrf }) {
      state.sessionToken = session;
      state.csrfToken = csrf;
    },
    LOGOUT(state) {
      state.user = null;
      state.isAuthenticated = false;
      state.sessionToken = null;
      state.csrfToken = null;
    }
  },
  
  actions: {
    async login({ commit }, credentials) {
      const response = await api.login(credentials);
      commit('SET_USER', response.user);
      commit('SET_TOKENS', response.tokens);
    },
    
    async logout({ commit }) {
      await api.logout();
      commit('LOGOUT');
    }
  },
  
  getters: {
    isLoggedIn: state => state.isAuthenticated,
    currentUser: state => state.user,
    authHeaders: state => ({
      'x-session-token': state.sessionToken,
      'x-csrf-token': state.csrfToken
    })
  }
};
```

### Module: videos

```javascript
const videos = {
  state: {
    currentVideo: null,
    videoCache: {},        // { [id]: HentaiVideo }
    relatedVideos: [],
    loading: false,
    error: null
  },
  
  mutations: {
    SET_CURRENT_VIDEO(state, video) {
      state.currentVideo = video;
      state.videoCache[video.id] = video;
    },
    SET_RELATED_VIDEOS(state, videos) {
      state.relatedVideos = videos;
    },
    SET_LOADING(state, isLoading) {
      state.loading = isLoading;
    },
    SET_ERROR(state, error) {
      state.error = error;
    }
  },
  
  actions: {
    async fetchVideo({ commit }, slug) {
      commit('SET_LOADING', true);
      try {
        const video = await api.fetchVideo(slug);
        commit('SET_CURRENT_VIDEO', video);
      } catch (error) {
        commit('SET_ERROR', error);
      } finally {
        commit('SET_LOADING', false);
      }
    }
  }
};
```

---

## Routing Configuration

### Vue Router Setup

```javascript
import VueRouter from 'vue-router';

const routes = [
  {
    path: '/',
    redirect: '/home'
  },
  {
    path: '/home',
    name: 'home',
    component: () => import('./pages/Home.vue'),
    meta: { title: 'Home' }
  },
  {
    path: '/videos/hentai/:slug',
    name: 'video',
    component: () => import('./pages/VideoDetail.vue'),
    meta: { title: 'Video' }
  },
  {
    path: '/browse',
    name: 'browse',
    component: () => import('./pages/Browse.vue'),
    meta: { title: 'Browse' }
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('./pages/Search.vue'),
    meta: { title: 'Search' }
  },
  {
    path: '/tags/:tag',
    name: 'tag',
    component: () => import('./pages/TagVideos.vue'),
    meta: { title: 'Tag' }
  },
  {
    path: '/brand/:brand',
    name: 'brand',
    component: () => import('./pages/BrandVideos.vue'),
    meta: { title: 'Brand' }
  }
];

const router = new VueRouter({
  mode: 'history',
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { x: 0, y: 0 };
  }
});

// Navigation guards
router.beforeEach((to, from, next) => {
  // Update page title
  document.title = `${to.meta.title || 'Page'} - hanime.tv`;
  next();
});
```

---

## Webpack Build Configuration

### Bundle Structure

```javascript
// webpack.config.js (simplified)
module.exports = {
  entry: {
    app: './src/main.js',
    vendor: ['vue', 'vue-router', 'vuex', 'vuetify']
  },
  
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: '[name].[contenthash].js',
    chunkFilename: 'chunk.[name].[contenthash].js'
  },
  
  optimization: {
    splitChunks: {
      chunks: 'all',
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: 'vendor',
          chunks: 'all'
        }
      }
    }
  },
  
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: 'vue-loader'
      },
      {
        test: /\.js$/,
        loader: 'babel-loader',
        exclude: /node_modules/
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader', 'postcss-loader']
      }
    ]
  }
};
```

### Code Splitting

```javascript
// Lazy-loaded routes
const Home = () => import(/* webpackChunkName: "home" */ './pages/Home.vue');
const VideoDetail = () => import(/* webpackChunkName: "video" */ './pages/VideoDetail.vue');
const Browse = () => import(/* webpackChunkName: "browse" */ './pages/Browse.vue');
const Search = () => import(/* webpackChunkName: "search" */ './pages/Search.vue');
```

---

## CORS Configuration

### Preflight Request Flow

```
┌──────────┐                    ┌──────────────────────┐
│ hanime.tv│                    │ cached.freeanime...  │
│  (origin)│                    │    (API server)      │
└────┬─────┘                    └──────────┬───────────┘
     │                                      │
     │  OPTIONS /api/v10/search_hvs         │
     │  Origin: https://hanime.tv           │
     │  Access-Control-Request-Method: GET  │
     │─────────────────────────────────────>│
     │                                      │
     │  200 OK                               │
     │  Access-Control-Allow-Origin: *      │
     │  Access-Control-Allow-Methods: GET   │
     │  Access-Control-Allow-Headers: ...   │
     │<─────────────────────────────────────│
     │                                      │
     │  GET /api/v10/search_hvs             │
     │─────────────────────────────────────>│
     │                                      │
     │  200 OK + JSON response              │
     │<─────────────────────────────────────│
```

### CORS Headers

```http
# Response headers for cross-origin requests
Access-Control-Allow-Origin: https://hanime.tv
Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT, DELETE
Access-Control-Allow-Headers:
    Content-Type,
    x-signature,
    x-time,
    x-signature-version,
    x-session-token,
    x-csrf-token,
    x-user-license,
    x-license
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 86400
```

---

## Analytics Integration

### Cloudflare RUM (Real User Monitoring)

```javascript
// Cloudflare Web Analytics
const cloudflareConfig = {
  token: 'your-analytics-token',
  spa: true,  // Single Page Application mode
  
  // Automatic route tracking
  autoTrackRoutes: true,
  
  // Performance metrics
  trackPerformance: true,
  
  // Custom dimensions
  dimensions: {
    userId: () => store.getters.currentUser?.id,
    theme: () => store.state.ui.theme,
    pageType: () => router.currentRoute.meta.pageType
  }
};
```

### Google IMA SDK Analytics

```javascript
// Ad-related analytics tracking
const adAnalytics = {
  // Track ad impressions
  trackImpression: (ad) => {
    gtag('event', 'ad_impression', {
      ad_provider: 'AdTng',
      ad_type: ad.type,
      ad_duration: ad.duration
    });
  },
  
  // Track ad completion
  trackComplete: (ad) => {
    gtag('event', 'ad_complete', {
      ad_provider: 'AdTng',
      ad_type: ad.type
    });
  },
  
  // Track ad skip
  trackSkip: (ad) => {
    gtag('event', 'ad_skip', {
      ad_provider: 'AdTng',
      ad_type: ad.type,
      ad_position: ad.currentPosition
    });
  }
};
```

---

## API Client Architecture

### HTTP Client Wrapper

Every API request follows the same signature acquisition pattern:

1. `Emit("e")` dispatches a `CustomEvent` to the window
2. The WASM module's `_on_window_event` (export **B**) receives and processes the event internally
3. WASM invokes `ASM_CONSTS[17392]` to read the current Unix timestamp
4. WASM invokes `ASM_CONSTS[17442]` to write the computed signature to `window.ssignature` and the timestamp to `window.stime`
5. The JS layer reads `window.ssignature` and `window.stime` and constructs the request headers

```javascript
// api/client.js
class APIClient {
  constructor(baseURL) {
    this.baseURL = baseURL;
  }

  async request(method, path, options = {}) {
    const url = `${this.baseURL}${path}`;

    // Trigger WASM signature generation
    window.Emit("e");

    // Wait for WASM to populate globals
    while (!window.stime) {
      await new Promise(r => setTimeout(r, 50));
    }

    const headers = {
      "content-type": "application/json",
      "accept": "application/json",
      "x-session-token": session.session_token || "",
      "x-user-license": session.encrypted_user_license || "",
      "x-license": requestContext.x_license || "",
      "x-signature-version": "web2",          // hardcoded constant
      "x-signature": window.ssignature,         // 64-char hex from WASM
      "x-time": window.stime,                   // Unix timestamp from WASM
      "x-csrf-token": session.csrf_token || "",
      ...options.headers
    };

    const response = await fetch(url, {
      method,
      headers,
      body: options.body ? JSON.stringify(options.body) : undefined
    });

    if (!response.ok) {
      throw new APIError(response);
    }

    return response.json();
  }

  get(path, params) {
    const queryString = params ? `?${new URLSearchParams(params)}` : '';
    return this.request('GET', `${path}${queryString}`);
  }

  post(path, body) {
    return this.request('POST', path, { body });
  }
}

// API instances
const cachedAPI = new APIClient('https://cached.freeanimehentai.net');
const searchAPI  = new APIClient('https://search.htv-services.com');
const hanimeAPI  = new APIClient('https://hanime.tv');
```

### HTTP Headers per API Request

| Header | Source | Description |
|--------|--------|-------------|
| `content-type` | Constant | Always `application/json` |
| `accept` | Constant | Always `application/json` |
| `x-session-token` | Session | User session token (empty if unauthenticated) |
| `x-user-license` | Session | Encrypted user license string |
| `x-license` | Request context | Per-request license value |
| `x-signature-version` | Constant | Hardcoded `"web2"` |
| `x-signature` | WASM | 64-character hex string from `window.ssignature` |
| `x-time` | WASM | Unix timestamp from `window.stime` |
| `x-csrf-token` | Session | CSRF token (empty if unauthenticated) |

### API Service Layer

```javascript
// api/videos.js
export const videoAPI = {
  search(params) {
    return cachedAPI.get('/api/v10/search_hvs', params);
  },
  
  getVideo(slug) {
    return cachedAPI.get(`/api/v8/hentai_videos/${slug}`);
  },
  
  getRelated(videoId) {
    return cachedAPI.get('/api/v8/playlists', {
      source: 'related',
      hv_id: videoId
    });
  },
  
  trackPlay(slug, width, height) {
    return cachedAPI.post(`/api/v8/hentai_videos/${slug}/play`, {
      width,
      height,
      ab: 'kh'
    });
  }
};
```

---

## CDN Infrastructure

### hanime-cdn.com

> **Note:** The WASM signature binary is **not** served as a separate `.wasm` file. It is embedded inline as a base64 string inside `vendor.js` and decoded at runtime via `findWasmBinary()`. There is no standalone `.wasm` download on the CDN.

```
                    ┌─────────────────────┐
                    │   hanime-cdn.com    │
                    │    (CDN Origin)     │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
        ▼                      ▼                      ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│   /images/    │    │   /videos/    │    │   /vhtv2/     │
│   (Media)     │    │  (Streaming)  │    │    (App)      │
└───────────────┘    └───────────────┘    └───────────────┘
        │                      │                      │
        │                      │                      │
   ┌────┴────┐            ┌────┴────┐            ┌────┴────┐
   │ posters │            │ *.mp4   │            │ *.js    │
   │ covers  │            │ *.webm  │            │ env.json│
   │storybds │            │ *.m3u8  │            │ *.css   │
   │ avatars │            │         │            │         │
   └─────────┘            └─────────┘            └─────────┘
```

### Cache Headers

```http
# Static assets (images)
Cache-Control: public, max-age=31536000, immutable

# Video files
Cache-Control: public, max-age=86400

# JavaScript bundles
Cache-Control: public, max-age=604800

# Environment config
Cache-Control: no-cache
```

---

## Security Architecture

### Authentication Flow

```
┌──────────┐     ┌──────────┐     ┌──────────────┐
│  Client  │────>│  hanime  │────>│   Auth API   │
│          │     │    .tv   │     │              │
└──────────┘     └──────────┘     └──────────────┘
     │                                  │
     │  1. Login credentials            │
     │─────────────────────────────────>│
     │                                  │
     │  2. Session token + CSRF token   │
     │<─────────────────────────────────│
     │                                  │
     │  3. API requests with tokens     │
     │─────────────────────────────────>│
```

### Request Signing

The site uses a **WASM-compiled C++ signature module** (Emscripten/embind) that runs entirely in the browser. The signature algorithm is hidden inside the WASM binary — it is not a simple HMAC or hash that can be replicated in JavaScript.

**Key Files:**

| File | Role |
|------|------|
| `vendor.0130da3e01eaf5c7d570b6ed1becb5f4.min.js` | WASM loader + runtime + inline base64 binary |
| `40c99ce.js` | Vue/Nuxt app that constructs HTTP headers and polls for signatures |

**Signature Generation Flow:**

```
 1. vendor.js loads → base64Decode("AGFzbQ...") → WebAssembly.instantiate()
 2. initRuntime()  → wasmExports["A"]()            (embind init)
 3. callMain()     → wasmExports["C"]()            (_main, registers window event listeners)
 4. WASM's _main() calls import y (window_on) → window.addEventListener("e", handler)
 5. 40c99ce.js on mount: polls every 100ms, dispatches Emit("e")
 6. "e" event fires → Module.ccall("on_window_event", ..., ["e", data])
 7. WASM export B (_on_window_event) processes the event internally
 8. WASM calls ASM_CONSTS[17392] → parseInt(new Date().getTime() / 1e3)  (timestamp)
 9. WASM calls ASM_CONSTS[17442] → window.ssignature = UTF8ToString($0), window.stime = $1
10. Polling detects window.stime → stops, app is ready
11. Every API request: Emit("e") → fresh signature → headers attached
```

**Emit helper:**

```javascript
window.Emit = function(t) {
  return window.dispatchEvent(new CustomEvent(t));
}
```

### CSRF Protection

```javascript
// CSRF token handling
axios.interceptors.request.use(config => {
  const csrfToken = store.state.auth.csrfToken;

  if (['POST', 'PUT', 'DELETE'].includes(config.method.toUpperCase())) {
    config.headers['x-csrf-token'] = csrfToken;
  }

  return config;
});
```

---

## WASM Module Architecture

The signature module is an **Emscripten-compiled C++ binary** that uses **embind** for JavaScript interop. The WASM binary is embedded as an inline base64 string inside `vendor.js` — there is no separate `.wasm` file fetched from the CDN. Source dumps and analysis artifacts live in `docs/wasm_dump/`.

### Module Loading

```
vendor.js loads
  └─ base64Decode("AGFzbQ...")   → raw WASM binary bytes
  └─ WebAssembly.instantiate()   → compiled module
  └─ initRuntime()               → wasmExports["A"]()  (embind initialization)
  └─ callMain()                  → wasmExports["C"]()  (_main entry point)
```

The `findWasmBinary()` function in vendor.js locates the inline base64 payload, decodes it, and passes it to the WebAssembly API.

### WASM Exports

| Export | Symbol | Purpose |
|--------|--------|---------|
| `A` | `initRuntime` | embind runtime initialization |
| `B` | `_on_window_event` | Signature writer — processes `"e"` events and produces `window.ssignature` / `window.stime` |
| `C` | `_main` | Entry point; registers `window.addEventListener("e", handler)` via import `y` |
| `D` | `___getTypeName` | embind type system support |
| `E` | `_malloc` | Heap allocation |
| `F` | `_free` | Heap deallocation |
| `G` | `__emscripten_stack_restore` | Stack pointer restore |
| `H` | `__emscripten_stack_alloc` | Stack space allocation |
| `I` | `_emscripten_stack_get_current` | Current stack pointer read |
| `z` | `memory` | Linear memory (WebAssembly.Memory) |

### WASM Imports (module "a")

The WASM binary imports **24 functions** from the host module `"a"`, which serve three roles:

- **embind runtime** — type registration, class metadata, exception bridging
- **ASM_CONSTS bridge** — `ASM_CONSTS[17392]` and `ASM_CONSTS[17442]` allow the WASM to call back into JavaScript
- **`window_on` (import `y`)** — called during `_main()` to register `window.addEventListener("e", ...)` so the JS side can dispatch signature requests

### JS ↔ WASM Bridge

The critical bridge points that connect JavaScript and WASM:

```
JS → WASM:  Module.ccall("on_window_event", ..., ["e", data])
             ↳ WASM export B (_on_window_event)

WASM → JS:  ASM_CONSTS[17392] → parseInt(new Date().getTime() / 1e3)
             ↳ Supplies the current Unix timestamp to the WASM

             ASM_CONSTS[17442] → window.ssignature = UTF8ToString($0)
                                  window.stime      = $1
             ↳ Receives the computed signature and timestamp from the WASM
```

### Module Statistics

| Metric | Value |
|--------|-------|
| Total functions | ~586 |
| Memory pages | 258 (16 MB fixed) |
| Imports (module "a") | 24 functions |
| Exports | 10 (listed above) |
| Compilation toolchain | Emscripten (embind) |

### Initialization Polling

The Vue/Nuxt app (`40c99ce.js`) polls for WASM readiness on mount:

```javascript
// Simplified from 40c99ce.js
const pollInterval = setInterval(() => {
  if (window.stime) {
    clearInterval(pollInterval);
    // App is ready — WASM is initialized and can produce signatures
  } else {
    window.Emit("e");  // Trigger signature generation attempt
  }
}, 100);
```

Once `window.stime` is populated, the app stops polling and all subsequent API calls use the `Emit("e")` → read globals pattern to attach fresh signatures.

---

## Performance Optimization

### Image Optimization

```javascript
// Responsive image loading
const getImageUrl = (baseUrl, options = {}) => {
  const { width = 400, format = 'webp', quality = 80 } = options;
  
  // Use CDN transformation parameters
  return `${baseUrl}?w=${width}&f=${format}&q=${quality}`;
};

// Lazy loading implementation
const lazyLoadImages = () => {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const img = entry.target;
        img.src = img.dataset.src;
        observer.unobserve(img);
      }
    });
  });
  
  document.querySelectorAll('img[data-src]').forEach(img => {
    observer.observe(img);
  });
};
```

### Code Splitting Metrics

```javascript
// Bundle analysis
const bundleSizes = {
  'vendor.min.js': '245 KB',     // Vue + Vuetify + core libs
  'app.min.js': '125 KB',        // Application code
  'chunk.home.js': '35 KB',      // Home page
  'chunk.video.js': '28 KB',     // Video detail page
  'chunk.browse.js': '22 KB',    // Browse page
  'chunk.search.js': '18 KB'     // Search page
};

// Initial load: ~370 KB (vendor + app)
// Route chunks loaded on demand
```

---

## Deployment Architecture

```
┌─────────────────────┐
│ Cloudflare CDN       │
│ (Global Edge)        │
└──────────┬──────────┘
           │
┌──────────┼──────────┬──────────────┐
│          │          │              │
▼          ▼          ▼              ▼
┌───────────┐ ┌───────────────┐ ┌───────────┐ ┌───────────────┐
│ hanime.tv │ │ cached.free   │ │ hanime-cdn│ │ search.htv    │
│(Web Server)│ │animehentai.net│ │  .com     │ │services.com   │
└───────────┘ │  (API)        │ │  (CDN)    │ │ (Search API)  │
              └───────────────┘ └───────────┘ └───────────────┘
```

---

## Environment Configuration

```javascript
// Environment detection
const environments = {
  production: {
    apiBase: 'https://cached.freeanimehentai.net',
    searchBase: 'https://search.htv-services.com',
    cdnBase: 'https://hanime-cdn.com',
    analytics: true,
    debug: false
  },
  staging: {
    apiBase: 'https://staging-api.hanime.tv',
    searchBase: 'https://staging-search.hanime.tv',
    cdnBase: 'https://staging-cdn.hanime.tv',
    analytics: false,
    debug: true
  },
  development: {
    apiBase: 'http://localhost:8000',
    searchBase: 'http://localhost:8001',
    cdnBase: 'http://localhost:3000',
    analytics: false,
    debug: true
  }
};

// Current environment
const ENV = process.env.NODE_ENV || 'production';
const config = environments[ENV];
```
