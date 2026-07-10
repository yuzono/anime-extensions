# Miruro.tv Security Research

## Verdict
**BLOCK** — 4 critical, 7 high, 5 medium findings. The pipe API "security" is security-through-obscurity with a publicly exposed XOR key. Cloudflare WAF is the only real barrier to full API access.

## Scope
- **Target**: miruro.tv and all known mirrors (miruro.tv, miruro.to, miruro.bz, miruro.ru, miruro.com)
- **Infrastructure**: Status page (status.miruro.com), Proxy servers (vault01/02.ultracloud.cc), Backend, Streaming server
- **Extension**: src/en/miruro/ in the anime-extensions repo (Android Aniyomi/Tachiyomi extension)
- **Analysis methods**: Source code review (extension), Live endpoint probing, JS bundle analysis, Status page API analysis, OAuth flow analysis
- **Commands run**: curl probes, JS decompilation, protocol reverse-engineering from frontend bundles

## Contents
- `report.md` — Full security research report
- `bundles/` — JS bundle dumps from miruro.tv
- `discovered-endpoints.txt` — All discovered API endpoints
- `secrets-dump.txt` — All secrets leaked via env2.js

---

## Quick Reference

### Critical Findings
1. **Pipe API XOR Key Exposed** — `71951034f8fbcf53d89db52ceb3dc22c` in both extension source and env2.js
2. **No Authentication on Pipe API** — Zero auth on `/api/secure/pipe`, Cloudflare WAF is the only barrier
3. **Proxy URLs & Second XOR Key Exposed** — `vault01/02.ultracloud.cc` + proxy key `a54d389c18527d9fd3e7f0643e27edbe`
4. **No Subresource Integrity** — 50+ JS bundles loaded without integrity hashes

### Key Endpoints
| Endpoint | Protection | Purpose |
|----------|-----------|---------|
| `/api/secure/pipe?e=<b64>` | Cloudflare WAF | Core pipe API (config, info, episodes, sources) |
| `/api/events` | None | SSE event stream (version, config, JWKS) |
| `/health` | None | Health check |
| `/random-pool.json` | None | AniList ID list |
| `/env2.js` | Cloudflare WAF | Client-side secrets (XOR key, proxy URLs, OAuth IDs) |
| `/api/secure/jwks` | Cloudflare WAF | JWK set |
| `/api/mal/me` | OAuth token | MAL user info |

### Exposed Secrets (env2.js)
| Key | Value |
|-----|-------|
| VITE_PIPE_OBF_KEY | `71951034f8fbcf53d89db52ceb3dc22c` |
| VITE_PROXY_A | `https://vault01.ultracloud.cc/` |
| VITE_PROXY_B | `https://vault02.ultracloud.cc/` |
| VITE_PROXY_OBF_KEY | `a54d389c18527d9fd3e7f0643e27edbe` |
| VITE_ANILIST_CLIENT_ID | `18233` |
| VITE_MAL_CLIENT_ID | `7ba6069f69816f7fc6dd8ec933ae8586` |
| VITE_ANILIST_REDIRECT_URI | `https://www.miruro.tv/callback` |
| VITE_MAL_REDIRECT_URI | `https://www.miruro.tv/callback` |
