package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationEstimateTest {

    private fun edge(
        lengthM: Double,
        isTraversed: Boolean,
        slopePercent: Double? = null,
        speedMedianKmh: Double? = null,
        speedMeanKmh: Double? = null,
    ) = GraphEdge(
        fromNode = 1, toNode = 2,
        lengthM = lengthM, highway = "residential", name = null,
        isTraversed = isTraversed, geometryEncoded = "",
        slopePercent = slopePercent, speedMedianKmh = speedMedianKmh, speedMeanKmh = speedMeanKmh,
    )

    private fun secondsFor(lengthM: Double, speedKmh: Double) = lengthM / (speedKmh * 1000.0 / 3600.0)

    @Test
    fun `estimate is null for an empty matched-edge list`() {
        assertNull(DurationEstimate.compute(emptyList(), emptyList()))
    }

    @Test
    fun `all-traversed route sums each edge's own median speed`() {
        val edges = listOf(
            edge(lengthM = 1000.0, isTraversed = true, speedMedianKmh = 20.0),
            edge(lengthM = 2000.0, isTraversed = true, speedMedianKmh = 25.0),
        )
        val result = DurationEstimate.compute(edges, historicalTraversedEdges = emptyList())!!

        val expected = secondsFor(1000.0, 20.0) + secondsFor(2000.0, 25.0)
        assertEquals(expected, result.totalDurationS, 1e-6)
        assertEquals(2, result.ownHistoryEdgeCount)
        assertEquals(0, result.slopeBucketEdgeCount)
        assertEquals(0, result.graphAverageEdgeCount)
    }

    @Test
    fun `traversed edge falls back to mean when median is missing`() {
        val edges = listOf(edge(lengthM = 1000.0, isTraversed = true, speedMedianKmh = null, speedMeanKmh = 18.0))
        val result = DurationEstimate.compute(edges, historicalTraversedEdges = emptyList())!!

        assertEquals(secondsFor(1000.0, 18.0), result.totalDurationS, 1e-6)
        assertEquals(1, result.ownHistoryEdgeCount)
    }

    @Test
    fun `all-untraversed route uses the slope bucket built from historical edges`() {
        val historical = listOf(
            // Both land in the [4, 6) bucket (bucketFor floors to 4) - averaged to 15.0 km/h.
            edge(lengthM = 500.0, isTraversed = true, slopePercent = 4.5, speedMedianKmh = 14.0),
            edge(lengthM = 500.0, isTraversed = true, slopePercent = 5.9, speedMedianKmh = 16.0),
        )
        val route = listOf(edge(lengthM = 3000.0, isTraversed = false, slopePercent = 5.0))

        val result = DurationEstimate.compute(route, historical)!!

        assertEquals(secondsFor(3000.0, 15.0), result.totalDurationS, 1e-6)
        assertEquals(0, result.ownHistoryEdgeCount)
        assertEquals(1, result.slopeBucketEdgeCount)
        assertEquals(0, result.graphAverageEdgeCount)
    }

    @Test
    fun `mixed route combines own-history and slope-bucket edges`() {
        val historical = listOf(edge(lengthM = 500.0, isTraversed = true, slopePercent = 0.5, speedMedianKmh = 22.0))
        val route = listOf(
            edge(lengthM = 1000.0, isTraversed = true, speedMedianKmh = 20.0),
            edge(lengthM = 1000.0, isTraversed = false, slopePercent = 0.0),
        )

        val result = DurationEstimate.compute(route, historical)!!

        val expected = secondsFor(1000.0, 20.0) + secondsFor(1000.0, 22.0)
        assertEquals(expected, result.totalDurationS, 1e-6)
        assertEquals(1, result.ownHistoryEdgeCount)
        assertEquals(1, result.slopeBucketEdgeCount)
    }

    @Test
    fun `untraversed edge with no slope data and no ride history uses the default fallback speed`() {
        val route = listOf(edge(lengthM = 1000.0, isTraversed = false, slopePercent = null))

        val result = DurationEstimate.compute(route, historicalTraversedEdges = emptyList())!!

        assertEquals(secondsFor(1000.0, DurationEstimate.DEFAULT_FALLBACK_SPEED_KMH), result.totalDurationS, 1e-6)
        assertEquals(1, result.graphAverageEdgeCount)
    }

    @Test
    fun `untraversed edge whose exact bucket has no historical samples falls to the graph-wide average, not a nearby bucket`() {
        val historical = listOf(
            edge(lengthM = 500.0, isTraversed = true, slopePercent = 0.5, speedMedianKmh = 20.0),
            edge(lengthM = 500.0, isTraversed = true, slopePercent = -0.5, speedMedianKmh = 30.0),
        )
        // 8% has no historical samples at all, even though nearby flatter buckets do.
        val route = listOf(edge(lengthM = 1000.0, isTraversed = false, slopePercent = 8.0))

        val result = DurationEstimate.compute(route, historical)!!

        assertEquals(secondsFor(1000.0, 25.0), result.totalDurationS, 1e-6)
        assertEquals(0, result.slopeBucketEdgeCount)
        assertEquals(1, result.graphAverageEdgeCount)
    }

    @Test
    fun `traversed edge with no speed data at all falls back like an untraversed edge`() {
        val historical = listOf(edge(lengthM = 500.0, isTraversed = true, slopePercent = 1.0, speedMedianKmh = 24.0))
        // is_traversed = true, but no speed sample was ever captured for this edge.
        val route = listOf(edge(lengthM = 1000.0, isTraversed = true, slopePercent = 1.0, speedMedianKmh = null, speedMeanKmh = null))

        val result = DurationEstimate.compute(route, historical)!!

        assertEquals(secondsFor(1000.0, 24.0), result.totalDurationS, 1e-6)
        assertEquals(0, result.ownHistoryEdgeCount)
        assertEquals(1, result.slopeBucketEdgeCount)
    }

    @Test
    fun `zero-length edge contributes zero duration without dividing by zero`() {
        val route = listOf(edge(lengthM = 0.0, isTraversed = true, speedMedianKmh = 20.0))

        val result = DurationEstimate.compute(route, historicalTraversedEdges = emptyList())!!

        assertEquals(0.0, result.totalDurationS, 1e-9)
    }
}
