package com.example.routeiq.data.gpx

import android.content.ContentResolver
import android.net.Uri
import com.example.routeiq.domain.model.GpxTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and parses a `.gpx` file from a content [Uri] - the shared entry point for both the
 * share-sheet and file-picker import paths, which both hand Route IQ a `Uri` once the OS-level
 * intent handling is done.
 */
class GpxImportService(private val contentResolver: ContentResolver) {

    suspend fun import(uri: Uri): Result<GpxTrack> = withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not open $uri"))
        stream.use { GpxParser.parse(it) }
    }
}
