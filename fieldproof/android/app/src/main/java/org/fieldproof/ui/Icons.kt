package org.fieldproof.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons in the style used by the designs (24 px grid, 2 px round strokes). */
object Lucide {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false, stroke: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            if (filled) b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            else b.addPath(
                addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Sprout = icon(
        "sprout", "M7 20h10", "M10 20c5.5-2.5.8-6.4 3-10",
        "M9.5 9.4c1.1.8 1.8 2.2 2.3 3.7-2 .4-3.5.4-4.8-.3-1.2-.6-2.3-1.9-3-4.2 2.8-.5 4.4 0 5.5.8z",
        "M14.1 6a7 7 0 0 0-1.1 4c1.9-.1 3.3-.6 4.3-1.4 1-1 1.6-2.3 1.7-4.6-2.7.1-4 1-4.9 2z",
    )
    val Map = icon("map", "M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3z", "M9 3v15", "M15 6v15")
    val RotateCw = icon("rotate", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")
    val FileText = icon("filetext", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")
    val File = icon("file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4")
    val FileDown = icon("filedown", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M12 18v-6", "M9 15l3 3 3-3")
    val Globe = icon("globe", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val Camera = icon("camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val MapPin = icon("mappin", "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0", "M9 10a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val Plus = icon("plus", "M5 12h14", "M12 5v14")
    val Minus = icon("minus", "M5 12h14")
    val House = icon("house", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", stroke = 1.8f)
    val Users = icon("users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M5 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75", stroke = 1.8f)
    val RefreshCw = icon("refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5", stroke = 1.8f)
    val ChevronRight = icon("chevright", "M9 18l6-6-6-6")
    val ChevronLeft = icon("chevleft", "M15 18l-6-6 6-6", stroke = 2.4f)
    val ChevronDown = icon("chevdown", "M6 9l6 6 6-6")
    val Check = icon("check", "M20 6 9 17l-5-5", stroke = 2.6f)
    val Play = icon("play", "M7 4.5v15a.8.8 0 0 0 1.2.7l12-7.5a.8.8 0 0 0 0-1.4l-12-7.5A.8.8 0 0 0 7 4.5z", filled = true)
    val Search = icon("search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "m21 21-4.3-4.3")
    val Share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val Alert = icon("alert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    val X = icon("x", "M18 6 6 18", "M6 6l12 12", stroke = 2.4f)
    val ArrowUp = icon("arrowup", "M12 19V5", "m5 12 7-7 7 7", stroke = 2.6f)
    val Navigation = icon("navigation", "M3 11 22 2 13 21 11 13 3 11z")
    val IndianRupee = icon("rupee", "M6 3h12", "M6 8h12", "m6 13 8.5 8", "M6 13h3", "M9 13c6.667 0 6.667-10 0-10")
    val Truck = icon("truck", "M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2", "M15 18H9", "M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.62L18.3 8.38A1 1 0 0 0 17.52 8H14", "M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M15 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val Shovel = icon("shovel", "M2 22v-5l5-5 5 5-5 5z", "M9.5 14.5 16 8", "m17 2 5 5-.5.5a3.53 3.53 0 0 1-5 0s0 0 0 0a3.53 3.53 0 0 1 0-5L17 2")
    val Hourglass = icon("hourglass", "M5 22h14", "M5 2h14", "M17 22v-4.17a2 2 0 0 0-.59-1.42L12 12l-4.41 4.41A2 2 0 0 0 7 17.83V22", "M7 2v4.17a2 2 0 0 0 .59 1.42L12 12l4.41-4.41A2 2 0 0 0 17 6.17V2")
    val Image = icon("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 9m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "m21 15-3.09-3.09a2 2 0 0 0-2.82 0L6 21")
    val Crosshair = icon("crosshair", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M22 12h-4", "M6 12H2", "M12 6V2", "M12 22v-4")
}
