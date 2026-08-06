package com.example.routeiq.ui.gpx

import com.example.routeiq.domain.matching.MatchResult
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.scoring.DurationEstimate
import com.example.routeiq.domain.scoring.FuelingScore

/**
 * UI state shapes shared between the Import and Results screens (issue #11 split the two apart
 * via Jetpack Navigation Compose) - `internal` rather than `private` now that they're read from
 * more than one file in this package.
 */
internal sealed interface GpxImportUiState {
    data object Idle : GpxImportUiState
    data object Loading : GpxImportUiState
    data class Loaded(val track: GpxTrack) : GpxImportUiState
    data class Error(val message: String) : GpxImportUiState
}

/** Matching runs automatically once a track loads (issue #4) - a separate state so import errors and match errors don't collide. */
internal sealed interface MatchUiState {
    data object Matching : MatchUiState
    data class Matched(val result: MatchResult.Matched) : MatchUiState
    data class OutsideCoverage(val reason: String) : MatchUiState
    data class Error(val message: String) : MatchUiState
}

/** Fueling score only runs once matching succeeds (issue #6) - fetching POIs and scoring is async, so it's its own state. */
internal sealed interface FuelingUiState {
    data object Loading : FuelingUiState
    data class Ready(val result: FuelingScore.Result?) : FuelingUiState
    data class Error(val message: String) : FuelingUiState
}

/**
 * Duration estimate only runs once matching succeeds (issue #10) - it needs the rider's graph-wide
 * ride history ([com.example.routeiq.data.graph.GraphAssetRepository.getTraversedEdges]) for its
 * slope-bucket fallback, which is its own async fetch just like fueling's POI lookup.
 */
internal sealed interface DurationUiState {
    data object Loading : DurationUiState
    data class Ready(val result: DurationEstimate.Result?) : DurationUiState
    data class Error(val message: String) : DurationUiState
}

/**
 * The safety score's counts (issue #9) come straight from [MatchResult.Matched.matchedTurns], so
 * they're computed synchronously alongside [MatchUiState.Matched] rather than needing their own
 * state - only resolving each flagged junction's coordinates for the map needs an async
 * [com.example.routeiq.data.graph.GraphAssetRepository.getNodesNear] fetch, which is what this state tracks.
 */
internal sealed interface SafetyMarkersUiState {
    data object Loading : SafetyMarkersUiState
    data class Ready(val markers: List<com.example.routeiq.domain.scoring.SafetyMarker>) : SafetyMarkersUiState
    data class Error(val message: String) : SafetyMarkersUiState
}

/**
 * The optimization score's own figures (issue #8) come straight from
 * [MatchResult.Matched.matchedTurns], computed synchronously like [SafetyScore][com.example.routeiq.domain.scoring.SafetyScore] -
 * only resolving each flagged junction's coordinates for the map needs an async
 * [com.example.routeiq.data.graph.GraphAssetRepository.getNodesNear] fetch, mirroring [SafetyMarkersUiState].
 */
internal sealed interface OptimizationMarkersUiState {
    data object Loading : OptimizationMarkersUiState
    data class Ready(val markers: List<com.example.routeiq.domain.model.GeoPoint>) : OptimizationMarkersUiState
    data class Error(val message: String) : OptimizationMarkersUiState
}
