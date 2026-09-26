package eu.kanade.tachiyomi.animeextension.en.hentaihaven

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.parser.Parser

private const val IMAGE_BASE_URL = "https://img.hentaihaven.xxx/"

@Serializable
class JsonLdDto(
    @SerialName("@type") private val type: String? = null,
    private val contentUrl: String? = null,
) {
    fun videoUrlOrNull() = contentUrl.takeIf { type == "VideoObject" }
}

@Serializable
class CatalogueDto(
    private val data: List<AnimeDto>,
    private val totalPages: Int,
) {
    fun toAnimesPage(page: Int) = AnimesPage(data.map { it.toSAnime() }.distinctBy { it.url }, page < totalPages)
}

@Serializable
class AnimeDto(
    private val slug: String,
    @SerialName("title") private val titleData: TitleDto,
    private val meta: MetaDto? = null,
) {
    @Serializable
    class TitleDto(val rendered: String)

    @Serializable
    class MetaDto(
        @SerialName("vraven_remote_thumbnail") val thumbnail: String? = null,
    )

    fun toSAnime() = SAnime.create().apply {
        url = "/watch/$slug/"
        title = Parser.unescapeEntities(titleData.rendered, false)
        thumbnail_url = meta?.thumbnail?.takeIf { it.isNotBlank() }?.let(::thumbnailUrl)
    }

    private fun thumbnailUrl(path: String): String = if (path.startsWith("http")) {
        path.toHttpUrlOrNull()?.toString() ?: path
    } else {
        IMAGE_BASE_URL.toHttpUrl().newBuilder().addPathSegments(path).build().toString()
    }
}

@Serializable
class FilterOptionsDto(
    private val genres: List<OptionDto>,
    private val authors: List<OptionDto>,
    private val years: List<OptionDto>,
) {
    @Serializable
    class OptionDto(
        val id: Int,
        val name: String,
    )

    fun genreOptions() = genres.toOptions()

    fun authorOptions() = authors.toOptions()

    fun yearOptions() = years.toOptions()

    private fun List<OptionDto>.toOptions() = listOf("All" to "") + map { it.name to it.id.toString() }
}
