package eu.kanade.tachiyomi.animeextension.all.torbox

import eu.kanade.tachiyomi.animesource.model.SAnime
import extensions.utils.Source
import extensions.utils.formatBytes
import extensions.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale.getDefault

@Serializable
data class DataDto<T>(
    val data: T,
)

@Serializable
data class ListDataDto(
    val id: Long,
    val name: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updateAt: String,
    @SerialName("cached_at")
    val cachedAt: String? = null,
    val progress: Double,
    @SerialName("download_state")
    val downloadState: String,
    val size: Long,
    val ratio: Double? = null,
    @SerialName("download_speed")
    val downloadSpeed: Long? = null,
    @SerialName("upload_speed")
    val uploadSpeed: Long? = null,
    val eta: Long,
    val files: List<FileDto>? = null,
) {
    fun toInfoDataDto(type: String): InfoDataDto = InfoDataDto(
        id = this.id,
        name = this.name,
        createdAt = this.createdAt,
        updateAt = this.updateAt,
        cachedAt = this.cachedAt,
        progress = this.progress,
        downloadState = this.downloadState,
        size = this.size,
        ratio = this.ratio,
        downloadSpeed = this.downloadSpeed,
        uploadSpeed = this.uploadSpeed,
        eta = this.eta,
        files = this.files.orEmpty(),
        type = type,
    )
}

@Serializable
data class FileDto(
    val id: Long,
    val size: Long,
    @SerialName("short_name")
    val shortName: String,
    val mimetype: String,
)

@Serializable
data class InfoDetailsDto(
    val type: String,
    val id: Long,
)

@Serializable
data class VideoDetailsDto(
    val type: String,
    val seriesId: Long,
    val videoId: Long,
    val openSubsHash: String? = null,
)

data class InfoDataDto(
    val id: Long,
    val name: String,
    val createdAt: String,
    val updateAt: String,
    val cachedAt: String? = null,
    val progress: Double,
    val downloadState: String,
    val size: Long,
    val ratio: Double? = null,
    val downloadSpeed: Long? = null,
    val uploadSpeed: Long? = null,
    val eta: Long,
    val files: List<FileDto>,
    val type: String,
) {
    context(source: Source)
    fun toSAnime(trimName: Boolean): SAnime = SAnime.create().apply {
        this.title = if (trimName) name.trimInfo() else name
        this.description = buildString {
            append("Type: ")
            appendLine(
                type.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString()
                },
            )
            append("Created at: ")
            appendLine(createdAt.replace("T", " ").replace("Z", ""))
            append("Download state: ")
            appendLine(downloadState)
            append("Size: ")
            append(size.formatBytes())
        }
        this.url = InfoDetailsDto(
            type = type,
            id = id,
        ).toJsonString()
    }
}
