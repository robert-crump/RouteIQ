package com.example.routeiq.ui.gpx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.routeiq.domain.matching.MatchResult
import com.example.routeiq.domain.matching.matchedRouteSegments
import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GpxTrack
import com.example.routeiq.domain.scoring.DiscoveryBucket
import com.example.routeiq.domain.scoring.DiscoveryScore
import com.example.routeiq.domain.scoring.DiscoverySegment
import com.example.routeiq.domain.scoring.DiscoveryTraversal
import com.example.routeiq.domain.scoring.ElevationScore
import com.example.routeiq.domain.scoring.FuelingBucket
import com.example.routeiq.domain.scoring.FuelingScore
import com.example.routeiq.domain.scoring.GradeBucket
import com.example.routeiq.domain.scoring.GradeSegment
import com.example.routeiq.domain.scoring.OptimizationBucket
import com.example.routeiq.domain.scoring.OptimizationScore
import com.example.routeiq.domain.scoring.SafetyScore
import com.example.routeiq.domain.scoring.discoverySegments
import com.example.routeiq.domain.scoring.elevationGradeSegments
import com.example.routeiq.domain.scoring.fuelingGapSegments
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Which scored dimension's map geometry is currently toggled on above [TrackMapView] - the
 * standalone segmented control from issue #11's resolved design (`/grill-me` on issue #11), one
 * chip per card, in the same order the cards render in. Defaults to none selected.
 */
enum class ScoreDimension(val label: String) {
    FUELING("Fueling"),
    OPTIMIZATION("Optimization"),
    ELEVATION("Elevation"),
    SAFETY("Safety"),
    DISCOVERY("Discovery"),
}

/**
 * The Results screen (issue #11): every scored dimension as its own independent [ScoreCard], a
 * compact duration summary, a standalone map-overlay toggle, and the map itself - reached once
 * matching resolves (see [com.example.routeiq.ui.RouteIqNavHost]). [MatchUiState.OutsideCoverage]
 * replaces the whole screen with a plain reason message (no cards, no map) rather than showing a
 * misleadingly partial result, per the parent PRD's concern about riders mistaking a partial
 * rating for a complete one.
 */
@Composable
internal fun ResultsScreen(
    track: GpxTrack,
    matchState: MatchUiState,
    fuelingState: FuelingUiState?,
    durationState: DurationUiState?,
    safetyMarkersState: SafetyMarkersUiState?,
    optimizationMarkersState: OptimizationMarkersUiState?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        when (matchState) {
            is MatchUiState.OutsideCoverage -> OutOfCoverageContent(matchState.reason)
            is MatchUiState.Matched -> MatchedResultsContent(
                track, matchState.result, fuelingState, durationState, safetyMarkersState, optimizationMarkersState,
            )
            // Matching/Error never reach this screen - RouteIqNavHost only navigates here once
            // matching resolves to Matched or OutsideCoverage.
            else -> Unit
        }
    }
}

@Composable
private fun OutOfCoverageContent(reason: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Outside covered territory", style = MaterialTheme.typography.headlineSmall)
        Text(reason, color = Color(0xFFB00020))
    }
}

