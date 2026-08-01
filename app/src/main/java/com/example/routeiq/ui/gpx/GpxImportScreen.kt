package com.example.routeiq.ui.gpx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.routeiq.data.graph.GraphAssetRepository
import com.example.routeiq.data.graph.GraphDatabase
import com.example.routeiq.data.gpx.GpxImportService
import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.matching.MatchResult
import com.example.routeiq.domain.matching.RouteGraphMatcher
import com.example.routeiq.domain.matching.matchedRouteSegments
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.scoring.DiscoveryBucket
import com.example.routeiq.domain.scoring.DiscoveryScore
import com.example.routeiq.domain.scoring.FuelingBucket
import com.example.routeiq.domain.scoring.FuelingScore
import com.example.routeiq.domain.scoring.fuelingGapSegments
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private sealed interface GpxImportUiState {
    data object Idle : GpxImportUiState
    data object Loading : GpxImportUiState
    data class Loaded(val track: GpxTrack) : GpxImportUiState
    data class Error(val message: String) : GpxImportUiState
}

/** Matching runs automatically once a track loads (issue #4) - a separate state so import errors and match errors don't collide. */
private sealed interface MatchUiState {
    data object Matching : MatchUiState
    data class Matched(val result: MatchResult.Matched) : MatchUiState
    data class OutsideCoverage(val reason: String) : MatchUiState
    data class Error(val message: String) : MatchUiState
}

/** Fueling score only runs once matching succeeds (issue #6) - fetching POIs and scoring is async, so it's its own state. */
private sealed interface FuelingUiState {
    data object Loading : FuelingUiState
    data class Ready(val result: FuelingScore.Result?) : FuelingUiState
    data class Error(val message: String) : FuelingUiState
}

/**
 * Lets the rider bring a `.gpx` route into Route IQ via a manual file picker, or via [incomingUri]
 * when the file arrived through the Android share sheet (see `MainActivity`'s intent handling).
 * Renders the parsed track on [TrackMapView] once loaded, so the import pipeline is verifiable
 * end-to-end ahead of any scoring work. Once loaded, the track is automatically matched against
 * the bundled map graph ([RouteGraphMatcher]) - the matched route is drawn distinctly on the map,
 * and a route outside the graph's covered territory is called out explicitly rather than showing
 * an empty or misleading score (issue #4's acceptance criteria).
 */
@Composable
fun GpxImportScreen(
    modifier: Modifier = Modifier,
    incomingUri: Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<GpxImportUiState>(GpxImportUiState.Idle) }
    var matchState by remember { mutableStateOf<MatchUiState?>(null) }
    var fuelingState by remember { mutableStateOf<FuelingUiState?>(null) }
    val repository = remember { GraphAssetRepository(GraphDatabase.getInstance(context).graphAssetDao()) }
    val matcher = remember { RouteGraphMatcher(repository) }

    fun importUri(uri: Uri) {
        uiState = GpxImportUiState.Loading
        matchState = null
        scope.launch {
            val service = GpxImportService(context.contentResolver)
            uiState = service.import(uri).fold(
                onSuccess = { GpxImportUiState.Loaded(it) },
                onFailure = { GpxImportUiState.Error(it.message ?: it.toString()) },
            )
        }
    }

    LaunchedEffect(incomingUri) {
        incomingUri?.let {
            importUri(it)
            onIncomingUriConsumed()
        }
    }

    val loadedTrack = (uiState as? GpxImportUiState.Loaded)?.track
    LaunchedEffect(loadedTrack) {
        val track = loadedTrack ?: return@LaunchedEffect
        matchState = MatchUiState.Matching
        matchState = try {
            when (val result = matcher.match(track)) {
                is MatchResult.Matched -> MatchUiState.Matched(result)
                is MatchResult.OutsideCoverage -> MatchUiState.OutsideCoverage(result.reason)
            }
        } catch (e: Exception) {
            MatchUiState.Error(e.message ?: e.toString())
        }
    }

    val matchedTrackResult = (matchState as? MatchUiState.Matched)?.result
    LaunchedEffect(matchedTrackResult) {
        val track = loadedTrack
        if (matchedTrackResult == null || track == null) {
            fuelingState = null
            return@LaunchedEffect
        }
        fuelingState = FuelingUiState.Loading
        fuelingState = try {
            val box = GeoUtils.computeBoundingBox(track.points, FuelingScore.DETOUR_CORRIDOR_M)
            val pois = repository.getPoisNear(box)
            FuelingUiState.Ready(FuelingScore.compute(track.points, pois))
        } catch (e: Exception) {
            FuelingUiState.Error(e.message ?: e.toString())
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import a .gpx route")
        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Text("Choose .gpx file")
        }
        when (val state = uiState) {
            is GpxImportUiState.Idle -> Text("No route loaded yet.")
            is GpxImportUiState.Loading -> CircularProgressIndicator()
            is GpxImportUiState.Error -> Text("Couldn't import: ${state.message}")
            is GpxImportUiState.Loaded -> GpxTrackSummary(state.track, matchState, fuelingState)
        }
    }
}

