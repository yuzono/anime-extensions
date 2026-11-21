package keiyoushi.source.stream

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder

class StreamProxyTest {
    private val base = "http://127.0.0.1:4321/".toHttpUrl()

    private fun roundTrip(url: String) {
        val target = url.toHttpUrl()
        val encoded = ProxyUrl.encode(base, 42L, target)
        assertEquals(42L to target, ProxyUrl.decode(encoded))
    }

    @Test
    fun `round trips urls`() {
        roundTrip("https://cdn.example.com/hls/master.m3u8?token=a%2Fb&x=1")
        roundTrip("https://cdn.example.com/")
        roundTrip("http://cdn.example.com:8080/a/b/")
        roundTrip("https://cdn.example.com/seg%20one.ts")
        roundTrip("https://[2001:db8::1]:8443/v.mp4")
    }

    @Test
    fun `survives the server decoding the path`() {
        listOf(
            "https://cdn.example.com/a%2Fb/seg.ts",
            "https://cdn.example.com/100%25/seg.ts",
            "https://cdn.example.com/a+b/seg%20one.ts",
        ).forEach { url ->
            val target = url.toHttpUrl()
            val encoded = ProxyUrl.encode(base, 3L, target)
            // What NanoHTTPD hands to the server: the path percent-decoded once
            val path = URLDecoder.decode(encoded.encodedPath, "UTF-8")
            val served = base.newBuilder().encodedPath(path).encodedQuery(encoded.encodedQuery).build()
            assertEquals(3L to target, ProxyUrl.decode(served, escaped = false))
        }
    }

    @Test
    fun `encodes onto the animiru placeholder`() {
        val encoded = ProxyUrl.encode("${ProxyUrl.ANIMIRU_PLACEHOLDER}/".toHttpUrl(), 7L, "https://h.com/a.m3u8".toHttpUrl())
        assertEquals("http://localhost:1/_kp/7/https/h.com/a.m3u8", encoded.toString())
    }

    @Test
    fun `relative urls resolve inside the proxy`() {
        val encoded = ProxyUrl.encode(base, 1L, "https://h.com/hls/720/index.m3u8".toHttpUrl())
        assertEquals("https://h.com/hls/720/seg1.ts".toHttpUrl(), ProxyUrl.decode(encoded.resolve("seg1.ts")!!)!!.second)
        assertEquals("https://h.com/hls/key.bin".toHttpUrl(), ProxyUrl.decode(encoded.resolve("../key.bin")!!)!!.second)
    }

    @Test
    fun `ignores other urls`() {
        assertNull(ProxyUrl.decode("http://127.0.0.1:4321/other/1/https/h.com/a".toHttpUrl()))
        assertNull(ProxyUrl.decode("https://h.com/_kp/1/https/h.com/a".toHttpUrl()))
        assertNull(ProxyUrl.decode("http://127.0.0.1:4321/_kp/x/https/h.com/a".toHttpUrl()))
    }

