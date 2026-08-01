package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryScoreTest {

    private fun edgeOf(lengthM: Double, isTraversed: Boolean) = GraphEdge(
        fromNode = 1, toNode = 2,
        lengthM = lengthM, highway = "residential", name = null,
        isTraversed = isTraversed, geometryEncoded = "",
    )

    @Test
    fun `score is null for an empty edge list`() {
        assertNull(DiscoveryScore.compute(emptyList()))
    }

    @Test
    fun `score is 0 when all matched edges are traversed`() {
        val edges = listOf(edgeOf(1000.0, isTraversed = true), edgeOf(1000.0, isTraversed = true))
        assertEquals(0, DiscoveryScore.compute(edges))
    }

    @Test
    fun `score is 100 when no matched edges are traversed`() {
        val edges = listOf(edgeOf(1000.0, isTraversed = false), edgeOf(1000.0, isTraversed = false))
        assertEquals(100, DiscoveryScore.compute(edges))
    }

    @Test
    fun `score is the length-weighted percentage of untraversed edges`() {
        val edges = listOf(
            edgeOf(750.0, isTraversed = false),
            edgeOf(250.0, isTraversed = true),
        )
        assertEquals(75, DiscoveryScore.compute(edges))
    }

    @Test
    fun `score is null when total matched length is zero`() {
        val edges = listOf(edgeOf(0.0, isTraversed = false), edgeOf(0.0, isTraversed = true))
        assertNull(DiscoveryScore.compute(edges))
    }
}

class DiscoveryBucketTest {

    @Test
    fun `buckets follow the four-tier split`() {
        assertEquals(DiscoveryBucket.FAMILIAR, DiscoveryBucket.forScore(0))
        assertEquals(DiscoveryBucket.FAMILIAR, DiscoveryBucket.forScore(24))
        assertEquals(DiscoveryBucket.SOME_NEW, DiscoveryBucket.forScore(25))
        assertEquals(DiscoveryBucket.SOME_NEW, DiscoveryBucket.forScore(49))
        assertEquals(DiscoveryBucket.MOSTLY_NEW, DiscoveryBucket.forScore(50))
        assertEquals(DiscoveryBucket.MOSTLY_NEW, DiscoveryBucket.forScore(74))
        assertEquals(DiscoveryBucket.ALL_NEW, DiscoveryBucket.forScore(75))
        assertEquals(DiscoveryBucket.ALL_NEW, DiscoveryBucket.forScore(100))
    }
}