@Composable
private fun MatchedResultsContent(
    track: GpxTrack,
    matchedResult: MatchResult.Matched,
    fuelingState: FuelingUiState?,
    durationState: DurationUiState?,
    safetyMarkersState: SafetyMarkersUiState?,
    optimizationMarkersState: OptimizationMarkersUiState?,
) {
    var useDetourCorridor by remember { mutableStateOf(false) }
    var selectedOverlay by remember { mutableStateOf<ScoreDimension?>(null) }

    val matchedEdges = matchedResult.matchedEdges
    val fuelingResult = (fuelingState as? FuelingUiState.Ready)?.result
    val selectedCorridor = fuelingResult?.let { if (useDetourCorridor) it.withDetour else it.onRoute }
    val safetyResult = remember(matchedResult) { SafetyScore.compute(matchedResult.matchedTurns, matchedResult.matchedDistanceM) }
    val resolvedSafetyMarkers = (safetyMarkersState as? SafetyMarkersUiState.Ready)?.markers
    val optimizationResult = remember(matchedResult) { OptimizationScore.compute(matchedResult.matchedTurns, matchedResult.matchedDistanceM) }
    val resolvedOptimizationMarkers = (optimizationMarkersState as? OptimizationMarkersUiState.Ready)?.markers
    val discoveryScore = remember(matchedEdges) { DiscoveryScore.compute(matchedEdges) }
    val discoveryOverlaySegments = remember(matchedEdges) { discoverySegments(matchedEdges) }

    // Elevation: renders as soon as the .gpx's own <ele> data is available; only when the file has
    // none does it fall back to the bundled graph's DEM-derived slope_percent (issue #7's resolved
    // design). All overlay geometry (including this one) is resolved once up front, not per-tap.
    val gpxElevation = remember(track) { track.elevations?.let { ElevationScore.computeFromGpx(track.points, it) } }
    val demElevation = remember(gpxElevation, matchedEdges) {
        if (gpxElevation == null) ElevationScore.computeFromMatchedEdges(matchedEdges) else null
    }
    val elevationResult = gpxElevation ?: demElevation
    val elevationRoutePoints = remember(gpxElevation, matchedEdges) {
        if (gpxElevation != null) track.points else matchedRouteSegments(matchedEdges).flatten()
    }
    val elevationOverlaySegments = remember(elevationResult, elevationRoutePoints) {
        elevationResult?.let { elevationGradeSegments(elevationRoutePoints, it.profile) }.orEmpty()
    }

    DurationSummaryHeader(durationState)

    FuelingScoreCard(fuelingState, useDetourCorridor, onUseDetourCorridorChange = { useDetourCorridor = it })
    OptimizationScoreCard(optimizationResult)
    ElevationScoreCard(elevationResult)
    SafetyScoreCard(safetyResult)
    DiscoveryScoreCard(discoveryScore)

    OverlaySegmentedControl(
        selected = selectedOverlay,
        onSelect = { dimension -> selectedOverlay = if (selectedOverlay == dimension) null else dimension },
    )

    TrackMapView(
        points = track.points,
        modifier = Modifier.fillMaxWidth(),
        matchedSegments = discoveryOverlaySegmentsFor(selectedOverlay, discoveryOverlaySegments, DiscoveryTraversal.TRAVERSED),
        undiscoveredSegments = discoveryOverlaySegmentsFor(selectedOverlay, discoveryOverlaySegments, DiscoveryTraversal.UNDISCOVERED),
        fuelingSparseSegments = if (selectedOverlay == ScoreDimension.FUELING) selectedCorridor?.let { fuelingGapSegments(track.points, it.sparseRanges) } else null,
        fuelingExtendedGapSegments = if (selectedOverlay == ScoreDimension.FUELING) selectedCorridor?.let { fuelingGapSegments(track.points, it.extendedGaps) } else null,
        safetyMarkers = if (selectedOverlay == ScoreDimension.SAFETY) resolvedSafetyMarkers?.map { it.point to safetyTierColor(it.tier) } else null,
        optimizationMarkers = if (selectedOverlay == ScoreDimension.OPTIMIZATION) resolvedOptimizationMarkers else null,
        elevationGradeSegments = if (selectedOverlay == ScoreDimension.ELEVATION) elevationOverlaySegments.map { it.points to gradeBucketColor(it.bucket) } else null,
    )

    OverlayStatusFooter(
        resolveFooterContent(
            selected = selectedOverlay,
            fuelingState = fuelingState,
            selectedCorridorEmpty = selectedCorridor?.let { it.sparseRanges.isEmpty() && it.extendedGaps.isEmpty() },
            optimizationMarkersState = optimizationMarkersState,
            elevationResult = elevationResult,
            elevationOverlaySegments = elevationOverlaySegments,
            safetyMarkersState = safetyMarkersState,
            discoveryOverlaySegments = discoveryOverlaySegments,
        ),
    )
}

/** Only the requested [traversal] half of [segments], and only when [selected] is Discovery - map param stays null otherwise (no per-tap recomputation, just filtering already-resolved geometry). */
private fun discoveryOverlaySegmentsFor(
    selected: ScoreDimension?,
    segments: List<DiscoverySegment>,
    traversal: DiscoveryTraversal,
): List<List<GeoPoint>>? {
    if (selected != ScoreDimension.DISCOVERY) return null
    return segments.filter { it.traversal == traversal }.map { it.points }
}

