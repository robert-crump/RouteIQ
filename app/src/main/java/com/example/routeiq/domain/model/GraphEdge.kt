package com.example.routeiq.domain.model

/**
 * An edge in the bundled map graph (`map_edges`), scoped to what [RouteGraphMatcher][com.example.routeiq.domain.matching.RouteGraphMatcher]
 * needs. Traversal-stat fields (speed/power percentiles etc.) live in `map_edges.metadata` as a
 * JSON blob and are left for the scoring modules that actually need them (issue #1's
 * RouteRatingEngine), not modeled here.
 */
data class GraphEdge(
    val fromNode: Long,
    val toNode: Long,
    val lengthM: Double,
    val highway: String?,
    val name: String?,
    val isTraversed: Boolean,
    val geometryEncoded: String?,
)
