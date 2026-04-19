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
  x-csrf-token
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

```javascript
// api/client.js
class APIClient {
  constructor(baseURL) {
    this.baseURL = baseURL;
  }
  
  async request(method, path, options = {}) {
    const url = `${this.baseURL}${path}`;
    const timestamp = Date.now();
    
    const headers = {
      'Content-Type': 'application/json',
      'x-time': timestamp.toString(),
      'x-signature-version': 'web2',
      'x-signature': this.generateSignature(method, path, timestamp, options.body),
      ...options.headers
    };
    
    // Add auth headers if available
    const authHeaders = store.getters['auth/authHeaders'];
    Object.assign(headers, authHeaders);
    
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
  
  generateSignature(method, path, timestamp, body) {
    const payload = `${method}:${path}:${timestamp}:${body ? JSON.stringify(body) : ''}`;
    return hmacSHA256(SECRET_KEY, payload);
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
const hanimeAPI = new APIClient('https://hanime.tv');
```

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

```javascript
// HMAC signature generation
function generateSignature(method, path, timestamp, body) {
  // Concatenate request components
  const payload = [
    method.toUpperCase(),
    path,
    timestamp.toString(),
    body ? JSON.stringify(body) : ''
  ].join(':');
  
  // Generate HMAC-SHA256 signature
  const signature = CryptoJS.HmacSHA256(payload, SECRET_KEY);
  return signature.toString(CryptoJS.enc.Hex);
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
                    │   Cloudflare CDN    │
                    │   (Global Edge)     │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
        ▼                      ▼                      ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│   hanime.tv   │    │  cached.free  │    │  hanime-cdn   │
│  (Web Server) │    │animehentai.net│    │     .com      │
└───────────────┘    │   (API)       │    │    (CDN)      │
                     └───────────────┘    └───────────────┘
```

---

## Environment Configuration

```javascript
// Environment detection
const environments = {
  production: {
    apiBase: 'https://cached.freeanimehentai.net',
    cdnBase: 'https://hanime-cdn.com',
    analytics: true,
    debug: false
  },
  staging: {
    apiBase: 'https://staging-api.hanime.tv',
    cdnBase: 'https://staging-cdn.hanime.tv',
    analytics: false,
    debug: true
  },
  development: {
    apiBase: 'http://localhost:8000',
    cdnBase: 'http://localhost:3000',
    analytics: false,
    debug: true
  }
};

// Current environment
const ENV = process.env.NODE_ENV || 'production';
const config = environments[ENV];
```
