package com.example.routeiq.ui.gpx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.example.routeiq.domain.scoring.ElevationScore
import com.example.routeiq.domain.scoring.FuelingBucket
import com.example.routeiq.domain.scoring.FuelingScore
import com.example.routeiq.domain.scoring.fuelingGapSegments
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.floor
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
        // Renders as soon as the .gpx's own <ele> data is available - no map match needed. Only
        // when the file has no elevation data at all does it wait on matchedEdges, to fall back
        // to the bundled graph's DEM-derived slope_percent (issue #7's resolved design).
        val gpxElevation = remember(track) { track.elevations?.let { ElevationScore.computeFromGpx(track.points, it) } }
        val demElevation = remember(gpxElevation, matchedEdges) {
            if (gpxElevation == null) matchedEdges?.let { ElevationScore.computeFromMatchedEdges(it) } else null
        }
        ElevationScoreCard(
            elevationResult = gpxElevation ?: demElevation,
            waitingForMatch = gpxElevation == null && demElevation == null && (matchState == null || matchState is MatchUiState.Matching),
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
private fun ElevationScoreCard(elevationResult: ElevationScore.Result?, waitingForMatch: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Elevation profile", style = MaterialTheme.typography.titleMedium)
            when {
                elevationResult != null -> {
                    ElevationProfileChart(elevationResult)
                    val numberFormat = remember { NumberFormat.getIntegerInstance() }
                    val gainRoundedTo10m = ((elevationResult.gainM / 10.0).roundToInt() * 10)
                    Text(
                        "${numberFormat.format(gainRoundedTo10m)}m ↑, ${numberFormat.format(elevationResult.lossM)}m ↓, " +
                            "${numberFormat.format(elevationResult.gainPer100km)}m / 100km",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (elevationResult.source == ElevationScore.Source.DEM_FALLBACK) {
                        Text(
                            "This file has no elevation data - estimated from the map's elevation data instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                waitingForMatch -> Text(
                    "Matching route to estimate elevation from map data…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "No elevation data available for this route",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Nice" elevation axis steps, smallest first - ported from Velometrics' `GpxAnalysisUtils`. */
private val ELEVATION_AXIS_STEPS_M = listOf(50.0, 100.0, 200.0, 500.0)
private const val ELEVATION_AXIS_MAX_TICKS = 6

/** Data-driven plot area height - the axis/gridlines/line are all sized against this, never the badge band below. */
private val ELEVATION_PLOT_HEIGHT = 140.dp

/** Reserved headroom above the plot purely for climb-category badges - not part of the axis range. */
private val ELEVATION_BADGE_BAND_HEIGHT = 32.dp
private val ELEVATION_BADGE_GAP = 6.dp
private val ELEVATION_BADGE_H_PADDING = 6.dp
private val ELEVATION_BADGE_V_PADDING = 3.dp

private fun elevationAxisStep(minEle: Double, maxEle: Double): Double {
    for (step in ELEVATION_AXIS_STEPS_M) {
        val axisMin = floor(minEle / step) * step
        val rawMax = ceil(maxEle / step) * step
        val axisMax = if (rawMax <= axisMin) axisMin + step else rawMax
        val tickCount = ((axisMax - axisMin) / step).roundToInt() + 1
        if (tickCount <= ELEVATION_AXIS_MAX_TICKS) return step
    }
    return ELEVATION_AXIS_STEPS_M.last()
}

/** TdF-inspired climb-category colors (green -> purple, easy -> hardest), distinct from the primary line color. */
private fun climbCategoryColor(category: ElevationScore.ClimbCategory): Color = when (category) {
    ElevationScore.ClimbCategory.CAT_4 -> Color(0xFF4CAF50)
    ElevationScore.ClimbCategory.CAT_3 -> Color(0xFF2196F3)
    ElevationScore.ClimbCategory.CAT_2 -> Color(0xFFFF9800)
    ElevationScore.ClimbCategory.CAT_1 -> Color(0xFFE53935)
    ElevationScore.ClimbCategory.HC -> Color(0xFF6A1B9A)
}

/** Linearly interpolated elevation at [distanceM] along [profile] (sorted ascending by distance) - used to anchor a climb's badge to its actual summit height. */
private fun elevationAt(profile: List<Pair<Double, Double>>, distanceM: Double): Double {
    val idx = profile.indexOfFirst { it.first >= distanceM }
    return when (idx) {
        0 -> profile.first().second
        -1 -> profile.last().second
        else -> {
            val (d0, e0) = profile[idx - 1]
            val (d1, e1) = profile[idx]
            if (d1 <= d0) return e0
            val t = ((distanceM - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
            e0 + t * (e1 - e0)
        }
    }
}

/**
 * A TdF "stage profile"-style chart: the smoothed elevation line, with the filled area beneath it
 * colored by climb category over each detected climb's distance range (neutral gray elsewhere), and
 * a small category badge horizontally centered over each climb, floating above its summit height in
 * a reserved band above the plot.
 */
@Composable
private fun ElevationProfileChart(result: ElevationScore.Result) {
    val profile = result.profile
    val minEle = profile.minOf { it.second }
    val maxEle = profile.maxOf { it.second }
    val axisStep = elevationAxisStep(minEle, maxEle)
    val axisMin = floor(minEle / axisStep) * axisStep
    val axisMax = (ceil(maxEle / axisStep) * axisStep).let { if (it <= axisMin) axisMin + axisStep else it }
    val axisRange = (axisMax - axisMin).coerceAtLeast(1.0)
    val maxDistance = profile.last().first.coerceAtLeast(1.0)
    val axisTickCount = ((axisMax - axisMin) / axisStep).roundToInt() + 1
    val axisLabels = remember(axisMin, axisMax, axisStep) { (0 until axisTickCount).map { i -> (axisMax - axisStep * i).roundToInt() } }
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val lineColor = MaterialTheme.colorScheme.primary
    val neutralFillColor = MaterialTheme.colorScheme.surfaceVariant
    val referenceLineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val climbColors = result.climbs.map { climbCategoryColor(it.category) }
    val textMeasurer = rememberTextMeasurer()
    val badgeTextStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold)

    Row(modifier = Modifier.fillMaxWidth().height(ELEVATION_PLOT_HEIGHT + ELEVATION_BADGE_BAND_HEIGHT)) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(top = ELEVATION_BADGE_BAND_HEIGHT, end = 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            axisLabels.forEach { label ->
                Text(text = numberFormat.format(label), style = MaterialTheme.typography.labelSmall, color = referenceLineColor)
            }
        }
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val plotHeight = size.height - ELEVATION_BADGE_BAND_HEIGHT.toPx()
            fun xFor(distanceM: Double) = (distanceM / maxDistance).toFloat() * size.width
            fun yFor(elevationM: Double) = size.height - ((elevationM - axisMin) / axisRange).toFloat() * plotHeight

            axisLabels.forEach { label ->
                val y = yFor(label.toDouble())
                drawLine(referenceLineColor.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }

            fun fillPath(startM: Double, endM: Double): Path {
                val path = Path()
                val points = profile.filter { it.first in startM..endM }
                if (points.isEmpty()) return path
                path.moveTo(xFor(startM), size.height)
                path.lineTo(xFor(points.first().first), yFor(points.first().second))
                points.forEach { (d, e) -> path.lineTo(xFor(d), yFor(e)) }
                path.lineTo(xFor(endM), size.height)
                path.close()
                return path
            }

            var cursorM = 0.0
            result.climbs.forEachIndexed { i, climb ->
                if (climb.startM > cursorM) drawPath(fillPath(cursorM, climb.startM), color = neutralFillColor)
                drawPath(fillPath(climb.startM, climb.endM), color = climbColors[i].copy(alpha = 0.5f))
                cursorM = climb.endM
            }
            if (cursorM < maxDistance) drawPath(fillPath(cursorM, maxDistance), color = neutralFillColor)

            val path = Path()
            profile.forEachIndexed { index, (d, e) ->
                val x = xFor(d)
                val y = yFor(e)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

            val badgeGapPx = ELEVATION_BADGE_GAP.toPx()
            val hPaddingPx = ELEVATION_BADGE_H_PADDING.toPx()
            val vPaddingPx = ELEVATION_BADGE_V_PADDING.toPx()
            result.climbs.forEachIndexed { i, climb ->
                val summitY = yFor(elevationAt(profile, climb.endM))
                val textLayout = textMeasurer.measure(climb.category.label, badgeTextStyle)
                val badgeWidth = textLayout.size.width + hPaddingPx * 2
                val badgeHeight = textLayout.size.height + vPaddingPx * 2
                val badgeTop = summitY - badgeGapPx - badgeHeight
                val badgeLeft = xFor((climb.startM + climb.endM) / 2.0) - badgeWidth / 2f
                drawRoundRect(
                    color = climbColors[i],
                    topLeft = Offset(badgeLeft, badgeTop),
                    size = Size(badgeWidth, badgeHeight),
                    cornerRadius = CornerRadius(badgeHeight / 2f, badgeHeight / 2f),
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = climb.category.label,
                    topLeft = Offset(badgeLeft + hPaddingPx, badgeTop + vPaddingPx),
                    style = badgeTextStyle,
                )
            }
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
