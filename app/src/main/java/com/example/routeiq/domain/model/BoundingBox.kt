package com.example.routeiq.domain.model

/** A lat/lon bounding box. Used both for a track's local query window and the graph's overall coverage. */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    fun overlaps(other: BoundingBox): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat && minLon <= other.maxLon && maxLon >= other.minLon
}
