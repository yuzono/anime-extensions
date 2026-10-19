package aniyomi.lib.megaextractor

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeBase64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MegaStreamServerTest {

    private val plaintext = ByteArray(257) { it.toByte() }

    // Fixed vector generated independently with Python cryptography, using AES key
    // 2b7e151628aed2a6abf7158809cf4f3c and nonce f0f1f2f3f4f5f6f7, counter zero.
    private val key = "24/n5dxbJFGr9heLDcpJO/Dx8vP09fb3AAECAwQFBgc=".decodeBase64()!!.toByteArray()
    private val encrypted = (
        "DC65tV7cYS0R9/c+l/5FDb++juH+zHsk+LzbzVWcrtEDAYIvsC5nft5K7v2jRGjN6SabR6AvhqMDap6x9wegZ1zAic1Msgru" +
            "JLJUskwYMGASTLYVoD19ukjSta2qigqDCuc6+uStA7I6qbk7kBgDwQ/aZ5VCnT4QHkrygGd5EA/G9GDJeXIMceZgzoog4FZa9" +
            "lbwb7mW//2uNfDEBhn+OnEVbhrRSF0GH7yi39PxfyWMM+zU7patIbv6w9MRXaBTotYEMwYdSHrFMMqogN5OMcUlw0c7SnYDY" +
            "VnZK7el5i8WJgctR5C92bLO2zdfBPuEIwhP+6q6sup5c91WOLxXmUk="
        ).decodeBase64()!!.toByteArray()

    private val calls = AtomicInteger()
    private val closed = AtomicInteger()
    private val downstream = OkHttpClient()
    private lateinit var localUrl: String

    @Volatile
    private var errorCode = 0

    @Volatile
    private var truncate = false

    @Volatile
    private var failTransport = false

    @Before
    fun setUp() {
        val upstream = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("retained", chain.request().header("X-Source-Test"))
            calls.incrementAndGet()
            if (failTransport) throw IOException("Offline")
            val bounds = chain.request().url.pathSegments.last().split('-').map(String::toInt)
            val bytes = encrypted.copyOfRange(bounds[0], bounds[1] + 1).let {
                if (truncate) it.copyOf(it.size - 1) else it
            }
            val body = object : ResponseBody() {
                private val buffer = Buffer().write(bytes)
                private val isClosed = AtomicBoolean()
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = bytes.size.toLong()
                override fun source(): BufferedSource = buffer
                override fun close() {
                    if (isClosed.compareAndSet(false, true)) closed.incrementAndGet()
                    super.close()
                }
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (errorCode == 0) 200 else errorCode)
                .message("Fixture")
                .body(body)
                .build()
        }.build()
        localUrl = MegaStreamServer.register(upstream, "https://fixture.userstorage.mega.co.nz/dl/file".toHttpUrl(), plaintext.size.toLong(), key, Headers.headersOf("X-Source-Test", "retained"))
    }

    @After
    fun tearDown() {
        MegaStreamServer.stop()
        downstream.connectionPool.evictAll()
    }

    private fun request(range: String? = null, method: String = "GET"): Response {
        val request = Request.Builder().url(localUrl).apply {
            if (range != null) header("Range", range)
            method(method, if (method == "POST") "".toRequestBody() else null)
        }.build()
        return downstream.newCall(request).execute()
    }

    @Test
    fun fullFileAndHead() {
        request(method = "HEAD").use {
            assertEquals(200, it.code)
            assertEquals("257", it.header("Content-Length"))
            assertEquals(0, it.body.bytes().size)
            assertEquals(0, calls.get())
        }
        request().use {
            assertEquals(200, it.code)
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertArrayEquals(plaintext, it.body.bytes())
        }
        assertEquals(1, calls.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun seeksAcrossAesBlockBoundaries() {
        for (start in listOf(0, 1, 15, 16, 17, 31, 32, 255, 256)) {
            val end = (start + 35).coerceAtMost(256)
            request("bytes=$start-$end").use {
                assertEquals(206, it.code)
                assertEquals("bytes $start-$end/257", it.header("Content-Range"))
                assertArrayEquals(plaintext.copyOfRange(start, end + 1), it.body.bytes())
            }
        }
        assertEquals(9, calls.get())
        assertEquals(9, closed.get())
    }

    @Test
    fun openEndedSuffixAndOversizedRanges() {
        for ((header, start) in listOf("bytes=17-" to 17, "bytes=-37" to 220, "bytes=-999" to 0, "bytes=250-999" to 250)) {
            request(header).use {
                assertEquals(206, it.code)
                assertArrayEquals(plaintext.copyOfRange(start, plaintext.size), it.body.bytes())
            }
        }
    }

    @Test
    fun invalidRangesDoNotFetchMedia() {
        for (header in listOf("bytes=257-", "bytes=20-10", "bytes=-0", "bytes=-", "items=0-1", "bytes=0-1,4-5", "bytes=invalid-9", "bytes=999999999999999999999-")) {
            request(header).use {
                assertEquals(header, 416, it.code)
                assertEquals("bytes */257", it.header("Content-Range"))
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun concurrentSeeksUseIndependentCounters() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..16).map { start ->
                Callable {
                    request("bytes=$start-${start + 40}").use {
                        assertArrayEquals(plaintext.copyOfRange(start, start + 41), it.body.bytes())
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(16, closed.get())
    }

    @Test
    fun upstreamErrorsDoNotAdvertiseDecryptedRanges() {
        for (code in listOf(403, 429, 503)) {
            errorCode = code
            request("bytes=1-20").use {
                assertEquals(code, it.code)
                assertNull(it.header("Content-Range"))
            }
        }
        assertEquals(3, closed.get())
    }

    @Test
    fun truncatedRangesAreRejectedAndClosed() {
        truncate = true
        request("bytes=1-20").use {
            assertEquals(503, it.code)
        }
        assertEquals(1, closed.get())
    }

    @Test
    fun transportFailuresAreReported() {
        failTransport = true
        request("bytes=1-20").use {
            assertEquals(503, it.code)
        }
    }

    @Test
    fun unsupportedMethodsAndUnknownStreams() {
        request(method = "POST").use { assertEquals(405, it.code) }
        downstream.newCall(Request.Builder().url(localUrl.substringBeforeLast('/') + "/unknown.mp4").build()).execute().use {
            assertEquals(404, it.code)
        }
        assertEquals(0, calls.get())
    }
}
