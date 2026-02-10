package eu.kanade.tachiyomi.lib.megamaxmultiserver.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeechResponse(
    val props: LeechProps,
)

@Serializable
data class LeechProps(
    val streams: LeechStreams
)

@Serializable
data class LeechStreams(
    val data: List<LeechData>,
    val msg: String,
    val status: String,
)

@Serializable
data class LeechData(
    val file: String,
    val label: String,
    val size: Long,
    val type: String
)
