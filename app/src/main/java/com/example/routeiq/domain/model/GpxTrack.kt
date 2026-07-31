package com.example.routeiq.domain.model

data class GpxTrack(
    val name: String?,
    val points: List<GeoPoint>,
    /** Elevation in meters per point (same size as [points]), or null if the file has no `<ele>` data. */
    val elevations: List<Double?>? = null,
)