@Composable
private fun OverlaySegmentedControl(selected: ScoreDimension?, onSelect: (ScoreDimension) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Show on map", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScoreDimension.entries.forEach { dimension ->
                FilterChip(
                    selected = selected == dimension,
                    onClick = { onSelect(dimension) },
                    label = { Text(dimension.label) },
                )
            }
        }
    }
}

/** Legend swatch color for each 4-tier [GradeBucket] - a green (easy) -> red (steep) ramp, separate from the elevation chart's own TdF climb-category palette. */
private fun gradeBucketColor(bucket: GradeBucket): Color = when (bucket) {
    GradeBucket.FLAT -> Color(0xFF43A047)
    GradeBucket.MODERATE -> Color(0xFFFDD835)
    GradeBucket.STEEP -> Color(0xFFFB8C00)
    GradeBucket.VERY_STEEP -> Color(0xFFE53935)
}

/** LOW/MEDIUM/HIGH marker + legend color, distinct from the other cards' palettes. */
private fun safetyTierColor(tier: SafetyScore.Tier): Color = when (tier) {
    SafetyScore.Tier.LOW -> Color(0xFFFBC02D)
    SafetyScore.Tier.MEDIUM -> Color(0xFFFB8C00)
    SafetyScore.Tier.HIGH -> Color(0xFFE53935)
}

/** Same greens/gray as the pre-#11 always-on matched/undiscovered route rendering. */
private val DISCOVERY_TRAVERSED_COLOR = Color(0xFF2E7D32)
private val DISCOVERY_UNDISCOVERED_COLOR = Color(0xFF757575)
private val FUELING_GAP_COLOR = Color(0xFFE53935)

/** What the map-overlay footer should show for the currently selected dimension. */
private sealed interface FooterContent {
    data object None : FooterContent
    data class Message(val text: String, val isError: Boolean = false) : FooterContent
    data class Legend(val items: List<Pair<String, Color>>) : FooterContent
}

/**
 * Resolves the contextual legend under the map (issue #11's resolved design): a swatch-per-color
 * legend for multi-color overlays, or an explicit "none flagged" note when the selected overlay
 * resolved to zero map geometry - instead of a confusingly blank map. A single-color overlay
 * (Optimization's markers are all one color/shape) resolves to [FooterContent.None] when
 * non-empty - its own [ScoreCard] already tells that story, so no legend swatch is needed. Pure
 * function (no composable calls) so [OverlayStatusFooter] only has to switch on its result.
 */
private fun resolveFooterContent(
    selected: ScoreDimension?,
    fuelingState: FuelingUiState?,
    selectedCorridorEmpty: Boolean?,
    optimizationMarkersState: OptimizationMarkersUiState?,
    elevationResult: ElevationScore.Result?,
    elevationOverlaySegments: List<GradeSegment>,
    safetyMarkersState: SafetyMarkersUiState?,
    discoveryOverlaySegments: List<DiscoverySegment>,
): FooterContent {
    if (selected == null) return FooterContent.None
    return when (selected) {
        ScoreDimension.FUELING -> when {
            fuelingState == null || fuelingState is FuelingUiState.Loading -> FooterContent.Message("Finding resupply points…")
            fuelingState is FuelingUiState.Error -> FooterContent.Message("Couldn't load fueling map data: ${fuelingState.message}", isError = true)
            selectedCorridorEmpty != false -> FooterContent.Message("No fueling issues flagged on this route.")
            else -> FooterContent.Legend(listOf("Sparse resupply" to FUELING_GAP_COLOR, "Extended gap (15km+)" to FUELING_GAP_COLOR))
        }
        ScoreDimension.OPTIMIZATION -> when (optimizationMarkersState) {
            null, is OptimizationMarkersUiState.Loading -> FooterContent.Message("Loading junction locations…")
            is OptimizationMarkersUiState.Error -> FooterContent.Message("Couldn't load junction locations: ${optimizationMarkersState.message}", isError = true)
            is OptimizationMarkersUiState.Ready ->
                if (optimizationMarkersState.markers.isEmpty()) FooterContent.Message("No optimization issues flagged on this route.") else FooterContent.None
        }
        ScoreDimension.ELEVATION -> when {
            elevationResult == null -> FooterContent.Message("No elevation data available for this route.")
            elevationOverlaySegments.isEmpty() -> FooterContent.Message("No elevation issues flagged on this route.")
            else -> FooterContent.Legend(GradeBucket.entries.map { it.label to gradeBucketColor(it) })
        }
        ScoreDimension.SAFETY -> when (safetyMarkersState) {
            null, is SafetyMarkersUiState.Loading -> FooterContent.Message("Loading junction locations…")
            is SafetyMarkersUiState.Error -> FooterContent.Message("Couldn't load junction locations: ${safetyMarkersState.message}", isError = true)
            is SafetyMarkersUiState.Ready ->
                if (safetyMarkersState.markers.isEmpty()) FooterContent.Message("No safety issues flagged on this route.")
                else FooterContent.Legend(SafetyScore.Tier.entries.map { it.label to safetyTierColor(it) })
        }
        ScoreDimension.DISCOVERY -> when {
            discoveryOverlaySegments.isEmpty() -> FooterContent.Message("No discovery issues flagged on this route.")
            else -> FooterContent.Legend(listOf("Ridden before" to DISCOVERY_TRAVERSED_COLOR, "Not yet ridden" to DISCOVERY_UNDISCOVERED_COLOR))
        }
    }
}

