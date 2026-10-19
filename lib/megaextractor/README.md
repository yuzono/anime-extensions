# MEGA extractor

`MegaExtractor(client, headers).videosFromUrl(url, prefix)` supports public MEGA file and embed links,
including legacy `#!id!key` links. It resolves file metadata when the hoster is selected,
then streams media through a loopback-only HTTP adapter. The adapter decrypts AES-CTR
ranges on demand, supports seeking and HEAD requests, and closes upstream responses
when reads finish. It does not download the whole file before playback.

The local URL contains a random token; the file key stays in memory. The adapter retains
the 64 most recently used streams. Links need resolving again after the app process
restarts or the MEGA download URL expires. MEGA transfer limits still apply.

Folder links, account login, and password-protected links are unsupported. Range
decryption follows the [MEGA web client](https://github.com/meganz/webclient/blob/master/decrypter.js).

Run the range/decryption tests with `./gradlew :lib:megaextractor:testDebugUnitTest`.
