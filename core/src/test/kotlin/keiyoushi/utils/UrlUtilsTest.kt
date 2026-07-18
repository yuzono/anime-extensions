package keiyoushi.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlUtilsTest {

    @Test
    fun `fixUrl with empty string returns null`() {
        assertNull(UrlUtils.fixUrl(""))
    }

    @Test
    fun `fixUrl with valid http URL returns unchanged`() {
        val url = "http://example.com/path"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with valid https URL returns unchanged`() {
        val url = "https://example.com/path"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with JSON object returns unchanged`() {
        val json = "{\"key\": \"value\", \"url\": \"https://example.com\"}"
        assertEquals(json, UrlUtils.fixUrl(json))
    }

    @Test
    fun `fixUrl with protocol-relative URL adds https`() {
        val url = "//example.com/path"
        assertEquals("https://example.com/path", UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with missing protocol prefix removes redundant prefix`() {
        val url = "http://example.comhttps://example.com/path"
        assertEquals("https://example.com/path", UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl strips non-url prefix before first http URL`() {
        val url = "prefix text https://example.com/path"
        assertEquals("https://example.com/path", UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with two parameters and invalid base url returns null`() {
        assertNull(UrlUtils.fixUrl("resource.html", "not-a-valid-url"))
    }

    @Test
    fun `fixUrl with relative path only keeps the path`() {
        val url = "path/to/resource"
        assertEquals("path/to/resource", UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with two parameter and absolute path`() {
        val baseUrl = "https://example.com/dir/page.html"
        val url = "/path/to/resource"
        val expected = "https://example.com/path/to/resource"
        assertEquals(expected, UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameter and absolute path and baseUrl`() {
        val baseUrl = "https://example.com/"
        val url = "/path/to/resource"
        val expected = "https://example.com/path/to/resource"
        assertEquals(expected, UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameter and relative path and baseUrl`() {
        val baseUrl = "https://example.com/"
        val url = "path/to/resource"
        val expected = "https://example.com/path/to/resource"
        assertEquals(expected, UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameters and relative path`() {
        val baseUrl = "https://example.com/dir/page.html"
        val url = "resource.html"
        val expected = "https://example.com/dir/resource.html"
        assertEquals(expected, UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameters and protocol-relative URL`() {
        val baseUrl = "https://example.com/dir/page.html"
        val url = "//cdn.example.com/resource"
        assertEquals("https://cdn.example.com/resource", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameters and absolute URL`() {
        val baseUrl = "https://example.com/dir/page.html"
        val url = "https://other.com/resource"
        assertEquals("https://other.com/resource", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with two parameters and empty url returns null`() {
        val baseUrl = "https://example.com/dir/page.html"
        assertNull(UrlUtils.fixUrl("", baseUrl))
    }

    @Test
    fun `fixUrl with two parameters and JSON object returns unchanged`() {
        val baseUrl = "https://example.com/dir/page.html"
        val json = "{\"key\": \"value\"}"
        assertEquals(json, UrlUtils.fixUrl(json, baseUrl))
    }

    @Test
    fun `fixUrl with complex relative path`() {
        val baseUrl = "https://example.com/dir/subdir/page.html"
        val url = "../resource.html"
        assertEquals("https://example.com/dir/subdir/../resource.html", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with nested directory structure`() {
        val baseUrl = "https://example.com/a/b/c/page.html"
        val url = "d/e/f.html"
        assertEquals("https://example.com/a/b/c/d/e/f.html", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl preserves query parameters with relative path`() {
        val baseUrl = "https://example.com/dir/page.html?param=value"
        val url = "resource.html"
        assertEquals("https://example.com/dir/resource.html", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with base URL containing query and absolute path`() {
        val baseUrl = "https://example.com/dir/page.html?param=value#fragment"
        val url = "/newpath"
        assertEquals("https://example.com/newpath", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl single parameter with special characters in URL`() {
        val url = "https://example.com/path?query=value&other=123#fragment"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with URL containing only domain`() {
        val url = "https://example.com"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl two parameters with URL containing only domain`() {
        val baseUrl = "https://base.com/path"
        val url = "resource.html"
        assertEquals("https://base.com/resource.html", UrlUtils.fixUrl(url, baseUrl))
    }

    @Test
    fun `fixUrl with port number in URL`() {
        val url = "https://example.com:8080/path"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl two parameters with port number in base`() {
        val baseUrl = "https://example.com:8080/dir/page.html"
        val url = "/path"
        assertEquals("https://example.com:8080/path", UrlUtils.fixUrl(url, baseUrl))
    }
}
