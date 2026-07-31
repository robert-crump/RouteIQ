package com.example.routeiq.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxParserTest {

    private fun parse(xml: String) =
        GpxParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses single track segment`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"/>
                <trkpt lat="50.79" lon="6.08"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val result = parse(gpx)
        assertTrue(result.isSuccess)
        val track = result.getOrThrow()
        assertEquals(2, track.points.size)
        assertEquals(50.78, track.points[0].latitude, 1e-6)
        assertEquals(6.07, track.points[0].longitude, 1e-6)
    }

    @Test
    fun `concatenates multiple track segments`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="50.78" lon="6.07"/>
                  <trkpt lat="50.79" lon="6.08"/>
                </trkseg>
                <trkseg>
                  <trkpt lat="50.80" lon="6.09"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(3, track.points.size)
        assertEquals(50.80, track.points[2].latitude, 1e-6)
    }

    @Test
    fun `parses rte format`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <rte>
                <rtept lat="50.78" lon="6.07"/>
                <rtept lat="50.79" lon="6.08"/>
              </rte>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(2, track.points.size)
    }

    @Test
    fun `empty file returns failure`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
            </gpx>
        """.trimIndent()

        val result = parse(gpx)
        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed XML returns failure`() {
        val gpx = "this is not xml"
        val result = parse(gpx)
        assertTrue(result.isFailure)
    }

    @Test
    fun `missing lat or lon skips point`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78"/>
                <trkpt lat="50.79" lon="6.08"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(1, track.points.size)
        assertEquals(50.79, track.points[0].latitude, 1e-6)
    }

    @Test
    fun `extracts track name from trk`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>My Ride</name>
                <trkseg>
                  <trkpt lat="50.78" lon="6.07"/>
                  <trkpt lat="50.79" lon="6.08"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals("My Ride", track.name)
    }

    @Test
    fun `parses elevation per track point`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"><ele>100.5</ele></trkpt>
                <trkpt lat="50.79" lon="6.08"><ele>110.2</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(listOf(100.5, 110.2), track.elevations)
    }

    @Test
    fun `elevations is null when file has no ele data`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"/>
                <trkpt lat="50.79" lon="6.08"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertNull(track.elevations)
    }

    @Test
    fun `parses elevation for rte format`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <rte>
                <rtept lat="50.78" lon="6.07"><ele>200</ele></rtept>
                <rtept lat="50.79" lon="6.08"><ele>205</ele></rtept>
              </rte>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(listOf(200.0, 205.0), track.elevations)
    }

    @Test
    fun `extracts name from metadata if no track name`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <metadata><name>Metadata Name</name></metadata>
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"/>
                <trkpt lat="50.79" lon="6.08"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals("Metadata Name", track.name)
    }

    @Test
    fun `mixed null and present elevations keeps positional nulls`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="50.78" lon="6.07"><ele>100.0</ele></trkpt>
                <trkpt lat="50.79" lon="6.08"/>
                <trkpt lat="50.80" lon="6.09"><ele>120.0</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = parse(gpx).getOrThrow()
        assertEquals(listOf(100.0, null, 120.0), track.elevations)
    }
}
