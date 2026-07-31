package com.example.routeiq.domain.model

/** A junction turn in the bundled map graph (`map_turns`), keyed by the ordered node triple. */
data class GraphTurn(
    val fromNode: Long,
    val junctionNode: Long,
    val toNode: Long,
    val hazardScore: Double,
    val hazardSource: String?,
    val stopPenalty: Double,
    val stopPenaltySource: String?,
    val brakingProbability: Double?,
    val medianKeDelta: Double?,
    val stopPenaltyConfidence: Double?,
)
