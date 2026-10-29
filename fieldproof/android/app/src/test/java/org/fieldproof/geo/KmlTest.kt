package org.fieldproof.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.hypot

class KmlTest {
    private val origin = LatLng(19.4512, 77.1874)
    private val t = Geo.Enu(origin)

    private fun dense(corners: List<XY>, step: Double): List<LatLng> {
        val out = mutableListOf<LatLng>()
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val n = ceil(hypot(b.e - a.e, b.n - a.n) / step).toInt()
            for (k in 0 until n) {
                val f = k.toDouble() / n
                out += t.unproject(XY(a.e + f * (b.e - a.e), a.n + f * (b.n - a.n)))
            }
        }
        return out
    }

    private fun parseRings(doc: String): List<List<LatLng>> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val xml = dbf.newDocumentBuilder().parse(doc.byteInputStream())
        assertEquals("http://www.opengis.net/kml/2.2", xml.documentElement.namespaceURI)
        assertEquals("kml", xml.documentElement.localName)
        val coords = xml.getElementsByTagNameNS("http://www.opengis.net/kml/2.2", "coordinates")
        return (0 until coords.length).map { i ->
            (coords.item(i) as Element).textContent.trim().split(Regex("\\s+")).map {
                val f = it.split(",")
                assertEquals(3, f.size)
                LatLng(f[1].toDouble(), f[0].toDouble())
            }
        }
    }

    @Test fun formatCoordIsLocaleIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // decimal comma locale must not leak into KML
            assertEquals("77.0482", Kml.formatCoord(77.0482))
            assertEquals("19.12345679", Kml.formatCoord(19.123456789))
            assertEquals("-0.5", Kml.formatCoord(-0.5))
            assertEquals("10", Kml.formatCoord(10.0))
            val doc = Kml.build("x", listOf(KmlParcel("p", dense(listOf(XY(0.0, 0.0), XY(50.0, 0.0), XY(50.0, 50.0), XY(0.0, 50.0)), 2.0), "a", "b", "c", "d", 3.0, 200.0)))
            assertTrue(doc.contains("<Data name=\"area_m2\"><value>2500.00</value>") || doc.contains("<Data name=\"area_m2\"><value>2499.99</value>") || doc.contains("<Data name=\"area_m2\"><value>2500.01</value>"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun roundTripIsExactClosedAndCounterClockwise() {
        val walk = dense(listOf(XY(0.0, 0.0), XY(90.0, 5.0), XY(95.0, 70.0), XY(-5.0, 66.0)), 2.0)
        val cw = walk.reversed()
        val doc = Kml.build(
            "Sunanda Ramrao Raut · 4417sun",
            listOf(KmlParcel("4417sun · Gat 214/2B", cw, "Sunanda Ramrao Raut & <test>", "4417sun", "Kurunda", "214/2B", 3.0, 330.0, 1760000000000, 1760000400000)),
        )
        val ring = parseRings(doc).single()
        assertEquals(ring.first(), ring.last())
        assertEquals(walk.size + 1, ring.size)
        assertTrue("KML outer ring must be counter-clockwise", Geo.signedArea(ring) > 0)
        for (p in ring) {
            val best = walk.minOf { Geo.distance(it, p) }
            assertTrue("vertex moved $best m", best < 0.002)
        }
        assertEquals(Geo.area(walk), Geo.area(ring), 0.05)
        assertTrue(doc.contains("&amp; &lt;test&gt;"))
        assertTrue(doc.contains("<altitudeMode>clampToGround</altitudeMode>"))
        assertTrue(doc.contains("<Data name=\"started_at\"><value>2025-10-09T08:53:20Z</value></Data>"))
    }

    /** Writes sample files that tools/validate-kml.sh checks against the OGC KML 2.2 XSD. */
    @Test fun writeSampleForSchemaValidation() {
        val walk = dense(listOf(XY(0.0, 0.0), XY(82.0, -6.0), XY(96.0, 40.0), XY(78.0, 84.0), XY(12.0, 80.0), XY(-8.0, 38.0)), 2.0)
        val doc = Kml.build("Schema sample", listOf(KmlParcel("4417sun · Gat 214/2B", walk, "Sunanda Ramrao Raut", "4417sun", "Kurunda, Basmat, Hingoli", "214/2B", 3.1, 412.0, 1760000000000, 1760000400000)))
        val out = File("build/kml-samples").apply { mkdirs() }
        File(out, "android-sample.kml").writeText(doc)
    }
}
