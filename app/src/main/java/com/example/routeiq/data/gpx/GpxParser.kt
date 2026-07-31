package com.example.routeiq.data.gpx

import com.example.routeiq.domain.model.GeoPoint
import com.example.routeiq.domain.model.GpxTrack
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Parses `.gpx` v1.1 files (both `<trk>`/`<trkseg>` and `<rte>` forms) into a [GpxTrack],
 * including `<ele>` elevation per point. Ported from Velometrics' `GpxParser`.
 */
object GpxParser {

    fun parse(inputStream: InputStream): Result<GpxTrack> = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        val trackPoints = mutableListOf<GeoPoint>()
        val routePoints = mutableListOf<GeoPoint>()
        val trackElevations = mutableListOf<Double?>()
        val routeElevations = mutableListOf<Double?>()
        var trackName: String? = null
        var metadataName: String? = null

        var inTrack = false
        var inTrkSeg = false
        var inRoute = false
        var inMetadata = false
        var inName = false
        var inTrkPt = false
        var inRtePt = false
        var inEle = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "metadata" -> inMetadata = true
                        "trk" -> inTrack = true
                        "trkseg" -> inTrkSeg = true
                        "rte" -> inRoute = true
                        "name" -> inName = true
                        "ele" -> inEle = true
                        "trkpt" -> {
                            if (inTrkSeg) {
                                parseLatLon(parser)?.let {
                                    trackPoints.add(it)
                                    trackElevations.add(null)
                                    inTrkPt = true
                                }
                            }
                        }
                        "rtept" -> {
                            if (inRoute) {
                                parseLatLon(parser)?.let {
                                    routePoints.add(it)
                                    routeElevations.add(null)
                                    inRtePt = true
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inName) {
                        val text = parser.text?.trim()
                        if (!text.isNullOrEmpty()) {
                            when {
                                inTrack && !inTrkSeg -> trackName = text
                                inMetadata && metadataName == null -> metadataName = text
                                inRoute && trackName == null -> trackName = text
                            }
                        }
                    }
                    if (inEle) {
                        val elevation = parser.text?.trim()?.toDoubleOrNull()
                        if (elevation != null) {
                            when {
                                inTrkPt && trackElevations.isNotEmpty() ->
                                    trackElevations[trackElevations.lastIndex] = elevation
                                inRtePt && routeElevations.isNotEmpty() ->
                                    routeElevations[routeElevations.lastIndex] = elevation
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "metadata" -> inMetadata = false
                        "trk" -> inTrack = false
                        "trkseg" -> inTrkSeg = false
                        "rte" -> inRoute = false
                        "name" -> inName = false
                        "ele" -> inEle = false
                        "trkpt" -> inTrkPt = false
                        "rtept" -> inRtePt = false
                    }
                }
            }
            event = parser.next()
        }

        val points = trackPoints.ifEmpty { routePoints }
        val elevations = if (trackPoints.isNotEmpty()) trackElevations else routeElevations
        val name = trackName ?: metadataName

        require(points.isNotEmpty()) { "GPX file contains no track or route points" }

        GpxTrack(name = name, points = points, elevations = elevations.takeIf { it.any { e -> e != null } })
    }

    private fun parseLatLon(parser: XmlPullParser): GeoPoint? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        return if (lat != null && lon != null) GeoPoint(lat, lon) else null
    }
}
