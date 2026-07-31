package com.example.routeiq.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.routeiq.data.graph.GraphAssetRepository
import com.example.routeiq.data.graph.GraphDatabase
import com.example.routeiq.data.graph.GraphStats

private sealed interface GraphStatsUiState {
    data object Loading : GraphStatsUiState
    data class Loaded(val stats: GraphStats) : GraphStatsUiState
    data class Error(val message: String) : GraphStatsUiState
}

/**
 * Debug surface confirming the bundled `cycling_graph.db` asset is readable
 * end-to-end: opens it via [GraphDatabase] and shows row counts per table.
 */
@Composable
fun GraphStatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<GraphStatsUiState>(GraphStatsUiState.Loading) }

    LaunchedEffect(Unit) {
        uiState = try {
            val repository = GraphAssetRepository(GraphDatabase.getInstance(context).graphAssetDao())
            GraphStatsUiState.Loaded(repository.getGraphStats())
        } catch (e: Exception) {
            GraphStatsUiState.Error(e.message ?: e.toString())
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Map graph asset")
        when (val state = uiState) {
            is GraphStatsUiState.Loading -> CircularProgressIndicator()
            is GraphStatsUiState.Loaded -> GraphStatsList(state.stats)
            is GraphStatsUiState.Error -> Text("Failed to read cycling_graph.db: ${state.message}")
        }
    }
}

@Composable
private fun GraphStatsList(stats: GraphStats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nodes: ${stats.nodeCount.orNotAvailable()}")
        Text("Edges: ${stats.edgeCount.orNotAvailable()}")
        Text("Turns: ${stats.turnCount.orNotAvailable()}")
        Text("POIs: ${stats.poiCount.orNotAvailable()}")
        Text("Metadata rows: ${stats.metadataCount.orNotAvailable()}")
        Text("Corridors: ${stats.corridorCount.orNotAvailable()}")
        Text("Corridor connectors: ${stats.corridorConnectorCount.orNotAvailable()}")
        Text("Node R-tree entries: ${stats.nodesRtreeCount.orNotAvailable()}")
    }
}

private fun Long?.orNotAvailable(): String = this?.toString() ?: "n/a"
