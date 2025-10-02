package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackInfoDto(
    val userId: String,
    val isPlayback: Boolean,
    val mediaSourceId: String,
    val maxStreamingBitrate: Int,
    val enableTranscoding: Boolean,
    val audioStreamIndex: String? = null,
    val subtitleStreamIndex: String? = null,
    val alwaysBurnInSubtitleWhenTranscoding: Boolean,
    val deviceProfile: DeviceProfileDto,
)

@Serializable
data class DeviceProfileDto(
    val name: String,
    val maxStreamingBitrate: Int,
    val maxStaticBitrate: Int,
    val musicStreamingTranscodingBitrate: Int,
    val transcodingProfiles: List<ProfileDto>,
    val directPlayProfiles: List<ProfileDto>,
    val responseProfiles: List<ProfileDto>,
    val containerProfiles: List<ProfileDto>,
    val codecProfiles: List<ProfileDto>,
    val subtitleProfiles: List<SubtitleProfileDto>,
) {
    @Serializable
    data class ProfileDto(
        val type: String,
        val container: String? = null,
        val protocol: String? = null,
        val audioCodec: String? = null,
        val videoCodec: String? = null,
        val codec: String? = null,
        val maxAudioChannels: String? = null,
        val conditions: List<ProfileConditionDto>? = null,
    ) {
        @Serializable
        data class ProfileConditionDto(
            val condition: String,
            val property: String,
            val value: String,
        )
    }

    @Serializable
    data class SubtitleProfileDto(
        val format: String,
        val method: String,
    )
}
