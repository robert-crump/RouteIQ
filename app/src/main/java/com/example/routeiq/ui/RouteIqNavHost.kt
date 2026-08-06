package com.example.routeiq.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.routeiq.data.graph.GraphAssetRepository
import com.example.routeiq.data.graph.GraphDatabase
import com.example.routeiq.data.gpx.GpxImportService
import com.example.routeiq.domain.matching.GeoUtils
import com.example.routeiq.domain.matching.MatchResult
import com.example.routeiq.domain.matching.RouteGraphMatcher
import com.example.routeiq.domain.scoring.DurationEstimate
import com.example.routeiq.domain.scoring.FuelingScore
import com.example.routeiq.domain.scoring.OptimizationScore
import com.example.routeiq.domain.scoring.SafetyScore
import com.example.routeiq.domain.scoring.optimizationMarkers
import com.example.routeiq.domain.scoring.safetyMarkers
import com.example.routeiq.ui.gpx.DurationUiState
import com.example.routeiq.ui.gpx.FuelingUiState
import com.example.routeiq.ui.gpx.GpxImportScreen
import com.example.routeiq.ui.gpx.GpxImportUiState
import com.example.routeiq.ui.gpx.MatchUiState
import com.example.routeiq.ui.gpx.OptimizationMarkersUiState
import com.example.routeiq.ui.gpx.ResultsScreen
import com.example.routeiq.ui.gpx.SafetyMarkersUiState
import kotlinx.coroutines.launch

private const val ROUTE_IMPORT = "import"
private const val ROUTE_RESULTS = "results"

/**
 * Owns every piece of state the Import -> Results flow needs (issue #11's resolved design: a new
 * Results screen via Jetpack Navigation Compose, reached automatically once matching resolves)
 * and hosts both destinations. State lives here rather than in either screen because navigating
 * to a Compose destination can't carry a `MatchResult`/`GpxTrack` as a type-safe nav argument
 * without introducing (de)serialization this single-Activity, single-in-flight-import app has no
 * other need for - both screens just read it back out of the same remembered state instead.
 */
@Composable
fun RouteIqNavHost(
    modifier: Modifier = Modifier,
    incomingGpxUri: Uri? = null,
    onIncomingGpxUriConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<GpxImportUiState>(GpxImportUiState.Idle) }
    var matchState by remember { mutableStateOf<MatchUiState?>(null) }
    var fuelingState by remember { mutableStateOf<FuelingUiState?>(null) }
    var durationState by remember { mutableStateOf<DurationUiState?>(null) }
    var safetyMarkersState by remember { mutableStateOf<SafetyMarkersUiState?>(null) }
    var optimizationMarkersState by remember { mutableStateOf<OptimizationMarkersUiState?>(null) }
    val repository = remember { GraphAssetRepository(GraphDatabase.getInstance(context).graphAssetDao()) }
    val matcher = remember { RouteGraphMatcher(repository) }
    val navController = rememberNavController()

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

    LaunchedEffect(incomingGpxUri) {
        incomingGpxUri?.let {
            importUri(it)
            onIncomingGpxUriConsumed()
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

    LaunchedEffect(matchedTrackResult) {
        val matchedEdges = matchedTrackResult?.matchedEdges
        if (matchedEdges == null) {
            durationState = null
            return@LaunchedEffect
        }
        durationState = DurationUiState.Loading
        durationState = try {
            val historicalTraversedEdges = repository.getTraversedEdges()
            DurationUiState.Ready(DurationEstimate.compute(matchedEdges, historicalTraversedEdges))
        } catch (e: Exception) {
            DurationUiState.Error(e.message ?: e.toString())
        }
    }

    LaunchedEffect(matchedTrackResult) {
        val track = loadedTrack
        val flaggedJunctions = matchedTrackResult
            ?.let { SafetyScore.compute(it.matchedTurns, it.matchedDistanceM).flaggedJunctions }
        if (track == null || flaggedJunctions == null) {
            safetyMarkersState = null
            return@LaunchedEffect
        }
        safetyMarkersState = SafetyMarkersUiState.Loading
        safetyMarkersState = try {
            val box = GeoUtils.computeBoundingBox(track.points, SafetyScore.JUNCTION_LOOKUP_BUFFER_M)
            val nodes = repository.getNodesNear(box)
            SafetyMarkersUiState.Ready(safetyMarkers(flaggedJunctions, nodes))
        } catch (e: Exception) {
            SafetyMarkersUiState.Error(e.message ?: e.toString())
        }
    }

    LaunchedEffect(matchedTrackResult) {
        val track = loadedTrack
        val flaggedJunctions = matchedTrackResult
            ?.let { OptimizationScore.compute(it.matchedTurns, it.matchedDistanceM).flaggedJunctions }
        if (track == null || flaggedJunctions == null) {
            optimizationMarkersState = null
            return@LaunchedEffect
        }
        optimizationMarkersState = OptimizationMarkersUiState.Loading
        optimizationMarkersState = try {
            val box = GeoUtils.computeBoundingBox(track.points, SafetyScore.JUNCTION_LOOKUP_BUFFER_M)
            val nodes = repository.getNodesNear(box)
            OptimizationMarkersUiState.Ready(optimizationMarkers(flaggedJunctions, nodes))
        } catch (e: Exception) {
            OptimizationMarkersUiState.Error(e.message ?: e.toString())
        }
    }

    // Navigate to the Results screen once matching resolves either way - a real match, or a
    // confirmed-outside-coverage result (itself a complete, if negative, outcome; only a genuine
    // matching *error* stays on the Import screen, since there's nothing to show results of).
    // launchSingleTop guards against a duplicate back-stack entry if this ever re-fires while
    // already on Results.
    LaunchedEffect(matchState) {
        val state = matchState
        if (state is MatchUiState.Matched || state is MatchUiState.OutsideCoverage) {
            navController.navigate(ROUTE_RESULTS) { launchSingleTop = true }
        }
    }

    // Back navigation discards the loaded track (issue #11's resolved design). Reset must happen
    // *before* the pop, in the same action, rather than reactively off the resulting route change -
    // reacting off route alone would race the effect above (which reads the not-yet-reset
    // matchState) and immediately bounce straight back to Results.
    fun backToImport() {
        uiState = GpxImportUiState.Idle
        matchState = null
        fuelingState = null
        durationState = null
        safetyMarkersState = null
        optimizationMarkersState = null
        navController.popBackStack()
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    BackHandler(enabled = currentRoute == ROUTE_RESULTS) { backToImport() }

    NavHost(navController = navController, startDestination = ROUTE_IMPORT, modifier = modifier.fillMaxSize()) {
        composable(ROUTE_IMPORT) {
            GpxImportScreen(uiState = uiState, matchState = matchState, onUriPicked = ::importUri)
        }
        composable(ROUTE_RESULTS) {
            val track = loadedTrack
            val result = matchState
            if (track != null && (result is MatchUiState.Matched || result is MatchUiState.OutsideCoverage)) {
                ResultsScreen(
                    track = track,
                    matchState = result,
                    fuelingState = fuelingState,
                    durationState = durationState,
                    safetyMarkersState = safetyMarkersState,
                    optimizationMarkersState = optimizationMarkersState,
                    onBack = ::backToImport,
                )
            }
        }
    }
}
