package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationScoreTest {

    private fun turnOf(
        junctionNode: Long,
        stopPenalty: Double = 0.0,
        stopPenaltySource: String? = null,
        brakingPenaltyS: Double = 0.0,
        brakingPenaltySource: String? = null,
        brakingPenaltyConfidence: Double? = null,
    ) = GraphTurn(
        fromNode = junctionNode - 1,
        junctionNode = junctionNode,
        toNode = junctionNode + 1,
        hazardScore = 0.0,
        hazardSource = null,
        stopPenalty = stopPenalty,
        stopPenaltySource = stopPenaltySource,
        brakingProbability = null,
        medianKeDelta = null,
        stopPenaltyConfidence = null,
        brakingPenaltyS = brakingPenaltyS,
        brakingPenaltySource = brakingPenaltySource,
        brakingPenaltyConfidence = brakingPenaltyConfidence,
    )

    @Test
    fun `a route with no junctions scores 100 with nothing flagged`() {
        val result = OptimizationScore.compute(emptyList(), matchedDistanceM = 10_000.0)

        assertEquals(100, result.score)
        assertEquals(0.0, result.penaltySPer100km, 0.0)
        assertTrue(result.flaggedJunctions.isEmpty())
    }

    @Test
    fun `low-penalty junctions below the flag threshold are not flagged but still cost the score`() {
        // 2 junctions at 2s each over 10km -> 40s/100km, well under a 5s flag threshold.
        val turns = listOf(
            turnOf(1, stopPenalty = 1.0, brakingPenaltyS = 1.0, brakingPenaltySource = "osm_priority"),
            turnOf(2, stopPenalty = 1.0, brakingPenaltyS = 1.0, brakingPenaltySource = "osm_priority"),
        )

        val result = OptimizationScore.compute(turns, matchedDistanceM = 10_000.0)

        assertTrue(result.flaggedJunctions.isEmpty())
        assertEquals(40.0, result.penaltySPer100km, 0.001)
        assertTrue("expected a high score for a low-penalty route, was ${result.score}", result.score > 80)
    }

    @Test
    fun `high-penalty junctions above the flag threshold are flagged and lower the score`() {
        val turns = listOf(
            turnOf(1, stopPenalty = 5.0, brakingPenaltyS = 5.11, brakingPenaltySource = "osm_priority"), // 10.11s, flagged
            turnOf(2, stopPenalty = 0.0, brakingPenaltyS = 1.0, brakingPenaltySource = "osm_priority"), // 1s, not flagged
        )

        val result = OptimizationScore.compute(turns, matchedDistanceM = 10_000.0)

        assertEquals(1, result.flaggedJunctions.size)
        assertEquals(1L, result.flaggedJunctions.single().junctionNode)
        assertEquals(10.11, result.flaggedJunctions.single().penaltyS, 0.001)
        assertTrue(result.score < 100)
    }

    @Test
    fun `a junction's negative braking penalty is floored at 0, not treated as a credit`() {
        // Calibration noise can make an individual junction's braking_penalty_s slightly negative
        // (e.g. Ride-Graph#98's own osm_priority/"stop" average of -0.48s) - it must not offset
        // other junctions' real cost.
        val turns = listOf(
            turnOf(1, stopPenalty = 0.0, brakingPenaltyS = -10.0, brakingPenaltySource = "osm_priority"),
            turnOf(2, stopPenalty = 5.0, brakingPenaltyS = 5.11, brakingPenaltySource = "osm_priority"),
        )

        val result = OptimizationScore.compute(turns, matchedDistanceM = 10_000.0)

        // If the negative penalty were allowed to offset the total, this would be (10.11 - 10) = 0.11s/10km.
        // Floored per-junction, it stays 10.11s/10km (= 101.1s/100km) instead.
        assertEquals(101.1, result.penaltySPer100km, 0.001)
    }

    @Test
    fun `missing braking_penalty_confidence does not affect scoring and is carried through as null`() {
        val turns = listOf(
            turnOf(1, stopPenalty = 5.0, brakingPenaltyS = 5.11, brakingPenaltySource = "osm_priority", brakingPenaltyConfidence = null),
        )

        val result = OptimizationScore.compute(turns, matchedDistanceM = 1_000.0)

        assertEquals(1, result.flaggedJunctions.size)
        assertEquals(null, result.flaggedJunctions.single().brakingPenaltyConfidence)
    }

    @Test
    fun `penaltySPer100km is 0 when matched distance is zero, not a division-by-zero crash`() {
        val turns = listOf(turnOf(1, stopPenalty = 10.0, brakingPenaltyS = 10.0))

        val result = OptimizationScore.compute(turns, matchedDistanceM = 0.0)

        assertEquals(0.0, result.penaltySPer100km, 0.0)
        assertEquals(100, result.score)
    }

    @Test
    fun `score is clamped at 0 for very high penalty-per-100km routes`() {
        val turns = listOf(turnOf(1, stopPenalty = 100.0, brakingPenaltyS = 100.0))

        val result = OptimizationScore.compute(turns, matchedDistanceM = 1_000.0)

        assertEquals(0, result.score)
    }
}

class OptimizationBucketTest {

    @Test
    fun `all four boundaries resolve to the expected bucket`() {
        assertEquals(OptimizationBucket.HEAVY_STOP_AND_GO, OptimizationBucket.forScore(0))
        assertEquals(OptimizationBucket.HEAVY_STOP_AND_GO, OptimizationBucket.forScore(24))
        assertEquals(OptimizationBucket.FREQUENT_STOPS, OptimizationBucket.forScore(25))
        assertEquals(OptimizationBucket.FREQUENT_STOPS, OptimizationBucket.forScore(49))
        assertEquals(OptimizationBucket.MINOR_STOPS, OptimizationBucket.forScore(50))
        assertEquals(OptimizationBucket.MINOR_STOPS, OptimizationBucket.forScore(74))
        assertEquals(OptimizationBucket.EFFICIENT, OptimizationBucket.forScore(75))
        assertEquals(OptimizationBucket.EFFICIENT, OptimizationBucket.forScore(100))
    }
}
