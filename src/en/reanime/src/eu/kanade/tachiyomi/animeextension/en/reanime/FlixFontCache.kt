package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// Downloads the ASS fonts in the embed page's extracted_fonts[] into
// mpv's fonts dir, so HLS subtitles render with the right positioning.
object FlixFontCache {

    private const val TAG = "ReAnimeFonts"
    private const val MAX_FONT_BYTES = 25L * 1024 * 1024
    private const val MAX_TOTAL_CACHE_BYTES = 50L * 1024 * 1024

    // Full per-file vault URLs, e.g. https://vault-95.../fonts/<fileId>/<name>.
    private val FONT_URL_REGEX =
        Regex("""(https://[^"'\s)]+/fonts/([0-9a-fA-F-]{36})/([^"'\s)]+))""")

    private val EXTRACTED_FONTS_REGEX = Regex(
        """"extracted_fonts"\s*:\s*\[(.*?)]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val FONT_NAME_REGEX = Regex(""""([^"']+?\.(?:ttf|otf|ttc))"""", RegexOption.IGNORE_CASE)
    private val VAULT_HOST_REGEX = Regex("""(https://[^"'\s)]+?)/(?:fonts|subtitles)/""")
    private val FILE_ID_REGEX = Regex("""/(?:fonts|subtitles)/([0-9a-fA-F-]{36})/""")

    private data class Font(val url: String, val fileId: String, val name: String)

    suspend fun ensureFonts(
        client: OkHttpClient,
        fontHeaders: Headers,
        html: String,
        embedJson: String,
        subtitleUrls: List<String>,
    ) = withContext(Dispatchers.IO) {
        val fonts = collectFonts(html, embedJson, subtitleUrls)
        if (fonts.isEmpty()) return@withContext

        val mpvFontsDir: File
        val cacheRoot: File
        try {
            val appContext = applicationContext
            mpvFontsDir = File(appContext.filesDir, "mpv/fonts").apply { mkdirs() }
            cacheRoot = File(appContext.cacheDir, "reanime-fonts").apply { mkdirs() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve app dirs: $e")
            return@withContext
        }

        cleanOldFonts(cacheRoot, mpvFontsDir)

        val fontClient = client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        supervisorScope {
            fonts.map { font ->
                async(Dispatchers.IO) {
                    try {
                        ensureFont(fontClient, fontHeaders, cacheRoot, mpvFontsDir, font)
                    } catch (e: Exception) {
                        Log.w(TAG, "Font failed: ${font.name}: $e")
                    }
                }
            }.forEach { it.await() }
        }
    }

    private fun collectFonts(
        html: String,
        embedJson: String,
        subtitleUrls: List<String>,
    ): List<Font> {
        val sources = listOf(html, embedJson) + subtitleUrls
        val found = linkedSetOf<Font>()

        sources.forEach { source ->
            FONT_URL_REGEX.findAll(source).forEach { match ->
                sanitize(decode(match.groupValues[3]))?.let { name ->
                    found.add(Font(match.groupValues[1], match.groupValues[2], name))
                }
            }
        }

        // Bare filenames share the payload's vault host and file id.
        val vaultHost = sources.asSequence()
            .flatMap { VAULT_HOST_REGEX.findAll(it) }
            .firstOrNull()?.groupValues?.get(1)
        val fileId = found.firstOrNull()?.fileId
            ?: sources.asSequence()
                .flatMap { FILE_ID_REGEX.findAll(it) }
                .firstOrNull()?.groupValues?.get(1)
        if (vaultHost != null && fileId != null) {
            listOf(html, embedJson).forEach { source ->
                EXTRACTED_FONTS_REGEX.findAll(source).forEach { block ->
                    FONT_NAME_REGEX.findAll(block.groupValues[1]).forEach { match ->
                        sanitize(match.groupValues[1].trim())?.let { name ->
                            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                            found.add(Font("$vaultHost/fonts/$fileId/$encoded", fileId, name))
                        }
                    }
                }
            }
        }

        return found.toList()
    }

    private fun decode(raw: String): String = runCatching { URLDecoder.decode(raw.trim(), "UTF-8").trim() }.getOrNull().orEmpty()

    private fun sanitize(raw: String): String? {
        val clean = raw.substringBefore('?')
        val name = clean.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || name.length > 128 || ".." in name) return null
        if (!name.endsWith(".ttf", ignoreCase = true) &&
            !name.endsWith(".otf", ignoreCase = true) &&
            !name.endsWith(".ttc", ignoreCase = true)
        ) {
            return null
        }
        return name
    }

    private suspend fun ensureFont(
        client: OkHttpClient,
        fontHeaders: Headers,
        cacheRoot: File,
        mpvFontsDir: File,
        font: Font,
    ) {
        val cached = File(File(cacheRoot, font.fileId), font.name)
        if (cached.length() <= 0) download(client, fontHeaders, font, cached)
        if (cached.length() <= 0) return

        // Copy to mpv/fonts if missing or file content differs; refresh timestamp if already present
        val installed = File(mpvFontsDir, font.name)
        val now = System.currentTimeMillis()
        if (!installed.isFile || !installed.contentEquals(cached)) {
            cached.copyTo(installed, overwrite = true)
            Log.i(TAG, "Installed font: ${font.name}")
        } else {
            installed.setLastModified(now)
            cached.setLastModified(now)
        }
    }

    private fun File.contentEquals(other: File): Boolean {
        if (!this.exists() || !other.exists()) return false
        if (this.length() != other.length()) return false
        return try {
            this.readBytes().contentEquals(other.readBytes())
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun download(
        client: OkHttpClient,
        fontHeaders: Headers,
        font: Font,
        target: File,
    ) {
        if (!font.url.startsWith("https://")) return
        val dir = target.parentFile ?: return
        dir.mkdirs()

        val tmp = withContext(Dispatchers.IO) {
            File.createTempFile("font_", ".tmp", dir)
        }
        try {
            client.newCall(Request.Builder().url(font.url).headers(fontHeaders).build())
                .awaitSuccess().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Font HTTP ${response.code}: ${font.name}")
                        return
                    }
                    if (response.body.contentLength() > MAX_FONT_BYTES) {
                        Log.w(TAG, "Font too large: ${font.name}")
                        return
                    }
                    var total = 0L
                    response.body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                total += n
                                if (total > MAX_FONT_BYTES) error("Font too large: ${font.name}")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    if (tmp.length() <= 0) {
                        Log.w(TAG, "Font empty: ${font.name}")
                        return
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                    }
                    Log.i(TAG, "Downloaded font: ${font.name} (${target.length()} bytes)")
                }
        } finally {
            tmp.delete()
        }
    }

    private fun cleanOldFonts(cacheRoot: File, mpvFontsDir: File) {
        try {
            val allFiles = listOf(cacheRoot, mpvFontsDir)
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
                .sortedBy { it.lastModified() } // Least recently used first

            var totalSize = allFiles.sumOf { it.length() }
            for (file in allFiles) {
                if (totalSize <= MAX_TOTAL_CACHE_BYTES) break
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Font cache cleanup failed: $e")
        }
    }
}
