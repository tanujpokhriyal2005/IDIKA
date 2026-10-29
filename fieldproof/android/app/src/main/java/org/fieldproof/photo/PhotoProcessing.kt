package org.fieldproof.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedPhoto(val file: File, val sharpness: Double, val readable: Boolean, val lat: Double?, val lng: Double?, val accuracy: Double?, val capturedAt: Long)

/**
 * Evidence photo pipeline: fix rotation, downscale, measure sharpness,
 * burn a location/time stamp into the image, and write EXIF GPS + time.
 */
object PhotoProcessing {
    /** Variance of the Laplacian on a ~640 px grayscale copy. Text on a sharp card scores well above this. */
    const val SHARPNESS_MIN = 60.0
    private const val MAX_SIDE = 2048

    fun process(raw: File, out: File, location: Location?, now: Long = System.currentTimeMillis()): ProcessedPhoto {
        val exif = ExifInterface(raw.absolutePath)
        val rotation = exif.rotationDegrees
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.absolutePath, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(raw.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
            ?: error("Could not read the photo")
        val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (scale < 1f) m.postScale(scale, scale)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)

        val sharp = sharpness(bmp)
        stamp(bmp, location, now)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        raw.delete()

        ExifInterface(out.absolutePath).apply {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fmt.format(Date(now)))
            setAttribute(ExifInterface.TAG_DATETIME, fmt.format(Date(now)))
            setAttribute(ExifInterface.TAG_SOFTWARE, "Fieldproof")
            location?.let { setGpsInfo(it) }
            saveAttributes()
        }
        return ProcessedPhoto(out, sharp, sharp >= SHARPNESS_MIN, location?.latitude, location?.longitude, location?.accuracy?.toDouble(), now)
    }

    fun sharpness(src: Bitmap): Double {
        val w = 640
        val h = (src.height * w.toFloat() / src.width).roundToInt().coerceAtLeast(3)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != src) small.recycle()
        val g = DoubleArray(px.size) { i -> val c = px[i]; 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c) }
        // Central 80% only: edges are usually background.
        val x0 = w / 10; val x1 = w - w / 10; val y0 = h / 10; val y1 = h - h / 10
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in maxOf(1, y0) until minOf(h - 1, y1)) for (x in maxOf(1, x0) until minOf(w - 1, x1)) {
            val i = y * w + x
            val lap = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
            sum += lap; sq += lap * lap; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    private fun stamp(bmp: Bitmap, l: Location?, now: Long) {
        val c = Canvas(bmp)
        val size = bmp.width / 34f
        val pad = size * 0.6f
        val time = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(now))
        val zone = TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(Date(now)), TimeZone.SHORT, Locale.US)
        val line1 = if (l != null) String.format(Locale.US, "%.6f, %.6f  ±%.0f m", l.latitude, l.longitude, l.accuracy) else "Location not available"
        val line2 = "$time $zone · Fieldproof"
        val bandH = size * 2.6f + pad * 2
        c.drawRect(0f, bmp.height - bandH, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        c.drawText(line1, pad, bmp.height - bandH + pad + size, p)
        p.typeface = Typeface.DEFAULT
        c.drawText(line2, pad, bmp.height - pad - size * 0.2f, p)
    }
}
