package com.example.routeiq.data.gpx

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

/**
 * Verifies the file-picker/share-sheet boundary end-to-end: a content [Uri] goes in, an open
 * [android.content.ContentResolver] stream comes out, and [GpxParser] turns it into a [GpxTrack].
 */
@RunWith(RobolectricTestRunner::class)
class GpxImportServiceTest {

    private val contentResolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val service = GpxImportService(contentResolver)

    @Test
    fun `imports a gpx track from a content uri`() = runBlocking {
        val uri = Uri.parse("content://com.example.provider/route.gpx")
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"><ele>100.0</ele></trkpt>
                <trkpt lat="50.79" lon="6.08"><ele>110.0</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        shadowOf(contentResolver).registerInputStream(uri, ByteArrayInputStream(gpx.toByteArray()))

        val result = service.import(uri)

        assertTrue(result.isSuccess)
        val track = result.getOrThrow()
        assertEquals(2, track.points.size)
        assertEquals(listOf(100.0, 110.0), track.elevations)
    }

    @Test
    fun `unresolvable uri fails without throwing`() = runBlocking {
        val uri = Uri.parse("content://com.example.provider/missing.gpx")

        val result = service.import(uri)

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed gpx content fails`() = runBlocking {
        val uri = Uri.parse("content://com.example.provider/broken.gpx")
        shadowOf(contentResolver).registerInputStream(uri, ByteArrayInputStream("not gpx".toByteArray()))

        val result = service.import(uri)

        assertTrue(result.isFailure)
    }
}
