package dev.jdtech.jellyfin.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import dev.jdtech.jellyfin.core.R as CoreR
import java.io.File

internal object FindroidCarSurfaceColors {
    const val BG = 0xFF0F1116.toInt()
    const val SURFACE_1 = 0xFF1D2024.toInt()
    const val SURFACE_2 = 0xFF272A2F.toInt()
    const val SURFACE_NAV = 0xFF14171C.toInt()
    const val OUTLINE_FAINT = 0xFF2A2D33.toInt()
    const val ON_BG = 0xFFE1E2E8.toInt()
    const val ON_BG_MUTED = 0xFFAAADB4.toInt()
    const val ON_BG_SOFT = 0xFFC3C6CF.toInt()
    const val PRIMARY = 0xFFA4C9FE.toInt()
    const val ON_PRIMARY = 0xFF00315C.toInt()
    const val PROGRESS_TRACK = 0x2EFFFFFF
    const val SCRIM_TOP = 0xA6000000.toInt()
    const val SCRIM_BOTTOM = 0xBF000000.toInt()
    const val DIM_OVERLAY = 0x66000000
    const val ICON_BG = 0x73000000

    const val POSTER_PLACEHOLDER_CENTER = 0xFF2A3340.toInt()
    const val POSTER_PLACEHOLDER_EDGE = 0xFF0D0F14.toInt()
}

internal object FindroidCarBitmapCache {
    private const val MAX_LONGEST_SIDE = 720
    private val cache = LruCache<String, Bitmap>(48)

    fun bitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        cache.get(path)?.let { return it }
        val file = File(path)
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val bitmap =
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                },
            ) ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    fun firstBitmap(paths: List<String>): Bitmap? {
        for (path in paths) {
            bitmap(path)?.let { return it }
        }
        return null
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > MAX_LONGEST_SIDE) sample *= 2
        return sample
    }
}

internal fun mixColor(start: Int, end: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    val a = (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * f).toInt()
    val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * f).toInt()
    val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * f).toInt()
    val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * f).toInt()
    return Color.argb(a, r, g, b)
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

internal fun ticksToMinutesText(ticks: Long): String {
    val minutes = (ticks / 10_000_000L / 60L).coerceAtLeast(0L)
    return "$minutes min"
}

internal fun progressFraction(playbackTicks: Long, runtimeTicks: Long, maxFraction: Float = 0.99f): Float {
    if (runtimeTicks <= 0L || playbackTicks <= 0L) return 0f
    return (playbackTicks.toFloat() / runtimeTicks.toFloat()).coerceIn(0f, maxFraction)
}

internal fun elide(text: String, paint: Paint, maxWidth: Float): String {
    if (maxWidth <= 0f) return ""
    if (paint.measureText(text) <= maxWidth) return text
    val ellipsis = "…"
    val ellipsisWidth = paint.measureText(ellipsis)
    var end = text.length
    while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
    return text.substring(0, end) + ellipsis
}

internal fun transparentNavigationTemplate(carContext: CarContext): Template {
    val actionStrip =
        ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                                IconCompat.createWithResource(
                                    carContext,
                                    CoreR.drawable.ic_transparent,
                                )
                            )
                            .build()
                    )
                    .setOnClickListener {
                        android.util.Log.i(
                            "FindroidCarAA",
                            "transparentNavigationTemplate actionStrip clicked",
                        )
                    }
                    .build()
            )
            .build()
    return NavigationTemplate.Builder()
        .setActionStrip(actionStrip)
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()
}
