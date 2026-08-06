package com.example.routeiq.ui.gpx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared card shell every scored dimension (Fueling, Optimization, Elevation, Safety,
 * Discovery) renders into on the Results screen (issue #11's resolved design) - extracted from
 * the six near-identical hand-repeated `Card { Column { Text(title) ... } }` shells each score's
 * own issue (#6-#9) had built inline, one per issue, with no shared ancestor to factor into until
 * they were all sitting on one screen together.
 */
@Composable
fun ScoreCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
