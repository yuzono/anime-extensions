# YouTube extractor

Extracts YouTube HLS, progressive MP4 and adaptive video streams from watch,
embed, Shorts and youtu.be URLs. HLS audio groups stay paired with their video
variants; adaptive video streams include separate audio tracks.

Direct adaptive video with audio is preferred, followed by progressive MP4,
then HLS. Direct streams avoid the packed HLS audio timestamp metadata that
triggers a JSON escaping bug in mpv-android's track-list conversion. HLS-only
responses still require that player bug to be fixed in the app.

Requires extensions-lib 16.

Add `implementation(project(":lib:youtubeextractor"))` to the extension's
dependencies, then call the suspend API:

```kotlin
private val youtubeExtractor by lazy { YoutubeExtractor(client, headers) }

override suspend fun getVideoList(hoster: Hoster): List<Video> =
    youtubeExtractor.videosFromUrl(
        hoster.hosterUrl,
        preferredCodecs = listOf("AV1", "VP9", "H.264"),
    )
```

`preferredCodecs` keeps one choice per resolution, selecting the first available
codec in that order and retaining the highest bitrate within it. Omit this
argument to keep all codec/frame-rate choices.

Stream labels include resolution, codec, frame rate and estimated bandwidth in
Mbps, for example `1080p - AV1 - 24 fps ~2.00 Mbps`. Direct streams use average
bitrate when available and include the first audio track; estimates are omitted
when either required rate is unknown. HLS uses the advertised combined average
bandwidth, falling back to peak bandwidth.
Protocol overhead and a different selected audio track can change actual usage.
Identical choices keep the highest bitrate. The extractor preserves the caller's User-Agent and
reports YouTube's playback restrictions, including sign-in and bot checks.

The `VISIONOS` visitor/player request flow is based on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/65cabc2ba5216ee871ace4a9963c08bdbf5d5dc0/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeStreamHelper.java).
