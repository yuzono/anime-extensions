package keiyoushi.source.model

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoThumbnailsTest {
    private val vtt = """
        WEBVTT

        NOTE thumbnails

        00:00:00.000 --> 00:00:05.000
        thumbnails/sprite_0.webp#xywh=0,0,160,90

        00:05.5 --> 00:00:10.000
        thumbnails/sprite_0.webp#xywh=160,0,160,90

        01:00:00.000 --> 01:00:05.000
        /abs/sprite_1.webp#xywh=0,90,160,90

        00:00:15.000 --> 00:00:20.000
        broken.webp#xywh=99999999999,0,160,90

        00:00:20.000 --> 00:00:25.000
        no-area.webp
    """.trimIndent()

    @Test
    fun `parses sprite cues`() {
        val thumbnails = VideoThumbnails.fromVtt(vtt, "https://cdn.com/v/1/thumbs.vtt".toHttpUrl())!!
        assertEquals(listOf("https://cdn.com/v/1/thumbnails/sprite_0.webp", "https://cdn.com/abs/sprite_1.webp"), thumbnails.imageUrls)
        assertEquals(
            listOf(
                listOf(0L, 0L, 0L, 160L, 90L),
                listOf(0L, 5_500L, 160L, 160L, 90L),
                listOf(1L, 3_600_000L, 0L, 160L, 90L),
            ),
            thumbnails.tiles.map { listOf(it.imageIndex.toLong(), it.timeMs, it.x.toLong(), it.width.toLong(), it.height.toLong()) },
        )
        assertEquals(90, thumbnails.tiles[2].y)
    }

    @Test
    fun `resolves against the final url of a response`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://cdn.com/thumbnails_vtt/abc/").header("Referer", "https://site.com/").header("Authorization", "Bearer x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(vtt.toResponseBody())
            .build()
        val thumbnails = VideoThumbnails.fromVtt(response)!!
        assertEquals("https://cdn.com/thumbnails_vtt/abc/thumbnails/sprite_0.webp", thumbnails.imageUrls[0])
        assertEquals("https://site.com/", thumbnails.headers?.get("Referer"))
        assertNull(thumbnails.headers?.get("Authorization"))
    }

    @Test
    fun `returns null without sprite cues`() {
        assertNull(VideoThumbnails.fromVtt("WEBVTT\n\n00:00.000 --> 00:05.000\nframe.jpg", "https://cdn.com/t.vtt".toHttpUrl()))
    }
}
