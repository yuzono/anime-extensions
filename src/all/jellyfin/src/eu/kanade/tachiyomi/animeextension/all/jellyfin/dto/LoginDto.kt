package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
class LoginDto(
    val accessToken: String,
    val sessionInfo: LoginSessionDto,
) {
    @Serializable
    class LoginSessionDto(
        val userId: String,
    )
}

@Serializable
class LoginRequestDto(
    val username: String,
    val pw: String,
)