@Composable
private fun OverlayStatusFooter(content: FooterContent) {
    when (content) {
        is FooterContent.None -> Unit
        is FooterContent.Message -> Text(
            content.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (content.isError) Color(0xFFB00020) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is FooterContent.Legend -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            content.items.forEach { (label, color) -> LegendItem(label, color) }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = color, radius = 5.dp.toPx()) }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** "2h 34m" for anything an hour or over, "34m" otherwise - no seconds, this is a ride-planning estimate, not a stopwatch. */
private fun formatDurationS(totalDurationS: Double): String {
    val totalMinutes = (totalDurationS / 60.0).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * Compact duration figure near the top of the screen (issue #11's resolved design) - "alongside
 * the scores", not one of the five [ScoreCard]s, per the parent issue's own wording.
 */
@Composable
private fun DurationSummaryHeader(durationState: DurationUiState?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (durationState) {
            null, is DurationUiState.Loading -> Text(
                "Estimating duration…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is DurationUiState.Error -> Text(
                "Duration estimate unavailable: ${durationState.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
            is DurationUiState.Ready -> {
                val result = durationState.result
                if (result == null) {
                    Text(
                        "Duration estimate unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Estimated duration: ${formatDurationS(result.totalDurationS)}", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

@Composable
private fun ElevationScoreCard(elevationResult: ElevationScore.Result?) {
    ScoreCard("Elevation profile") {
        if (elevationResult != null) {
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
        } else {
            Text(
                "No elevation data available for this route",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    fuelingState: FuelingUiState?,
    useDetourCorridor: Boolean,
    onUseDetourCorridorChange: (Boolean) -> Unit,
) {
    ScoreCard("Fueling score") {
        when (fuelingState) {
            null, is FuelingUiState.Loading -> Text(
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

@Composable
private fun DiscoveryScoreCard(score: Int?) {
    ScoreCard("Discovery score") {
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

@Composable
private fun SafetyScoreCard(result: SafetyScore.Result) {
    ScoreCard("Safety score") {
        Text(
            "${result.totalFlaggedCount} flagged junction${if (result.totalFlaggedCount == 1) "" else "s"} " +
                "(${result.flaggedPer100km} / 100km)",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Junctions with a nonzero hazard score, from the map graph's own turn data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.totalFlaggedCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendItem("${SafetyScore.Tier.LOW.label}: ${result.lowCount}", safetyTierColor(SafetyScore.Tier.LOW))
                LegendItem("${SafetyScore.Tier.MEDIUM.label}: ${result.mediumCount}", safetyTierColor(SafetyScore.Tier.MEDIUM))
                LegendItem("${SafetyScore.Tier.HIGH.label}: ${result.highCount}", safetyTierColor(SafetyScore.Tier.HIGH))
            }
        }
    }
}

@Composable
private fun OptimizationScoreCard(result: OptimizationScore.Result) {
    ScoreCard("Optimization score") {
        Text(
            "${result.score} — ${OptimizationBucket.forScore(result.score).label}",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Stop and braking cost of this route's junctions, from the map graph's own turn data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.flaggedJunctions.isNotEmpty()) {
            Text(
                "${result.flaggedJunctions.size} high-cost junction${if (result.flaggedJunctions.size == 1) "" else "s"} flagged on the map.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
