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
