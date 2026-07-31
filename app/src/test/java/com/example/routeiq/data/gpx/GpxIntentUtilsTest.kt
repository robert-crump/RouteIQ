package com.example.routeiq.data.gpx

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the two ways `.gpx` files reach Route IQ per the manifest's intent filters: opening a
 * file directly (share-sheet "open with" / file browser -> `ACTION_VIEW`) and receiving one via
 * the Android share sheet's "share" action (`ACTION_SEND`, file in `EXTRA_STREAM`).
 */
@RunWith(RobolectricTestRunner::class)
class GpxIntentUtilsTest {

    @Test
    fun `ACTION_VIEW uses the intent data uri`() {
        val uri = Uri.parse("content://com.example.provider/route.gpx")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        assertEquals(uri, extractGpxUri(intent))
    }

    @Test
    fun `ACTION_SEND uses the EXTRA_STREAM uri`() {
        val uri = Uri.parse("content://com.example.provider/route.gpx")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        assertEquals(uri, extractGpxUri(intent))
    }

    @Test
    fun `unrelated action yields no uri`() {
        val intent = Intent(Intent.ACTION_MAIN)

        assertNull(extractGpxUri(intent))
    }

    @Test
    fun `null intent yields no uri`() {
        assertNull(extractGpxUri(null))
    }

    @Test
    fun `ACTION_SEND without a stream extra yields no uri`() {
        val intent = Intent(Intent.ACTION_SEND)

        assertNull(extractGpxUri(intent))
    }
}
