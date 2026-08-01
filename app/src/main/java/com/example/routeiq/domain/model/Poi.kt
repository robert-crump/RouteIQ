package com.example.routeiq.domain.model

/** A point of interest from the bundled map graph's `pois` table (e.g. a cafe, bakery, or water point). */
data class Poi(
    val poiId: String,
    val name: String?,
    val category: String,
    val cuisine: String?,
    val location: GeoPoint,
    val openingHours: String?,
)
