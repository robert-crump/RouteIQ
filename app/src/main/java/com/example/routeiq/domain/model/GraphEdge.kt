package com.example.routeiq.domain.model

/**
 * An edge in the bundled map graph (`map_edges`), scoped to what [RouteGraphMatcher][com.example.routeiq.domain.matching.RouteGraphMatcher]
 * needs. Most traversal-stat fields (power percentiles, stop stats, etc.) live in
 * `map_edges.metadata` as a JSON blob and are left for the scoring modules that actually need
 * them (issue #1's RouteRatingEngine), not modeled here. [slopePercent] is a real column (not in
 * the metadata blob) - a Copernicus DEM-derived grade, used by [ElevationScore][com.example.routeiq.domain.scoring.ElevationScore]
 * as its no-GPX-elevation fallback. [speedMedianKmh]/[speedMeanKmh] are the two fields
 * [DurationEstimate][com.example.routeiq.domain.scoring.DurationEstimate] needs - parsed out of
 * the same metadata blob's `speed_median`/`speed_mean` keys, since duration estimation is the
 * first consumer that needs speed rather than the whole blob. All default to null so existing
 * test fixtures/constructors that predate these fields don't need updating.
 */
data class GraphEdge(
    val fromNode: Long,
    val toNode: Long,
    val lengthM: Double,
    val highway: String?,
    val name: String?,
    val isTraversed: Boolean,
    val geometryEncoded: String?,
    val slopePercent: Double? = null,
    val speedMedianKmh: Double? = null,
    val speedMeanKmh: Double? = null,
)
