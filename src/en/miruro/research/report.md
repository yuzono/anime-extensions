# Security Research Result — miruro.tv

## Verdict
**BLOCK** — 4 critical, 7 high, 5 medium findings. The pipe API "security" is security-through-obscurity with a publicly exposed XOR key. Cloudflare WAF is the only real barrier to full API access.

## Scope
- **Target**: miruro.tv + mirrors (miruro.tv, miruro.to, miruro.bz, miruro.ru, miruro.com)
- **Infrastructure**: status.miruro.com, vault01/02.ultracloud.cc, backend, streaming server
- **Extension**: src/en/miruro/ (Aniyomi/Tachiyomi Android extension)
- **Analysis**: Source code review, Live endpoint probing, JS bundle reverse-engineering, OAuth flow analysis

## Findings Table

| Severity | Title | CWE | Exploitability | Impact |
|----------|-------|-----|----------------|--------|
| 🔴 CRITICAL | Pipe API XOR Key Exposed Publicly | CWE-321 | High | Critical |
| 🔴 CRITICAL | No Authentication on Pipe API | CWE-287 | High | Critical |
| 🔴 CRITICAL | Proxy URLs & Second XOR Key Exposed | CWE-312 | High | Critical |
| 🔴 CRITICAL | No Subresource Integrity on 50+ Bundles | CWE-353 | Medium | Critical |
| 🟡 HIGH | OAuth Client Credentials Exposed | CWE-200 | Medium | High |
| 🟡 HIGH | Monitag Ad Script Has Full DOM Access | CWE-829 | Low | High |
| 🟡 HIGH | 30+ Third-Party Embed Extractors | CWE-829 | Low | High |
| 🟡 HIGH | Dynamic Config Supply Chain (Pipe API) | CWE-494 | Medium | High |
| 🟡 HIGH | Status Page Can Redirect Users | CWE-349 | Medium | High |
| 🟡 HIGH | Cloudflare Bypass via QuickJS Sandbox | CWE-693 | Medium | High |
| 🟡 HIGH | Proxy Server Origin Validation | CWE-918 | Medium | High |
| 🟡 MEDIUM | Episode Metadata in URLs | CWE-201 | Low | Medium |
| 🟡 MEDIUM | SSE Event Stream Leaks | CWE-200 | Low | Medium |
| 🟡 MEDIUM | Service Worker CDN Cache Poisoning | CWE-494 | Low | Medium |
| 🟡 MEDIUM | Multiple Mirrors Expand Attack Surface | CWE-200 | Low | Medium |

## Key Exploitability for the Extension

### Already Leveraged ✅
- Pipe API access with XOR key decryption
- Cloudflare bypass via CloudScraperInterceptor
- Multi-mirror fallback with auto-discovery

### Could Leverage ⚠️
- Direct proxy server streaming (vault*.ultracloud.cc bypasses main site)
- SSE endpoint for real-time config/version updates

### Must Avoid ❌
- Hardcoding pipe API secrets without rotation fallback
- Trusting embed provider content blindly (30+ extractors)
- Relying solely on unauthenticated status page for mirror selection

## Bundle Dumps Collected
See `bundles/` directory:
- `env2.js` — Client-side secrets (XOR keys, proxy URLs, OAuth IDs)
- `sw.js` — Service worker with CDN caching
- `index-B2pJnYG4.js` — Main app bundle (Vite/Rolldown + React)
- `WatchRoute-B2vRFobK.js` — Video player/watching page route
- `AuthCallbackRoute-_v3gJlIB.js` — OAuth callback handler
- `InfoRoute-D6Hy4sGQ.js` — Anime info page route
- `prod-P8_vGqKC_header.js` — Production utilities (partial)
- `links-BUhGnA4q_header.js` — Imports/linking utilities (partial)
- `registerSW.js` — Service worker registration
- `page.html` — Full page HTML
