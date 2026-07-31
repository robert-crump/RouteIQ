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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.routeiq.data.gpx.GpxImportService
import com.example.routeiq.domain.model.GpxTrack
import kotlinx.coroutines.launch

private sealed interface GpxImportUiState {
    data object Idle : GpxImportUiState
    data object Loading : GpxImportUiState
    data class Loaded(val track: GpxTrack) : GpxImportUiState
    data class Error(val message: String) : GpxImportUiState
}

/**
 * Lets the rider bring a `.gpx` route into Route IQ via a manual file picker, or via [incomingUri]
 * when the file arrived through the Android share sheet (see `MainActivity`'s intent handling).
 * Renders the parsed track on [TrackMapView] once loaded, so the import pipeline is verifiable
 * end-to-end ahead of any scoring work.
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

    fun importUri(uri: Uri) {
        uiState = GpxImportUiState.Loading
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
            is GpxImportUiState.Loaded -> GpxTrackSummary(state.track)
        }
    }
}

@Composable
private fun GpxTrackSummary(track: GpxTrack) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(track.name ?: "(unnamed route)")
        Text("${track.points.size} points")
        TrackMapView(points = track.points, modifier = Modifier.fillMaxWidth())
    }
}
