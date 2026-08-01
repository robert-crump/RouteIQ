package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelingScoreTest {

    /** A point [distanceM] meters along an eastward line from the origin at the equator. */
    private fun pointAt(distanceM: Double): GeoPoint = GeoPoint(0.0, GeoUtils.metersToLon(distanceM, 0.0))

    /** A straight track whose vertices sit at exactly [distancesM], in order. */
    private fun trackVertices(vararg distancesM: Double): List<GeoPoint> = distancesM.map(::pointAt)

    /** A resupply POI sitting [perpendicularM] off the track, directly opposite the vertex at [distanceM]. */
    private fun poiAt(distanceM: Double, perpendicularM: Double, id: String, category: String = "cafe") = Poi(
        poiId = id,
        name = null,
        category = category,
        cuisine = null,
        location = GeoPoint(GeoUtils.metersToLat(perpendicularM), GeoUtils.metersToLon(distanceM, 0.0)),
        openingHours = null,
    )

    private fun denseCluster(distanceM: Double) = (1..6).map { i -> poiAt(distanceM, perpendicularM = 50.0, id = "d$distanceM-$i") }

    @Test
    fun `score is null for fewer than 2 points`() {
        assertNull(FuelingScore.compute(listOf(pointAt(0.0)), emptyList()))
    }

    @Test
    fun `score is null when total distance is zero`() {
        assertNull(FuelingScore.compute(listOf(pointAt(0.0), pointAt(0.0)), emptyList()))
    }

    @Test
    fun `POI-dense route scores 100 and every bucket reads dense`() {
        val points = trackVertices(0.0, 5000.0, 10000.0, 15000.0)
        val pois = listOf(2500.0, 7500.0, 12500.0).flatMap(::denseCluster)

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(100, result.score)
        assertEquals(List(3) { FuelingScore.BucketSeverity.DENSE }, result.onRoute.severities)
        assertEquals(FuelingBucket.WELL_FUELED, FuelingBucket.forScore(result.score))
    }

    @Test
    fun `POI-sparse route scores 0 and every bucket reads sparse`() {
        val points = trackVertices(0.0, 5000.0, 10000.0, 15000.0)

        val result = FuelingScore.compute(points, emptyList())!!

        assertEquals(0, result.score)
        assertEquals(List(3) { FuelingScore.BucketSeverity.SPARSE }, result.onRoute.severities)
        assertEquals(FuelingBucket.POOR, FuelingBucket.forScore(result.score))
    }

    @Test
    fun `non-resupply categories don't count toward density`() {
        val points = trackVertices(0.0, 5000.0)
        val pois = listOf(poiAt(0.0, perpendicularM = 20.0, id = "bike", category = "bicycle"))

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(0, result.score)
        assertEquals(FuelingScore.BucketSeverity.SPARSE, result.onRoute.severities[0])
    }

    @Test
    fun `poi within 100m counts toward both on-route and detour tiers`() {
        val points = trackVertices(0.0, 5000.0)
        val pois = listOf(poiAt(0.0, perpendicularM = 50.0, id = "near"))

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(1, result.onRoute.counts[0])
        assertEquals(1, result.withDetour.counts[0])
    }

    @Test
    fun `poi between 100m and 500m counts only toward the detour tier`() {
        val points = trackVertices(0.0, 5000.0)
        val pois = listOf(poiAt(0.0, perpendicularM = 300.0, id = "mid"))

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(0, result.onRoute.counts[0])
        assertEquals(1, result.withDetour.counts[0])
        assertEquals(FuelingScore.BucketSeverity.SPARSE, result.onRoute.severities[0])
        assertEquals(FuelingScore.BucketSeverity.BORDERLINE, result.withDetour.severities[0])
    }

    @Test
    fun `poi beyond 500m is excluded from both tiers`() {
        val points = trackVertices(0.0, 5000.0)
        val pois = listOf(poiAt(0.0, perpendicularM = 600.0, id = "far"))

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(0, result.onRoute.counts[0])
        assertEquals(0, result.withDetour.counts[0])
    }

    @Test
    fun `three or more consecutive sparse buckets are flagged as an extended gap`() {
        val points = trackVertices(0.0, 5000.0, 10000.0, 15000.0, 20000.0, 25000.0)
        val pois = listOf(2500.0, 22500.0).flatMap(::denseCluster)

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(1, result.onRoute.extendedGaps.size)
        val gap = result.onRoute.extendedGaps.first()
        assertEquals(5000.0, gap.startM, 0.01)
        assertEquals(20000.0, gap.endM, 0.01)
    }

    @Test
    fun `two consecutive sparse buckets are not an extended gap`() {
        val points = trackVertices(0.0, 5000.0, 10000.0, 15000.0, 20000.0)
        val pois = listOf(2500.0, 17500.0).flatMap(::denseCluster)

        val result = FuelingScore.compute(points, pois)!!

        assertTrue(result.onRoute.extendedGaps.isEmpty())
        assertEquals(1, result.onRoute.sparseRanges.size)
    }

    @Test
    fun `score weights the final partial bucket by its actual length`() {
        // 2 full 5km buckets (dense) + a final partial ~2.5km bucket (sparse) - a ~20% risk share,
        // chosen with a wide rounding margin so the haversine-vs-equirectangular gap between the
        // track's actual length and its nominal 12500m doesn't flip the rounded score.
        val points = trackVertices(0.0, 5000.0, 10000.0, 12500.0)
        val pois = listOf(2500.0, 7500.0).flatMap(::denseCluster)

        val result = FuelingScore.compute(points, pois)!!

        assertEquals(
            listOf(FuelingScore.BucketSeverity.DENSE, FuelingScore.BucketSeverity.DENSE, FuelingScore.BucketSeverity.SPARSE),
            result.onRoute.severities,
        )
        assertEquals(80, result.score)
    }
}

class FuelingBucketTest {

    @Test
    fun `buckets follow the four-tier split`() {
        assertEquals(FuelingBucket.POOR, FuelingBucket.forScore(0))
        assertEquals(FuelingBucket.POOR, FuelingBucket.forScore(24))
        assertEquals(FuelingBucket.RISKY, FuelingBucket.forScore(25))
        assertEquals(FuelingBucket.RISKY, FuelingBucket.forScore(49))
        assertEquals(FuelingBucket.ADEQUATE, FuelingBucket.forScore(50))
        assertEquals(FuelingBucket.ADEQUATE, FuelingBucket.forScore(74))
        assertEquals(FuelingBucket.WELL_FUELED, FuelingBucket.forScore(75))
        assertEquals(FuelingBucket.WELL_FUELED, FuelingBucket.forScore(100))
    }
}
