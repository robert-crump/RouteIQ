package com.example.routeiq.ui.gpx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.routeiq.domain.matching.MatchResult
import com.example.routeiq.domain.matching.RouteGraphMatcher
import com.example.routeiq.domain.matching.matchedRouteSegments
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.model.GraphEdge
import com.example.routeiq.domain.scoring.DiscoveryBucket
import com.example.routeiq.domain.scoring.DiscoveryScore
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
    val matcher = remember { RouteGraphMatcher(GraphAssetRepository(GraphDatabase.getInstance(context).graphAssetDao())) }

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
            is GpxImportUiState.Loaded -> GpxTrackSummary(state.track, matchState)
        }
    }
}

@Composable
private fun GpxTrackSummary(track: GpxTrack, matchState: MatchUiState?) {
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
        TrackMapView(
            points = track.points,
            modifier = Modifier.fillMaxWidth(),
            matchedSegments = matchedEdges?.let { matchedRouteSegments(traversedEdges) },
            undiscoveredSegments = matchedEdges?.let { matchedRouteSegments(undiscoveredEdges) },
        )
        if (matchedEdges != null) {
            DiscoveryScoreCard(matchedEdges)
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
