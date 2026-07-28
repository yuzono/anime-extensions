package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMappingsResponse(
    val data: List<KitsuMapping>,
)

@Serializable
data class KitsuMapping(
    val id: String,
    val type: String,
    val links: KitsuLinks,
    val attributes: KitsuMappingAttributes,
    val relationships: KitsuMappingRelationships,
)

@Serializable
data class KitsuLinks(
    val self: String,
)

@Serializable
data class KitsuMappingAttributes(
    val createdAt: String,
    val updatedAt: String,
    val externalSite: String,
    val externalId: String,
)

@Serializable
data class KitsuMappingRelationships(
    val item: KitsuRelationshipItem,
)

@Serializable
data class KitsuRelationshipItem(
    val links: KitsuRelationshipLinks,
    val data: KitsuRelationshipData,
)

@Serializable
data class KitsuRelationshipLinks(
    val self: String,
    val related: String,
)

@Serializable
data class KitsuRelationshipData(
    val type: String,
    val id: String,
)
