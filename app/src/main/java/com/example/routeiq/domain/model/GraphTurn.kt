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
    /** Deceleration + re-acceleration elapsed-time loss in seconds (Ride-Graph#98) - additive with
     * [stopPenalty], not overlapping with it (that's wait-time-only at a full stop). Defaults to
     * 0.0 so existing call sites built before this field existed don't need updating. */
    val brakingPenaltyS: Double = 0.0,
    val brakingPenaltySource: String? = null,
    val brakingPenaltyConfidence: Double? = null,
)
