package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers issue #11's resolved design for the elevation map overlay: [elevationGradeSegments]'
 * distance-to-point grade resampling, on flat / steep / mixed grade series, plus [GradeBucket]'s
 * own boundary thresholds.
 */
class ElevationGradeSegmentsTest {

    /** [count] points 100m apart along a meridian, so cumulative haversine distance lands on exact 100m multiples. */
    private fun pointsAlongMeridian(count: Int): List<GeoPoint> {
        val originLat = 50.0
        return (0 until count).map { i -> GeoPoint(originLat + GeoUtils.metersToLat(i * 100.0), 6.0) }
    }

    @Test
    fun `fewer than 2 route points returns empty`() {
        val profile = listOf(0.0 to 0.0, 100.0 to 1.0)
        assertEquals(emptyList<GradeSegment>(), elevationGradeSegments(listOf(GeoPoint(50.0, 6.0)), profile))
    }

    @Test
    fun `empty profile returns empty`() {
        assertEquals(emptyList<GradeSegment>(), elevationGradeSegments(pointsAlongMeridian(5), emptyList()))
    }

    @Test
    fun `flat grade series is a single FLAT segment covering every point`() {
        val points = pointsAlongMeridian(5)
        val profile = listOf(0.0 to 0.0, 100.0 to 1.0, 200.0 to 2.0, 300.0 to 3.0, 400.0 to 4.0)

        val segments = elevationGradeSegments(points, profile)

        assertEquals(1, segments.size)
        assertEquals(GradeBucket.FLAT, segments[0].bucket)
        assertEquals(points, segments[0].points)
    }

    @Test
    fun `steep grade series is a single VERY_STEEP segment covering every point`() {
        val points = pointsAlongMeridian(5)
        val profile = listOf(0.0 to 0.0, 100.0 to 10.0, 200.0 to 20.0, 300.0 to 30.0, 400.0 to 40.0)

        val segments = elevationGradeSegments(points, profile)

        assertEquals(1, segments.size)
        assertEquals(GradeBucket.VERY_STEEP, segments[0].bucket)
        assertEquals(points, segments[0].points)
    }

    @Test
    fun `mixed grade series splits into contiguous segments sharing a boundary point`() {
        val points = pointsAlongMeridian(6)
        // Steps: 0-100 1%, 100-200 1%, 200-300 10%, 300-400 10%, 400-500 10%.
        val profile = listOf(
            0.0 to 0.0,
            100.0 to 1.0,
            200.0 to 2.0,
            300.0 to 12.0,
            400.0 to 22.0,
            500.0 to 32.0,
        )

        val segments = elevationGradeSegments(points, profile)

        assertEquals(2, segments.size)
        assertEquals(GradeBucket.FLAT, segments[0].bucket)
        assertEquals(points.subList(0, 3), segments[0].points)
        assertEquals(GradeBucket.VERY_STEEP, segments[1].bucket)
        assertEquals(points.subList(2, 6), segments[1].points)
        // The bucket boundary point is shared between segments, so the drawn polyline stays continuous.
        assertEquals(segments[0].points.last(), segments[1].points.first())
    }

    @Test
    fun `descending grade buckets by magnitude, not sign`() {
        val points = pointsAlongMeridian(3)
        val profile = listOf(0.0 to 40.0, 100.0 to 30.0, 200.0 to 20.0)

        val segments = elevationGradeSegments(points, profile)

        assertEquals(1, segments.size)
        assertEquals(GradeBucket.VERY_STEEP, segments[0].bucket)
    }

    @Test
    fun `GradeBucket forGradePercent boundaries`() {
        assertEquals(GradeBucket.FLAT, GradeBucket.forGradePercent(0.0))
        assertEquals(GradeBucket.FLAT, GradeBucket.forGradePercent(2.999))
        assertEquals(GradeBucket.MODERATE, GradeBucket.forGradePercent(3.0))
        assertEquals(GradeBucket.MODERATE, GradeBucket.forGradePercent(5.999))
        assertEquals(GradeBucket.STEEP, GradeBucket.forGradePercent(6.0))
        assertEquals(GradeBucket.STEEP, GradeBucket.forGradePercent(8.999))
        assertEquals(GradeBucket.VERY_STEEP, GradeBucket.forGradePercent(9.0))
        assertTrue(GradeBucket.forGradePercent(50.0) == GradeBucket.VERY_STEEP)
    }
}
