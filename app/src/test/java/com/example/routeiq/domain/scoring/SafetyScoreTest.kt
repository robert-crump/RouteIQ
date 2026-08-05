package com.example.routeiq.domain.scoring

import com.example.routeiq.domain.model.GraphTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyScoreTest {

    private fun turnOf(junctionNode: Long, hazardScore: Double, hazardSource: String? = "unridden_model") = GraphTurn(
        fromNode = junctionNode - 1,
        junctionNode = junctionNode,
        toNode = junctionNode + 1,
        hazardScore = hazardScore,
        hazardSource = hazardSource,
        stopPenalty = 0.0,
        stopPenaltySource = null,
        brakingProbability = null,
        medianKeDelta = null,
        stopPenaltyConfidence = null,
    )

    @Test
    fun `no junctions are flagged when every turn has a zero hazard score`() {
        val turns = listOf(turnOf(1, 0.0), turnOf(2, 0.0))

        val result = SafetyScore.compute(turns, matchedDistanceM = 10_000.0)

        assertEquals(0, result.totalFlaggedCount)
        assertEquals(0, result.flaggedPer100km)
        assertEquals(0, result.lowCount)
        assertEquals(0, result.mediumCount)
        assertEquals(0, result.highCount)
        assertTrue(result.flaggedJunctions.isEmpty())
    }

    @Test
    fun `result is all-zero for an empty turn list`() {
        val result = SafetyScore.compute(emptyList(), matchedDistanceM = 10_000.0)

        assertEquals(0, result.totalFlaggedCount)
        assertEquals(0, result.flaggedPer100km)
        assertTrue(result.flaggedJunctions.isEmpty())
    }

    @Test
    fun `flagged junctions are tiered, including the exact 0,33 and 0,67 boundaries`() {
        val turns = listOf(
            turnOf(1, 0.1), // LOW
            turnOf(2, 0.33), // LOW - boundary is inclusive on the low side
            turnOf(3, 0.34), // MEDIUM
            turnOf(4, 0.67), // MEDIUM - boundary is inclusive on the low side
            turnOf(5, 0.68), // HIGH
        )

        val result = SafetyScore.compute(turns, matchedDistanceM = 10_000.0)

        assertEquals(5, result.totalFlaggedCount)
        assertEquals(2, result.lowCount)
        assertEquals(2, result.mediumCount)
        assertEquals(1, result.highCount)
        assertEquals(
            listOf(
                SafetyScore.Tier.LOW,
                SafetyScore.Tier.LOW,
                SafetyScore.Tier.MEDIUM,
                SafetyScore.Tier.MEDIUM,
                SafetyScore.Tier.HIGH,
            ),
            result.flaggedJunctions.map { it.tier },
        )
        // 5 flagged over 10km -> 50 per 100km.
        assertEquals(50, result.flaggedPer100km)
    }

    @Test
    fun `flaggedPer100km is 0 when matched distance is zero, not a division-by-zero crash`() {
        val turns = listOf(turnOf(1, 0.5), turnOf(2, 0.9))

        val result = SafetyScore.compute(turns, matchedDistanceM = 0.0)

        assertEquals(2, result.totalFlaggedCount)
        assertEquals(0, result.flaggedPer100km)
    }

    @Test
    fun `hazardSource is carried onto the flagged junction but does not affect tiering`() {
        val turns = listOf(turnOf(1, 0.2, hazardSource = "braking_measured"))

        val result = SafetyScore.compute(turns, matchedDistanceM = 1_000.0)

        assertEquals("braking_measured", result.flaggedJunctions.single().hazardSource)
        assertEquals(SafetyScore.Tier.LOW, result.flaggedJunctions.single().tier)
    }
}