@Composable
private fun GpxTrackSummary(track: GpxTrack, matchState: MatchUiState?, fuelingState: FuelingUiState?) {
    var useDetourCorridor by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(track.name ?: "(unnamed route)")
        Text("${track.points.size} points")
        when (matchState) {
            null, is MatchUiState.Matching -> Text("Matching route to the map graph…")
            is MatchUiState.Error -> Text("Couldn't match route: ${matchState.message}")
            is MatchUiState.OutsideCoverage -> Text(
                "Outside covered territory: ${matchState.reason}",
                color = Color(0xFFB00020),
            )
            is MatchUiState.Matched -> Text("Matched to map graph: ${matchState.result.coveragePercent}% of the route covered")
        }
        val matchedEdges = (matchState as? MatchUiState.Matched)?.result?.matchedEdges
        val (traversedEdges, undiscoveredEdges) = matchedEdges.orEmpty().partition { it.isTraversed }
        val fuelingResult = (fuelingState as? FuelingUiState.Ready)?.result
        val selectedCorridor = fuelingResult?.let { if (useDetourCorridor) it.withDetour else it.onRoute }
        TrackMapView(
            points = track.points,
            modifier = Modifier.fillMaxWidth(),
            matchedSegments = matchedEdges?.let { matchedRouteSegments(traversedEdges) },
            undiscoveredSegments = matchedEdges?.let { matchedRouteSegments(undiscoveredEdges) },
            fuelingSparseSegments = selectedCorridor?.let { fuelingGapSegments(track.points, it.sparseRanges) },
            fuelingExtendedGapSegments = selectedCorridor?.let { fuelingGapSegments(track.points, it.extendedGaps) },
        )
        if (matchedEdges != null) {
            DiscoveryScoreCard(matchedEdges)
        }
        if (fuelingState != null) {
            FuelingScoreCard(fuelingState, useDetourCorridor, onUseDetourCorridorChange = { useDetourCorridor = it })
        }
    }
}

@Composable
private fun FuelingScoreCard(
    fuelingState: FuelingUiState,
    useDetourCorridor: Boolean,
    onUseDetourCorridorChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Fueling score", style = MaterialTheme.typography.titleMedium)
            when (fuelingState) {
                is FuelingUiState.Loading -> Text(
                    "Finding resupply points…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is FuelingUiState.Error -> Text(
                    "Couldn't compute fueling score: ${fuelingState.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB00020),
                )
                is FuelingUiState.Ready -> {
                    val result = fuelingState.result
                    if (result == null) {
                        Text(
                            "Fueling score unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${result.score} — ${FuelingBucket.forScore(result.score).label}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "How well-supplied with food/water/fuel this route is, based on resupply points within " +
                                "${FuelingScore.ON_ROUTE_CORRIDOR_M.roundToInt()}m of the route.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text("Max. detour", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onUseDetourCorridorChange(false) }) {
                                Text(if (!useDetourCorridor) "[On-route]" else "On-route")
                            }
                            TextButton(onClick = { onUseDetourCorridorChange(true) }) {
                                Text(if (useDetourCorridor) "[With 500m detour]" else "With 500m detour")
                            }
                        }

                        val selected = if (useDetourCorridor) result.withDetour else result.onRoute
                        if (selected.extendedGaps.isNotEmpty()) {
                            val minGapKm = (FuelingScore.EXTENDED_GAP_MIN_CONSECUTIVE_BUCKETS * result.bucketSizeM / 1000).roundToInt()
                            val ranges = selected.extendedGaps.joinToString(", ") { gap ->
                                "${(gap.startM / 1000).roundToInt()}-${(gap.endM / 1000).roundToInt()} km"
                            }
                            Text(
                                "Warning: No re-fueling for $minGapKm+ km in the following segments: $ranges",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB00020),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryScoreCard(matchedEdges: List<GraphEdge>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Discovery score", style = MaterialTheme.typography.titleMedium)
            val score = DiscoveryScore.compute(matchedEdges)
            if (score == null) {
                Text(
                    "Discovery score unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("$score% — ${DiscoveryBucket.forScore(score).label}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The percentage of this route's roads you haven't ridden before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
