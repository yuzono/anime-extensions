package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import keiyoushi.annotation.AnikkuOnly
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A simple implementation for sources from a website using Jsoup, an HTML parser.
 */
@Suppress("unused", "unused_parameter")
@Deprecated(
    message = "In most cases sources only require a subset of the methods from this class. " +
        "Source developers should make their own implementation according to their needs.",
)
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     * @param response the response from the site.
     */
    override fun popularAnimeParse(response: Response): AnimesPage = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun popularAnimeSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [popularAnimeSelector].
     */
    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun popularAnimeNextPageSelector(): String?

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     *
     * @param response the response from the site.
     */
    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun searchAnimeSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [searchAnimeSelector].
     */
    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun searchAnimeNextPageSelector(): String?

    /**
     * Parses the response from the site and returns a [AnimesPage] object.
     *
     * @param response the response from the site.
     */
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each anime.
     */
    protected abstract fun latestUpdatesSelector(): String

    /**
     * Returns an anime from the given [element]. Most sites only show the title and the url, it's
     * totally fine to fill only those two values.
     *
     * @param element an element obtained from [latestUpdatesSelector].
     */
    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    /**
     * Returns the Jsoup selector that returns the <a> tag linking to the next page, or null if
     * there's no next page.
     */
    protected abstract fun latestUpdatesNextPageSelector(): String?

    /**
     * Parses the response from the site and returns the details of an anime.
     *
     * @param response the response from the site.
     */
    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Stub!")

    /**
     * Returns the details of the anime from the given [document].
     *
     * @param document the parsed document.
     */
    protected abstract fun animeDetailsParse(document: Document): SAnime

    // KMK -->

    /**
     * Parses the response from the site and returns a list of related animes.
     * Normally it's not needed to override this method.
     *
     * @since anikku/extensions-lib 15
     * @param response the response from the site.
     */
    @AnikkuOnly
    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each related animes.
     *
     * @since anikku/extensions-lib 15
     */
    @AnikkuOnly
    protected open fun relatedAnimeListSelector(): String = throw Exception("Stub!")

    /**
     * Returns an anime from the given element.
     *
     * @since anikku/extensions-lib 15
     * @param element an element obtained from [relatedAnimeListSelector].
     */
    @AnikkuOnly
    protected open fun relatedAnimeFromElement(element: Element): SAnime = throw Exception("Stub!")
    // KMK <--

    /**
     * Parses the response from the site and returns a list of episodes.
     *
     * @param response the response from the site.
     */
    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each episode.
     */
    protected abstract fun episodeListSelector(): String

    /**
     * Returns an episode from the given element.
     *
     * @param element an element obtained from [episodeListSelector].
     */
    protected abstract fun episodeFromElement(element: Element): SEpisode

    /**
     * Parses the response from the site and returns a list of seasons.
     *
     * @since extensions-lib 16
     * @param response the response from the site.
     */
    override fun seasonListParse(response: Response): List<SAnime> = throw Exception("Stub!")

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each season.
     *
     * @since extensions-lib 16
     */
    protected abstract fun seasonListSelector(): String

    /**
     * Returns a season from the given element.
     *
     * @since extensions-lib 16
     * @param element an element obtained from [seasonListSelector].
     */
    protected abstract fun seasonFromElement(element: Element): SAnime
}
