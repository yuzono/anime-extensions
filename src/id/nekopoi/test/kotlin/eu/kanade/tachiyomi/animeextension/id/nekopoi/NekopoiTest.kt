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

        assertEquals("Deco x Deco Episode 1", anime.title)
        assertEquals("Big Oppai, Romance", anime.genre)
        assertEquals("PoRO", anime.author)
        assertTrue(anime.description!!.contains("Sinopsis Deco x Deco"))
        // Ensure genres and producers are NOT in the description
        assertTrue(!anime.description!!.contains("Genre :"))
        assertTrue(!anime.description!!.contains("Producers :"))
    }

    @Test
    fun `anime details parsing uses exact cover and ignores unrelated sidebar styles`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:image" content="https://nekopoi.care/wp-content/uploads/2026/08/correct-cover.jpg">
            </head>
            <body>
                <div class="sidebar">
                    <div class="ltd" style="background-image: url('https://nekopoi.care/wp-content/uploads/2026/08/random-recommended.jpg')"></div>
                    <div class="nk-player-series-thumb" style="background-image: url('https://nekopoi.care/wp-content/uploads/2026/08/random-series.jpg')"></div>
                </div>
                <div class="nk-post-header">
                    <h1>[L2D] Bertukar Hasrat Kepuasan Bersama Shaula</h1>
                </div>
                <div class="nk-post-body">
                    <div class="konten">
                        <p>Sinopsis cerita Shaula.</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val anime = NekopoiParser.parseAnimeDetails(doc)

        assertEquals("https://nekopoi.care/wp-content/uploads/2026/08/correct-cover.jpg", anime.thumbnailUrl)
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
        assertEquals("Episode 1", episodes[0].name)
        assertTrue(episodes[0].dateUpload > 0L)

        assertEquals("inaka-episode-2-subtitle-indonesia", episodes[1].url.trim('/'))
        assertEquals(2.0f, episodes[1].episodeNumber)
        assertEquals("Episode 2", episodes[1].name)
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
        assertEquals("Episode 1", ep.name)
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
    fun `parseAnimePage supports nk-post-card and filters out sticky blog posts`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="nk-hentai-grid">
                    <div class="nk-post-card">
                        <div class="nk-post-thumb">
                            <div class="nk-thumb-crop" style="background-image: url('https://nekopoi.care/thumb1.jpg')"></div>
                        </div>
                        <div class="nk-post-meta">
                            <h2><a href="https://nekopoi.care/inaka-episode-2-subtitle-indonesia/">[NEW Release] Inaka Episode 2 Subtitle Indonesia</a></h2>
                        </div>
                    </div>
                    <div class="nk-post-card">
                        <div class="nk-post-thumb">
                            <div class="nk-thumb-crop" style="background-image: url('https://nekopoi.care/thumb2.jpg')"></div>
                        </div>
                        <div class="nk-post-meta">
                            <h2><a href="https://nekopoi.care/selamat-tahun-baru-2022/">Selamat Tahun Baru 2022</a></h2>
                        </div>
                    </div>
                    <div class="nk-post-card">
                        <div class="nk-post-thumb">
                            <div class="nk-thumb-crop" style="background-image: url('https://nekopoi.care/thumb3.jpg')"></div>
                        </div>
                        <div class="nk-post-meta">
                            <h2><a href="https://nekopoi.care/happy-anniversary-nekopoi/">Happy 6th Anniversary NekoPoi</a></h2>
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val page = NekopoiParser.parseAnimePage(doc)

        assertEquals(1, page.animes.size)
        assertEquals("inaka-episode-2-subtitle-indonesia", page.animes[0].url.trim('/'))
        assertEquals("Inaka Episode 2", page.animes[0].title)
        assertEquals("https://nekopoi.care/thumb1.jpg", page.animes[0].thumbnailUrl)
    }

    @Test
    fun `extractThumbnail extracts image from nested style and img tags`() {
        val html1 = """<div class="nk-post-card"><div class="nk-post-thumb"><div class="nk-thumb-crop" style="background-image: url('https://nekopoi.care/test1.jpg')"></div></div></div>"""
        val doc1 = Jsoup.parse(html1).body().child(0)
        assertEquals("https://nekopoi.care/test1.jpg", NekopoiParser.extractThumbnail(doc1))

        val html2 = """<div class="card"><img data-src="https://nekopoi.care/test2.jpg" /></div>"""
        val doc2 = Jsoup.parse(html2).body().child(0)
        assertEquals("https://nekopoi.care/test2.jpg", NekopoiParser.extractThumbnail(doc2))
    }

    @Test
    fun `title cleanup removes Subtitle Indonesia, NEW Release, and bracket tags`() {
        assertEquals("Inaka Episode 2", NekopoiParser.cleanTitle("[NEW Release] Inaka Episode 2 Subtitle Indonesia"))
        assertEquals("Onaji Semi Episode 5", NekopoiParser.cleanTitle("Onaji Semi Episode 5 Subtitle Indonesia"))
        assertEquals("[3D] NieR Automata Climax", NekopoiParser.cleanTitle("[3D SUB INDO] NieR Automata Climax"))
        assertEquals("Marika Hase JAV", NekopoiParser.cleanTitle("Marika Hase JAV Subtitle Indonesia"))
        assertEquals("Deco x Deco Episode 1", NekopoiParser.cleanTitle("[NEW RELEASE] Deco x Deco Episode 1 - Sub Indo"))
    }

    @Test
    fun `deduplicateVideos removes duplicate URLs and adds server numbering`() {
        val videos = listOf(
            eu.kanade.tachiyomi.animesource.model.Video("https://stream1.com/vid.mp4", "Doodstream", "https://stream1.com/vid.mp4"),
            eu.kanade.tachiyomi.animesource.model.Video("https://stream2.com/vid.mp4", "Doodstream", "https://stream2.com/vid.mp4"),
            eu.kanade.tachiyomi.animesource.model.Video("https://stream1.com/vid.mp4", "Doodstream", "https://stream1.com/vid.mp4"),
            eu.kanade.tachiyomi.animesource.model.Video("https://stream3.com/vid.mp4", "StreamWish", "https://stream3.com/vid.mp4"),
        )

        val result = NekopoiParser.deduplicateVideos(videos)

        assertEquals(3, result.size)
        assertEquals("Doodstream", result[0].quality)
        assertEquals("Doodstream (2)", result[1].quality)
        assertEquals("StreamWish", result[2].quality)
    }

    @Test
    fun `status parsing handles variations`() {
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("Completed"))
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("completed"))
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("Finished"))
        assertEquals(SAnime.COMPLETED, NekopoiParser.parseStatus("tamat"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("Ongoing"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("on-going"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("berjalan"))
        assertEquals(SAnime.ONGOING, NekopoiParser.parseStatus("sedang berjalan"))
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

    @Test
    fun `parsePopularHentaiList sorts by score descending and paginates correctly`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="nk-hentai-list">
                    <div class="nk-az-item">
                        <a href="https://nekopoi.care/hentai/low-score/" original-title='<div class="nk-tooltip-card"><h2>Low Score Title</h2><div class="nk-tooltip-body"><img src="https://nekopoi.care/low.jpg" class="nk-tooltip-img" /><div class="nk-tooltip-detail"><p><b>Skor</b>: 5.50</p></div></div></div>'>Low Score Title</a>
                    </div>
                    <div class="nk-az-item">
                        <a href="https://nekopoi.care/hentai/high-score/" original-title='<div class="nk-tooltip-card"><h2>High Score Title</h2><div class="nk-tooltip-body"><img src="https://nekopoi.care/high.jpg" class="nk-tooltip-img" /><div class="nk-tooltip-detail"><p><b>Skor</b>: 9.25</p></div></div></div>'>High Score Title</a>
                    </div>
                    <div class="nk-az-item">
                        <a href="https://nekopoi.care/hentai/mid-score/" original-title='<div class="nk-tooltip-card"><h2>Mid Score Title</h2><div class="nk-tooltip-body"><img src="https://nekopoi.care/mid.jpg" class="nk-tooltip-img" /><div class="nk-tooltip-detail"><p><b>Skor</b>: 7.80</p></div></div></div>'>Mid Score Title</a>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val page1 = NekopoiParser.parsePopularHentaiList(doc, page = 1, pageSize = 2)

        assertEquals(2, page1.animes.size)
        assertEquals("High Score Title", page1.animes[0].title)
        assertEquals("https://nekopoi.care/high.jpg", page1.animes[0].thumbnailUrl)
        assertEquals("hentai/high-score", page1.animes[0].url.trim('/'))
        assertEquals("Mid Score Title", page1.animes[1].title)
        assertTrue(page1.hasNextPage)

        val page2 = NekopoiParser.parsePopularHentaiList(doc, page = 2, pageSize = 2)
        assertEquals(1, page2.animes.size)
        assertEquals("Low Score Title", page2.animes[0].title)
        assertEquals(false, page2.hasNextPage)
    }

}
