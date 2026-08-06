package com.example.routeiq.ui.gpx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.routeiq.domain.model.GpxTrack

/**
 * Lets the rider bring a `.gpx` route into Route IQ via a manual file picker, or via a route
 * shared through the Android share sheet (handled by [com.example.routeiq.ui.RouteIqNavHost],
 * which owns [uiState]/[matchState] and every downstream scoring fetch - this screen is purely
 * presentational since issue #11 split scoring/results out into their own [ResultsScreen],
 * reached automatically once matching resolves. A route that fails to *match* (as opposed to one
 * outside the graph's covered territory, which is itself a successful, if negative, match
 * outcome - see [MatchUiState.OutsideCoverage]) is shown right here rather than on the results
 * screen, since there's nothing to show results *of*.
 */
@Composable
internal fun GpxImportScreen(
    modifier: Modifier = Modifier,
    uiState: GpxImportUiState,
    matchState: MatchUiState?,
    onUriPicked: (Uri) -> Unit,
) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUriPicked)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import a .gpx route")
        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Text("Choose .gpx file")
        }
        when (uiState) {
            is GpxImportUiState.Idle -> Text("No route loaded yet.")
            is GpxImportUiState.Loading -> CircularProgressIndicator()
            is GpxImportUiState.Error -> Text("Couldn't import: ${uiState.message}")
            is GpxImportUiState.Loaded -> ImportedTrackStatus(uiState.track, matchState)
        }
    }
}

@Composable
private fun ImportedTrackStatus(track: GpxTrack, matchState: MatchUiState?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(track.name ?: "(unnamed route)")
        Text("${track.points.size} points")
        when (matchState) {
            null, is MatchUiState.Matching -> Text("Matching route to the map graph…")
            is MatchUiState.Error -> Text("Couldn't match route: ${matchState.message}", color = Color(0xFFB00020))
            // Both a real match and an out-of-coverage result are handled on the Results screen
            // (issue #11) - this is just the brief frame before navigation fires.
            is MatchUiState.Matched, is MatchUiState.OutsideCoverage -> Text("Opening results…")
        }
    }
}
