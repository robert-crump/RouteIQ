package com.example.routeiq.data.gpx

import android.content.Intent
import android.net.Uri

/**
 * Extracts the `.gpx` [Uri] Route IQ should import from an incoming [Intent], covering both
 * entry points declared on `MainActivity`: opening a `.gpx` file directly (`ACTION_VIEW`) and
 * receiving one via the Android share sheet (`ACTION_SEND`, file in `EXTRA_STREAM`).
 */
fun extractGpxUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}
