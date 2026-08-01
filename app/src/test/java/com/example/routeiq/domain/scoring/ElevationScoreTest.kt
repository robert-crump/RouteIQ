package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Covers issue #7's acceptance criteria (flat / steadily-climbing / mixed-gradient elevation
 * series, plus edges with missing slope metadata) and the `/grill-me`-resolved climb-detection
 * design from issue #7's comment thread (dip tolerance, length/gain floors, TdF categorization).
 *
 * Elevation series are built with flat padding at both ends around the segment under test -
 * [ElevationScore]'s centered moving average has a truncated (asymmetric) window at the very start
 * and end of the array, which would otherwise bias gain/loss for climbs placed at the array edges;
 * padding pushes that truncation into a flat region where it has no effect (see issue #6's
 * progress.txt note on a similar smoothing/rounding fragility trap).
 */
class ElevationScoreTest {

    /** Builds a (points, elevations) pair: 100m-spaced points along a meridian, elevation walking [deltas] from [startElevation]. */
    private fun buildTrack(deltas: List<Double>, startElevation: Double = 400.0): Pair<List<GeoPoint>, List<Double?>> {
        val originLat = 50.0
        val elevations = mutableListOf(startElevation)
        deltas.forEach { d -> elevations.add(elevations.last() + d) }
        val points = elevations.indices.map { i -> GeoPoint(originLat + GeoUtils.metersToLat(i * 100.0), 6.0) }
        return points to elevations
    }

    private val flatPad = List(5) { 0.0 }

    @Test
    fun `returns null for fewer than 2 elevation points`() {
        val points = listOf(GeoPoint(50.0, 6.0), GeoPoint(50.001, 6.0))
        assertNull(ElevationScore.computeFromGpx(points, listOf(400.0, null)))
    }

    @Test
    fun `returns null when all elevations are null`() {
        val (points, _) = buildTrack(List(10) { 0.0 })
        assertNull(ElevationScore.computeFromGpx(points, List(points.size) { null }))
    }

    @Test
    fun `flat series has zero gain and loss and no climbs`() {
        val (points, elevations) = buildTrack(List(20) { 0.0 })
        val result = ElevationScore.computeFromGpx(points, elevations)

        assertNotNull(result)
        result!!
        assertEquals(0, result.gainM)
        assertEquals(0, result.lossM)
        assertEquals(0, result.gainPer100km)
        assertTrue(result.climbs.isEmpty())
    }

    @Test
    fun `steadily climbing series accumulates gain and detects one categorized climb`() {
        // Padding + 20 steps of +10m/100m (2000m at 10%, 200m total gain) + padding.
        val (points, elevations) = buildTrack(flatPad + List(20) { 10.0 } + flatPad)
        val result = ElevationScore.computeFromGpx(points, elevations)

        assertNotNull(result)
        result!!
        // Monotonic non-decreasing overall (flat -> climb -> flat), so smoothing can't move the
        // endpoints - gain telescopes exactly to (end elevation - start elevation).
        assertEquals(200, result.gainM)
        assertEquals(0, result.lossM)
        assertEquals(1, result.climbs.size)
        val climb = result.climbs.first()
        assertEquals(200.0, climb.gainM, 0.01)
        // The smoothing window blends a few points around the flat->climb and climb->flat kinks
        // into the detected climb, so its measured length (and thus avg grade) is a bit looser
        // than the raw 2000m/10% - the window radius is 2, so at most ~2 points (200m) of slack
        // on each side is expected, not exact-boundary precision.
        assertTrue("expected avg grade roughly around 10%, was ${climb.avgGradePercent}", abs(climb.avgGradePercent - 10.0) <= 3.0)
        // difficulty = length_km * avg_grade^2, comfortably within Cat 2's 150-300 range even
        // with the length/grade slack above.
        assertEquals(ElevationScore.ClimbCategory.CAT_2, climb.category)
    }

    @Test
    fun `mixed gradient series accumulates both gain and loss but only counts the ascent as a climb`() {
        // Padding + climb 1500m@10% (+150m) + descent 1500m@10% (-150m) + padding.
        val deltas = flatPad + List(15) { 10.0 } + List(15) { -10.0 } + flatPad
        val (points, elevations) = buildTrack(deltas)
        val result = ElevationScore.computeFromGpx(points, elevations)

        assertNotNull(result)
        result!!
        assertTrue("expected gain near 150m, was ${result.gainM}", abs(result.gainM - 150) <= 20)
        assertTrue("expected loss near 150m, was ${result.lossM}", abs(result.lossM - 150) <= 20)
        assertEquals(1, result.climbs.size)
    }

