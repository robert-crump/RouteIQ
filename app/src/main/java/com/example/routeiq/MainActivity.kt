package com.example.routeiq

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.routeiq.data.gpx.extractGpxUri
import com.example.routeiq.ui.debug.GraphStatsScreen
import com.example.routeiq.ui.gpx.GpxImportScreen
import com.example.routeiq.ui.theme.RouteIQTheme

private enum class RouteIqTab(val label: String) { IMPORT("Import"), GRAPH_STATS("Graph stats") }

class MainActivity : ComponentActivity() {
    private var pendingGpxUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingGpxUri = extractGpxUri(intent)
        setContent {
            RouteIQTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RouteIqApp(
                        modifier = Modifier.padding(innerPadding),
                        incomingGpxUri = pendingGpxUri,
                        onIncomingGpxUriConsumed = { pendingGpxUri = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingGpxUri = extractGpxUri(intent)
    }
}

@Composable
private fun RouteIqApp(
    modifier: Modifier = Modifier,
    incomingGpxUri: Uri? = null,
    onIncomingGpxUriConsumed: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(RouteIqTab.IMPORT) }

    Column(modifier = modifier.fillMaxSize()) {
        Row {
            RouteIqTab.entries.forEach { tab ->
                TextButton(onClick = { selectedTab = tab }) {
                    Text(if (selectedTab == tab) "[${tab.label}]" else tab.label)
                }
            }
        }
        when (selectedTab) {
            RouteIqTab.IMPORT -> GpxImportScreen(
                incomingUri = incomingGpxUri,
                onIncomingUriConsumed = onIncomingGpxUriConsumed,
            )
            RouteIqTab.GRAPH_STATS -> GraphStatsScreen()
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RouteIQTheme {
        Greeting("Android")
    }
}