package com.example.routeiq.domain.matching

import com.example.routeiq.domain.model.BoundingBox
import com.example.routeiq.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ported from Velometrics' `util/GeoUtils.kt` (+ the bounding-box piece of
 * `domain/service/TrackGeometryUtils.kt`), swapping MapLibre's `LatLng` for [GeoPoint] - same
 * deviation `GpxParser`'s port made (see progress.txt), since Route IQ has no map library
 * dependency. Only the functions [RouteGraphMatcher] actually needs were ported; Velometrics'
 * fat/carb-burn helpers are unrelated to map matching and were left behind.
 */
object GeoUtils {
    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_DEG_LAT = 111_320.0

    /** Haversine distance between two lat/lon coordinates, in meters. */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_M * c
    }

    fun haversineDistance(a: GeoPoint, b: GeoPoint): Double =
        haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Meters per degree of longitude at the given latitude. */
    fun metersPerDegLon(lat: Double): Double = METERS_PER_DEG_LAT * cos(Math.toRadians(lat))

    fun metersToLat(meters: Double): Double = meters / METERS_PER_DEG_LAT

    fun metersToLon(meters: Double, refLat: Double): Double = meters / metersPerDegLon(refLat)

    /** Bearing from point 1 to point 2, in degrees [0, 360). */
    fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearingDeg = Math.toDegrees(atan2(y, x))
        return (bearingDeg + 360) % 360
    }

    /**
     * Perpendicular (point-to-segment) distance from a point to the segment (lat1,lon1)-(lat2,lon2),
     * using a local equirectangular projection (accurate for short segments). Meters.
     */
    fun pointToSegmentDistance(lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mPerLon = metersPerDegLon(lat1)
        val px = (lon - lon1) * mPerLon
        val py = (lat - lat1) * METERS_PER_DEG_LAT
        val dx = (lon2 - lon1) * mPerLon
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
        val ddx = px - t * dx
        val ddy = py - t * dy
        return sqrt(ddx * ddx + ddy * ddy)
    }

    /** Smallest angular difference between two bearings, in [0, 180]. */
    fun bearingDifference(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360
        return if (diff > 180) 360 - diff else diff
    }

    /** Axis-aligned bounding box around [points], expanded by [bufferM] meters on every side. */
    fun computeBoundingBox(points: List<GeoPoint>, bufferM: Double): BoundingBox {
        require(points.isNotEmpty()) { "Points list must not be empty" }

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }

        val latBuffer = metersToLat(bufferM)
        val midLat = (minLat + maxLat) / 2.0
        val lonBuffer = metersToLon(bufferM, midLat)

        return BoundingBox(
            minLat = minLat - latBuffer,
            minLon = minLon - lonBuffer,
            maxLat = maxLat + latBuffer,
            maxLon = maxLon + lonBuffer,
        )
    }
}