    @Test
    fun `a short dip within a climb does not split it, but a long dip does, and too-short climbs are filtered`() {
        val deltas = flatPad +
            List(15) { 10.0 } + // climb: 1500m, +150m
            List(3) { -5.0 } + // short dip: 300m (< 500m tolerance) - stays in the same climb
            List(5) { 10.0 } + // resumes climbing: +50m more (net climb-so-far: +185m)
            List(6) { -20.0 } + // long dip: 600m (> 500m tolerance) - ends the climb here
            List(5) { 0.0 } + // flat
            List(5) { 5.0 } + // too short to count: 500m length, +25m gain (< both floors)
            List(5) { 0.0 } + // flat
            List(12) { 8.0 } + // second real climb: 1200m, +96m
            flatPad
        val (points, elevations) = buildTrack(deltas)
        val result = ElevationScore.computeFromGpx(points, elevations)

        assertNotNull(result)
        result!!
        assertEquals(2, result.climbs.size)
        assertTrue("expected first climb gain well above the 30m floor, was ${result.climbs[0].gainM}", result.climbs[0].gainM > 100.0)
        assertTrue("expected second climb gain well above the 30m floor, was ${result.climbs[1].gainM}", result.climbs[1].gainM > 50.0)
    }

    @Test
    fun `computeFromMatchedEdges builds a relative profile from slope percent, treating missing slope as flat`() {
        val edges = listOf(
            edgeWithSlope(lengthM = 100.0, slopePercent = null), // padding: flat because slope is missing, not because it's 0
            edgeWithSlope(lengthM = 100.0, slopePercent = null),
        ) + List(20) { edgeWithSlope(lengthM = 100.0, slopePercent = 10.0) } + // climb: 2000m at 10% = +200m
            listOf(
                edgeWithSlope(lengthM = 100.0, slopePercent = null),
                edgeWithSlope(lengthM = 100.0, slopePercent = null),
            )
        val result = ElevationScore.computeFromMatchedEdges(edges)

        assertNotNull(result)
        result!!
        assertEquals(ElevationScore.Source.DEM_FALLBACK, result.source)
        assertEquals(2_400.0, result.totalDistanceM, 0.01)
        assertEquals(200, result.gainM)
        assertEquals(0, result.lossM)
    }

    @Test
    fun `computeFromMatchedEdges returns null for empty edges`() {
        assertNull(ElevationScore.computeFromMatchedEdges(emptyList()))
    }

    private fun edgeWithSlope(lengthM: Double, slopePercent: Double?) = GraphEdge(
        fromNode = 0, toNode = 1, lengthM = lengthM, highway = "residential", name = null,
        isTraversed = true, geometryEncoded = "", slopePercent = slopePercent,
    )
}

/** [ElevationScore.ClimbCategory.forDifficulty] boundary coverage - TdF thresholds from issue #7's comment. */
class ElevationScoreClimbCategoryTest {

    @Test
    fun `category boundaries match the TdF thresholds`() {
        assertEquals(ElevationScore.ClimbCategory.CAT_4, ElevationScore.ClimbCategory.forDifficulty(0.0))
        assertEquals(ElevationScore.ClimbCategory.CAT_4, ElevationScore.ClimbCategory.forDifficulty(74.99))
        assertEquals(ElevationScore.ClimbCategory.CAT_3, ElevationScore.ClimbCategory.forDifficulty(75.0))
        assertEquals(ElevationScore.ClimbCategory.CAT_3, ElevationScore.ClimbCategory.forDifficulty(149.99))
        assertEquals(ElevationScore.ClimbCategory.CAT_2, ElevationScore.ClimbCategory.forDifficulty(150.0))
        assertEquals(ElevationScore.ClimbCategory.CAT_2, ElevationScore.ClimbCategory.forDifficulty(299.99))
        assertEquals(ElevationScore.ClimbCategory.CAT_1, ElevationScore.ClimbCategory.forDifficulty(300.0))
        assertEquals(ElevationScore.ClimbCategory.CAT_1, ElevationScore.ClimbCategory.forDifficulty(599.99))
        assertEquals(ElevationScore.ClimbCategory.HC, ElevationScore.ClimbCategory.forDifficulty(600.0))
    }

    @Test
    fun `Col du Tourmalet's worked example (18-3km at 7-7 percent) categorizes as HC`() {
        val lengthKm = 18.3
        val avgGrade = 7.7
        val difficulty = lengthKm * avgGrade * avgGrade
        assertEquals(ElevationScore.ClimbCategory.HC, ElevationScore.ClimbCategory.forDifficulty(difficulty))
    }
}