    @Test
    fun `keeps hls byte ranges and non-http uris`() {
        val base = "http://127.0.0.1:1234/".toHttpUrl()
        val playlistUrl = "https://cdn.com/v/index.m3u8".toHttpUrl()
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXT-X-KEY:METHOD=AES-128,URI="data:text/plain;base64,AAEC"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="urn:uuid:1234"
            #EXT-X-BYTERANGE:1000@720
            media.mp4
            #EXT-X-BYTERANGE:2000
            media.mp4
            data:video/mp2t;base64,AAAA
        """.trimIndent()
        val rewritten = StreamPipeline.rewriteHls(playlist) { raw ->
            playlistUrl.resolve(raw)?.let { ProxyUrl.encode(base, 7L, it).toString() } ?: raw
        }
        assertEquals(
            """
            #EXTM3U
            #EXT-X-MAP:URI="http://127.0.0.1:1234/_kp/7/https/cdn.com/v/init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXT-X-KEY:METHOD=AES-128,URI="data:text/plain;base64,AAEC"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="urn:uuid:1234"
            #EXT-X-BYTERANGE:1000@720
            http://127.0.0.1:1234/_kp/7/https/cdn.com/v/media.mp4
            #EXT-X-BYTERANGE:2000
            http://127.0.0.1:1234/_kp/7/https/cdn.com/v/media.mp4
            data:video/mp2t;base64,AAAA
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `rewrites hls uris and attributes`() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="/keys/k.bin",IV=0x1
            #EXT-X-MAP:URI="init.mp4"
            seg1.ts
              https://other.com/seg2.ts
        """.trimIndent()
        val rewritten = StreamPipeline.rewriteHls(playlist) { "<$it>" }
        assertEquals(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="</keys/k.bin>",IV=0x1
            #EXT-X-MAP:URI="<init.mp4>"
            <seg1.ts>
            <https://other.com/seg2.ts>
            """.trimIndent(),
            rewritten,
        )
    }

    private fun dash(manifest: String, url: String = "https://h.com/v/manifest.mpd") = StreamPipeline.rewriteDash(manifest, url.toHttpUrl()) { it.toString() }

    @Test
    fun `makes dash base urls absolute`() {
        assertEquals(
            """<MPD><BaseURL>https://cdn.com/v/</BaseURL><Period><BaseURL>https://cdn.com/v/p/</BaseURL></Period></MPD>""",
            dash("""<MPD><BaseURL>https://cdn.com/v/</BaseURL><Period><BaseURL>p/</BaseURL></Period></MPD>"""),
        )
        assertEquals(
            """<MPD><Period><BaseURL>https://h.com/v/a/</BaseURL><BaseURL>https://h.com/v/b/</BaseURL></Period></MPD>""",
            dash("""<MPD><Period><BaseURL>a/</BaseURL><BaseURL>b/</BaseURL></Period></MPD>"""),
        )
    }

    @Test
    fun `leaves inherited dash templates relative`() {
        val manifest = """<MPD><AdaptationSet><SegmentTemplate media="${'$'}Number${'$'}.m4s" initialization="../init.mp4"/>""" +
            """<Representation><BaseURL>r1/</BaseURL></Representation></AdaptationSet></MPD>"""
        assertEquals(
            """<MPD><AdaptationSet><SegmentTemplate media="${'$'}Number${'$'}.m4s" initialization="../init.mp4"/>""" +
                """<Representation><BaseURL>https://h.com/v/r1/</BaseURL></Representation></AdaptationSet></MPD>""",
            dash(manifest),
        )
    }

    @Test
    fun `resolves dash urls that climb above the root`() {
        assertEquals(
            """<MPD><SegmentURL media="https://h.com/x/seg.m4s" sourceURL="https://h.com/abs.m4s"/></MPD>""",
            dash("""<MPD><SegmentURL media="../../x/seg.m4s" sourceURL="/abs.m4s"/></MPD>""", "https://h.com/a/manifest.mpd"),
        )
    }

    @Test
    fun `catches hidden climbs above the root`() {
        assertEquals(
            """<MPD><SegmentURL media="https://h.com/x/seg.m4s"/><SegmentURL media="b/../seg.m4s"/></MPD>""",
            dash("""<MPD><SegmentURL media="./../../x/seg.m4s"/><SegmentURL media="b/../seg.m4s"/></MPD>""", "https://h.com/a/manifest.mpd"),
        )
    }

    @Test
    fun `adds the final location of a redirected dash manifest`() {
        val manifest = """<MPD><Period><SegmentTemplate media="seg-${'$'}Number${'$'}.m4s"/></Period></MPD>"""
        assertEquals(
            """<MPD><BaseURL>https://h.com/v/manifest.mpd</BaseURL><Period><SegmentTemplate media="seg-${'$'}Number${'$'}.m4s"/></Period></MPD>""",
            StreamPipeline.rewriteDash(manifest, "https://h.com/v/manifest.mpd".toHttpUrl(), redirected = true) { it.toString() },
        )
        val withBase = """<MPD><BaseURL>https://cdn.com/</BaseURL><Period/></MPD>"""
        assertEquals(withBase, StreamPipeline.rewriteDash(withBase, "https://h.com/v/manifest.mpd".toHttpUrl(), redirected = true) { it.toString() })
    }

    @Test
    fun `rewrites dash attributes in any quoting`() {
        assertEquals(
            """<MPD><SegmentURL media = 'https://h.com/seg.m4s'/></MPD>""",
            dash("""<MPD><SegmentURL media = '/seg.m4s'/></MPD>"""),
        )
    }

    @Test
    fun `escapes kept dash paths for the local server`() {
        assertEquals(
            """<MPD><SegmentTemplate media="a%252Fb%2B${'$'}Number%08d${'$'}.m4s?t=a%2F"/></MPD>""",
            dash("""<MPD><SegmentTemplate media="a%2Fb+${'$'}Number%08d${'$'}.m4s?t=a%2F"/></MPD>"""),
        )
    }

    @Test
    fun `decodes xml character references in dash urls`() {
        assertEquals(
            """<MPD><SegmentURL media="https://h.com/seg.m4s?a=1&amp;b=2&amp;c=&amp;#38;"/></MPD>""",
            dash("""<MPD><SegmentURL media="/seg.m4s?a=1&#38;b=2&#x26;c=&amp;#38;"/></MPD>"""),
        )
    }

    @Test
    fun `keeps fragments through the proxy`() {
        val target = "https://cdn.com/v.mp4?a=1#variant=jp".toHttpUrl()
        val proxy = ProxyUrl.encode("http://127.0.0.1:8000/".toHttpUrl(), 7L, target)
        assertEquals("variant=jp", proxy.fragment)
        assertEquals(7L to target, ProxyUrl.decode(proxy))
    }

    @Test
    fun `keeps dash format tokens through the proxy`() {
        val rewritten = StreamPipeline.rewriteDash(
            """<MPD><SegmentTemplate media="https://h.com/v/seg-${'$'}Number%05d${'$'}.m4s"/></MPD>""",
            "https://h.com/v/manifest.mpd".toHttpUrl(),
        ) { ProxyUrl.encode(base, 1L, it).toString() }
        assertEquals(
            """<MPD><SegmentTemplate media="http://127.0.0.1:4321/_kp/1/https/h.com/v/seg-${'$'}Number%05d${'$'}.m4s"/></MPD>""",
            rewritten,
        )
    }
}
