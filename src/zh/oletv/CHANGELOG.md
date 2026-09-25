# OLeTV extension history

## v14.2 — 2026-08-08

- Accept API `data` returned as a JSON object, JSON array, JSON string, or AES-encrypted string.
- Fix `IllegalArgumentException: JsonObject is not a JsonPrimitive` on popular and latest lists.
- Keep the site-generated `_vv` signature flow and HLS `Referer`/`Origin` headers from v14.1.
- Debug APK SHA-256: `eeb2e4b3c494551a0ff3ba073b85dd918ecb0fdd55cfa6f36a86ec0e5b0da7aa`.

## v14.1 — 2026-08-08

- Initial OLeTV extension for `https://www.olevod.tv`.
- Add popular, latest, search, details, episodes, and HLS playback.
- Generate the API `_vv` signature through the site's WebView/WASM implementation.
- Support daily AES-CBC API response decryption.
- Capture the real HLS playlist from the player page and pass the required site headers.
- Debug APK SHA-256: `8eb720c43a5de137de682a3450fa6a3d46582b645b21ef3860d860b3ba35015b`.
