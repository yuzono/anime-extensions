package eu.kanade.tachiyomi.animeextension.id.nekopoi

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NekopoiTest {

    @Test
    fun `filters build correct search parameters`() {
        val categoryFilter = Filters.CategoryFilter(1) // Hentai
        val genreFilter = Filters.GenreFilter(6) // Big Oppai

        val filterList = AnimeFilterList(categoryFilter, genreFilter)
        val params = Filters.getSearchParameters(filterList)

        assertEquals("hentai", params.category)
        assertEquals("big-oppai", params.genre)
    }

    @Test
    fun `anime details parsing extracts metadata correctly`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:image" content="https://nekopoi.care/wp-content/uploads/2026/08/poster.jpg">
            </head>
            <body>
                <div class="nk-series-detail">
                    <div class="nk-series-poster" style="background-image: url('https://nekopoi.care/wp-content/uploads/2026/08/poster.jpg')"></div>
                    <span class="nk-series-synopsis">
                        <b>Inaka ni wa Kore Kurai Shika Goraku ga Nai</b>
                        <p>Setelah gagal mencari pekerjaan di kota, seorang pria pindah ke desa.</p>
                    </span>
                </div>
                <div class="nk-series-meta-list">
                    <ul>
                        <li><b>Judul Jepang</b>: 田舎にはこれくらいしか娯楽がない</li>
                        <li><b>Jenis</b>: Hentai</li>
                        <li><b>Episode</b>: 2</li>
                        <li><b>Status</b>: Completed</li>
                        <li><b>Tayang</b>: Jul 31, 2026</li>
                        <li><b>Produser</b>: Bunny Walker</li>
                        <li><b>Genre</b>: <a href="https://nekopoi.care/genres/big-oppai/">Big Oppai</a>, <a href="https://nekopoi.care/genres/romance/">Romance</a></li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val anime = NekopoiParser.parseAnimeDetails(doc)

        assertEquals("Inaka ni wa Kore Kurai Shika Goraku ga Nai", anime.title)
        assertEquals("Bunny Walker", anime.author)
        assertEquals(SAnime.COMPLETED, anime.status)
        assertEquals("Big Oppai, Romance", anime.genre)
        assertTrue(anime.description!!.contains("Setelah gagal mencari pekerjaan"))
        assertEquals("https://nekopoi.care/wp-content/uploads/2026/08/poster.jpg", anime.thumbnailUrl)
    }

    @Test
    fun `anime details parsing falls back to post body when series-meta is absent`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:image" content="https://nekopoi.care/poster2.jpg">
            </head>
            <body>
                <div class="nk-post-header">
                    <h1>[NEW Release] Deco x Deco Episode 1 Subtitle Indonesia</h1>
                </div>
                <div class="nk-post-body">
                    <div class="konten">
                        <p class="separator">Sinopsis Deco x Deco cerita seru.</p>
                        <p class="separator"><b>Genre : </b>Big Oppai, Romance</p>
                        <p class="separator"><b>Producers </b>: PoRO</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val anime = NekopoiParser.parseAnimeDetails(doc)

        assertEquals("[NEW Release] Deco x Deco Episode 1 Subtitle Indonesia", anime.title)
        assertEquals("Big Oppai, Romance", anime.genre)
        assertTrue(anime.description!!.contains("Sinopsis Deco x Deco"))
    }

    @Test
    fun `episode list parsing extracts episodes correctly`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="nk-episode-grid">
                    <ul>
                        <li>
                            <a href="https://nekopoi.care/inaka-episode-1-subtitle-indonesia/" class="nk-episode-card">
                                <div class="nk-episode-card-thumb" style="background-image: url('https://nekopoi.care/thumb1.jpg')">
                                    <span class="nk-episode-badge">Ep 1</span>
                                </div>
                                <div class="nk-episode-card-info">
                                    <span class="nk-episode-card-title">Inaka Episode 1 Subtitle Indonesia</span>
                                    <span class="nk-episode-card-date">1 Agustus 2026</span>
                                </div>
                            </a>
                        </li>
                        <li>
                            <a href="https://nekopoi.care/inaka-episode-2-subtitle-indonesia/" class="nk-episode-card">
                                <div class="nk-episode-card-thumb" style="background-image: url('https://nekopoi.care/thumb2.jpg')">
                                    <span class="nk-episode-badge">Ep 2</span>
                                </div>
                                <div class="nk-episode-card-info">
                                    <span class="nk-episode-card-title">Inaka Episode 2 Subtitle Indonesia</span>
                                    <span class="nk-episode-card-date">2 Agustus 2026</span>
                                </div>
                            </a>
                        </li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val episodes = NekopoiParser.parseEpisodeList(doc)

        assertEquals(2, episodes.size)
        assertEquals("inaka-episode-1-subtitle-indonesia", episodes[0].url.trim('/'))
        assertEquals(1.0f, episodes[0].episodeNumber)
        assertEquals("Inaka Episode 1 Subtitle Indonesia", episodes[0].name)
        assertTrue(episodes[0].dateUpload > 0L)

        assertEquals("inaka-episode-2-subtitle-indonesia", episodes[1].url.trim('/'))
        assertEquals(2.0f, episodes[1].episodeNumber)
    }

    @Test
    fun `single episode creation from standalone episode page`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="nk-post-header">
                    <h1>[NEW Release] Deco x Deco Episode 1 Subtitle Indonesia</h1>
                    <div class="nk-post-header-meta">
                        <span>Minggu, 16 Agustus 2026</span>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val ep = NekopoiParser.createSingleEpisode(doc, "https://nekopoi.care/deco-episode-1/")

        assertEquals("deco-episode-1", ep.url.trim('/'))
        assertEquals(1.0f, ep.episodeNumber)
        assertNotNull(ep.name)
    }

    @Test
    fun `search and popular anime parsing handles search results`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="nk-search-results">
                    <ul>
                        <li>
                            <a href="https://nekopoi.care/hentai/so_low/" class="nk-search-item">
                                <div class="nk-search-thumb" style="background-image: url('https://nekopoi.care/thumb.jpg')"></div>
                                <div class="nk-search-info">
                                    <h2>So_Low</h2>
                                    <p class="nk-search-desc">Sinopsis: Guru les.</p>
                                </div>
                            </a>
                        </li>
                    </ul>
                </div>
                <nav class="navigation pagination">
                    <div class="nav-links">
                        <span class="page-numbers current">1</span>
                        <a class="next page-numbers" href="https://nekopoi.care/page/2/">Selanjutnya</a>
                    </div>
                </nav>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val page = NekopoiParser.parseAnimePage(doc)

        assertEquals(1, page.animes.size)
        assertEquals("hentai/so_low", page.animes[0].url.trim('/'))
        assertEquals("So_Low", page.animes[0].title)
        assertEquals("https://nekopoi.care/thumb.jpg", page.animes[0].thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `status parsing handles variations`() {
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("Completed"))
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("completed"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("Ongoing"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("ongoing"))
        assertEquals(SAnime.UNKNOWN, NekopoiParser.parseStatus("Unknown"))
        assertEquals(SAnime.UNKNOWN, NekopoiParser.parseStatus(null))
    }

    @Test
    fun `episode number parsing handles various formats`() {
        assertEquals(1f, NekopoiParser.parseEpisodeNumber("Ep 1", "Title", 0f))
        assertEquals(2.5f, NekopoiParser.parseEpisodeNumber("Episode 2.5", "Title", 0f))
        assertEquals(3f, NekopoiParser.parseEpisodeNumber("", "Deco x Deco Episode 3", 0f))
        assertEquals(4f, NekopoiParser.parseEpisodeNumber("", "Deco x Deco Ep 4 Subtitle Indonesia", 0f))
        assertEquals(99f, NekopoiParser.parseEpisodeNumber("", "Special", 99f))
    }

    @Test
    fun `extractBgUrl parses various CSS style formats`() {
        assertEquals("https://nekopoi.care/img.jpg", NekopoiParser.extractBgUrl("background-image: url('https://nekopoi.care/img.jpg')"))
        assertEquals("https://nekopoi.care/img2.jpg", NekopoiParser.extractBgUrl("background-image: url(\"https://nekopoi.care/img2.jpg\");"))
        assertEquals("https://nekopoi.care/img3.jpg", NekopoiParser.extractBgUrl("background: url(https://nekopoi.care/img3.jpg) center;"))
    }
}
