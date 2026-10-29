package org.fieldproof.geo

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One walked parcel written as a KML Placemark. Mirrors backend internal/geo/kml.go. */
data class KmlParcel(
    val name: String,
    val ring: List<LatLng>,
    val farmerName: String,
    val farmerCode: String,
    val village: String,
    val surveyNo: String,
    val accuracyM: Double,
    val walkedM: Double,
    val startedAtMs: Long = 0,
    val finishedAtMs: Long = 0,
)

object Kml {
    /** Degrees with 8 decimals (~1 mm), trailing zeros trimmed. Locale-independent. */
    fun formatCoord(v: Double): String {
        val s = BigDecimal(v).setScale(8, RoundingMode.HALF_EVEN).toPlainString()
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&#34;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** `lon,lat,0` tuples, counter-clockwise, first vertex repeated at the end. */
    fun ringCoordinates(ring: List<LatLng>): String {
        val pts = Geo.ccw(ring)
        return (pts + pts.first()).joinToString(" ") { formatCoord(it.lng) + "," + formatCoord(it.lat) + ",0" }
    }

    private fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    private fun iso(ms: Long) = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).withNano(0))

    fun build(docName: String, parcels: List<KmlParcel>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        append("<Document>\n")
        append("<name>").append(escape(docName)).append("</name>\n")
        append("<Style id=\"fieldproof-boundary\"><LineStyle><color>ff1c50e8</color><width>3</width></LineStyle><PolyStyle><color>4d327d2e</color></PolyStyle></Style>\n")
        for (p in parcels) {
            val area = Geo.area(p.ring)
            val open = Geo.openRing(p.ring)
            append("<Placemark>\n")
            append("<name>").append(escape(p.name)).append("</name>\n")
            val desc = "${p.farmerName} (${p.farmerCode}), ${p.village}. Survey/Gat ${p.surveyNo}. " +
                "Area ${f(area / Geo.SQ_M_PER_ACRE, 2)} ac (${f(area / Geo.SQ_M_PER_HECTARE, 4)} ha, ${f(area, 1)} m²). " +
                "Walked boundary, ${open.size} points, GPS ±${f(p.accuracyM, 1)} m."
            append("<description>").append(escape(desc)).append("</description>\n")
            append("<styleUrl>#fieldproof-boundary</styleUrl>\n")
            append("<ExtendedData>\n")
            val data = mutableListOf(
                "farmer_id" to p.farmerCode,
                "farmer_name" to p.farmerName,
                "village" to p.village,
                "survey_no" to p.surveyNo,
                "area_m2" to f(area, 2),
                "area_ha" to f(area / Geo.SQ_M_PER_HECTARE, 4),
                "area_acres" to f(area / Geo.SQ_M_PER_ACRE, 4),
                "perimeter_m" to f(Geo.perimeter(p.ring), 1),
                "points" to open.size.toString(),
                "gps_accuracy_m" to f(p.accuracyM, 1),
                "walked_m" to f(p.walkedM, 1),
            )
            if (p.startedAtMs > 0) data += "started_at" to iso(p.startedAtMs)
            if (p.finishedAtMs > 0) data += "finished_at" to iso(p.finishedAtMs)
            for ((k, v) in data) append("<Data name=\"").append(k).append("\"><value>").append(escape(v)).append("</value></Data>\n")
            append("</ExtendedData>\n")
            append("<Polygon><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing><coordinates>")
            append(ringCoordinates(p.ring))
            append("</coordinates></LinearRing></outerBoundaryIs></Polygon>\n")
            append("</Placemark>\n")
        }
        append("</Document>\n</kml>\n")
    }
}
