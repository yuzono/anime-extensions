package eu.kanade.tachiyomi.animeextension.ru.animelib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class AnimeStatus(
    val id: Int,
)

@Serializable
class CoverInfo(
    val default: String,
)

@Serializable
class GenreInfo(
    val id: Int,
    val name: String,
)

@Serializable
class PublisherInfo(
    val id: Int,
    val name: String,
)

@Serializable
class AuthorInfo(
    val id: Int,
    val name: String,
)

@Serializable
class AnimeType(
    val id: Int,
    val label: String? = null,
)

@Serializable
class AnimeData(
    val id: Int,
    @SerialName("rus_name") val rusName: String? = null,
    @SerialName("eng_name") val engName: String? = null,
    @SerialName("slug_url") val href: String,
    @SerialName("status") val animeStatus: AnimeStatus,
    val cover: CoverInfo,

    // Optional
    val type: AnimeType? = null,
    @SerialName("is_licensed") val licensed: Boolean? = null,
    val summary: JsonElement? = null,
    val genres: List<GenreInfo>? = null,
    val publisher: List<PublisherInfo>? = null,
    val authors: List<AuthorInfo>? = null,
    @SerialName("otherNames") val otherNames: JsonElement? = null,
)

@Serializable
class PageMetaData(
    val next: String? = null,
)

@Serializable
class AnimeList(
    val data: List<AnimeData>,
    val links: PageMetaData? = null,
)

@Serializable
class AnimeInfo(
    val data: AnimeData,
)

@Serializable
class AnimeSimilars(
    val data: List<AnimeSimilar>,
)

@Serializable
class AnimeSimilar(
    val media: AnimeData,
)

// ============================== Episode ==============================
@Serializable
class TeamInfo(
    val id: Int,
    val name: String,
)

@Serializable
class VideoQuality(
    val href: String,
    val quality: Int,
)

@Serializable
class VideoMetaData(
    val id: Int,
    val quality: List<VideoQuality>,
)

@Serializable
class TranslationInfo(
    val id: Int,
)

@Serializable
class SubtitleInfo(
    val id: Int,
    val format: String,
    val src: String,
)

@Serializable
class VideoInfo(
    val id: Int,
    val player: String,
    val team: TeamInfo,

    @SerialName("translation_type") val translationInfo: TranslationInfo,

    // Kodik player
    val src: String? = null,

    // Animelib player
    val video: VideoMetaData? = null,
    val subtitles: List<SubtitleInfo>? = null,
)

@Serializable
class EpisodeInfo(
    val id: Int,
    @SerialName("name") val episodeName: String,
    val number: String,
    val season: String,
    @SerialName("created_at") val date: String,

    // Optional
    val players: List<VideoInfo>? = null,
)

@Serializable
class EpisodeVideoData(
    val data: EpisodeInfo,
)

@Serializable
class EpisodeList(
    val data: List<EpisodeInfo>,
)

// ============================== VideoServer ==============================
@Serializable
class VideoServerInfo(
    val id: String,
    val label: String,
    val url: String,
)

@Serializable
class VideoServers(
    val videoServers: List<VideoServerInfo>,
)

@Serializable
class VideoServerData(
    val data: VideoServers,
)

// ============================== Kodik ==============================
@Serializable
class KodikForm(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(
    val src: String,
)

@Serializable
class KodikVideoQuality(
    @SerialName("480") val bad: List<KodikVideoInfo>,
    @SerialName("720") val good: List<KodikVideoInfo>,
    @SerialName("360") val ugly: List<KodikVideoInfo>,
)

@Serializable
class KodikData(
    val links: KodikVideoQuality,
)
