package dev.jdtech.jellyfin.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

internal data class HitRegion(val left: Float, val top: Float, val right: Float, val bottom: Float, val payload: Payload) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    sealed interface Payload {
        data class TabSelect(val tab: FindroidCarBrowseTab) : Payload
        data object ProfileTap : Payload
        data class OpenItem(val item: FindroidCarCatalogItem) : Payload
        data object ScrollUp : Payload
        data object ScrollDown : Payload
    }
}

internal class FindroidCarBrowseView(
    context: Context,
    private val surface: FindroidCarSurface,
) : View(context) {
    private var state = FindroidCarBrowseState()
    private var scrollOffsetPx = 0f
    private var contentHeight = 0f

    private val hitRegions = mutableListOf<HitRegion>()

    fun setState(newState: FindroidCarBrowseState) {
        state = newState
        scrollOffsetPx = 0f
        invalidate()
    }

    fun currentState(): FindroidCarBrowseState = state

    fun scrollBy(dy: Float) {
        val maxScroll = max(0f, contentHeight - height.toFloat())
        scrollOffsetPx = (scrollOffsetPx + dy).coerceIn(0f, maxScroll)
        invalidate()
    }

    fun hitTest(x: Float, y: Float): HitRegion.Payload? {
        for (region in hitRegions) {
            val adjustedY = if (region.left >= SIDEBAR_WIDTH) y + scrollOffsetPx else y
            if (region.contains(x, adjustedY)) return region.payload
        }
        return null
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FindroidCarSurfaceColors.ON_BG
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
        }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRegions.clear()
        canvas.drawColor(FindroidCarSurfaceColors.BG)
        drawSidebar(canvas)
        drawMainArea(canvas)
    }

    private fun drawSidebar(canvas: Canvas) {
        paint.color = FindroidCarSurfaceColors.SURFACE_NAV
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, SIDEBAR_WIDTH, height.toFloat(), paint)

        paint.color = FindroidCarSurfaceColors.OUTLINE_FAINT
        canvas.drawRect(SIDEBAR_WIDTH - 1f, 0f, SIDEBAR_WIDTH, height.toFloat(), paint)

        val totalItems = FindroidCarBrowseTab.values()
        val itemHeight = NAV_ITEM_SIZE
        val gap = NAV_ITEM_GAP
        val totalBlockHeight = totalItems.size * itemHeight + (totalItems.size - 1) * gap
        val verticalPadding = (height - totalBlockHeight) / 2f
        val left = (SIDEBAR_WIDTH - NAV_ITEM_SIZE) / 2f

        totalItems.forEachIndexed { index, tab ->
            val top = verticalPadding + index * (itemHeight + gap)
            val itemRight = left + NAV_ITEM_SIZE
            val itemBottom = top + NAV_ITEM_SIZE
            if (tab == state.activeTab) {
                paint.color = FindroidCarSurfaceColors.SURFACE_2
                rect.set(left, top, itemRight, itemBottom)
                canvas.drawRoundRect(rect, 24f, 24f, paint)
            }

            val iconColor =
                if (tab == state.activeTab) FindroidCarSurfaceColors.ON_BG
                else FindroidCarSurfaceColors.ON_BG_MUTED
            drawNavIcon(canvas, tab, left + (NAV_ITEM_SIZE - NAV_ICON_SIZE) / 2f, top + 18f, iconColor)

            textPaint.color = iconColor
            textPaint.textSize = 20f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(tab.label, left + NAV_ITEM_SIZE / 2f, itemBottom - 20f, textPaint)

            hitRegions += HitRegion(
                left = 0f,
                top = top - gap / 2f,
                right = SIDEBAR_WIDTH,
                bottom = itemBottom + gap / 2f,
                payload = HitRegion.Payload.TabSelect(tab),
            )
        }
    }

    private fun drawNavIcon(canvas: Canvas, tab: FindroidCarBrowseTab, x: Float, y: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val s = NAV_ICON_SIZE
        when (tab) {
            FindroidCarBrowseTab.HOME -> {
                val path = Path()
                path.moveTo(x + 0.05f * s, y + 0.50f * s)
                path.lineTo(x + 0.50f * s, y + 0.10f * s)
                path.lineTo(x + 0.95f * s, y + 0.50f * s)
                canvas.drawPath(path, paint)
                val path2 = Path()
                path2.moveTo(x + 0.15f * s, y + 0.45f * s)
                path2.lineTo(x + 0.15f * s, y + 0.95f * s)
                path2.lineTo(x + 0.85f * s, y + 0.95f * s)
                path2.lineTo(x + 0.85f * s, y + 0.45f * s)
                canvas.drawPath(path2, paint)
            }
            FindroidCarBrowseTab.MOVIES -> {
                rect.set(x + 0.10f * s, y + 0.15f * s, x + 0.90f * s, y + 0.85f * s)
                canvas.drawRoundRect(rect, 6f, 6f, paint)
                canvas.drawLine(x + 0.10f * s, y + 0.40f * s, x + 0.90f * s, y + 0.40f * s, paint)
                canvas.drawLine(x + 0.30f * s, y + 0.15f * s, x + 0.30f * s, y + 0.85f * s, paint)
                canvas.drawLine(x + 0.70f * s, y + 0.15f * s, x + 0.70f * s, y + 0.85f * s, paint)
            }
            FindroidCarBrowseTab.SERIES -> {
                rect.set(x + 0.05f * s, y + 0.20f * s, x + 0.95f * s, y + 0.70f * s)
                canvas.drawRoundRect(rect, 6f, 6f, paint)
                canvas.drawLine(x + 0.30f * s, y + 0.85f * s, x + 0.70f * s, y + 0.85f * s, paint)
                canvas.drawLine(x + 0.50f * s, y + 0.70f * s, x + 0.50f * s, y + 0.85f * s, paint)
            }
            FindroidCarBrowseTab.DOWNLOADS -> {
                canvas.drawLine(x + 0.50f * s, y + 0.10f * s, x + 0.50f * s, y + 0.65f * s, paint)
                val path = Path()
                path.moveTo(x + 0.25f * s, y + 0.45f * s)
                path.lineTo(x + 0.50f * s, y + 0.70f * s)
                path.lineTo(x + 0.75f * s, y + 0.45f * s)
                canvas.drawPath(path, paint)
                canvas.drawLine(x + 0.20f * s, y + 0.92f * s, x + 0.80f * s, y + 0.92f * s, paint)
            }
            FindroidCarBrowseTab.SEARCH -> {
                canvas.drawCircle(x + 0.45f * s, y + 0.45f * s, 0.30f * s, paint)
                canvas.drawLine(
                    x + 0.66f * s,
                    y + 0.66f * s,
                    x + 0.92f * s,
                    y + 0.92f * s,
                    paint,
                )
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawMainArea(canvas: Canvas) {
        val mainLeft = SIDEBAR_WIDTH + MAIN_PADDING_LEFT
        val mainRight = width.toFloat() - MAIN_PADDING_RIGHT
        canvas.save()
        canvas.clipRect(SIDEBAR_WIDTH, 0f, width.toFloat(), height.toFloat())
        canvas.translate(0f, -scrollOffsetPx)
        var contentY = MAIN_PADDING_TOP

        contentY = drawTopbar(canvas, mainLeft, mainRight, contentY)

        when (state.activeTab) {
            FindroidCarBrowseTab.HOME -> {
                contentY = drawContinueWatchingRow(canvas, mainLeft, mainRight, contentY)
                contentY = drawMoviesRow(canvas, mainLeft, mainRight, contentY)
            }
            FindroidCarBrowseTab.MOVIES -> {
                contentY = drawPosterGrid(canvas, mainLeft, mainRight, contentY, state.movies, "Movies")
            }
            FindroidCarBrowseTab.SERIES -> {
                contentY = drawPosterGrid(canvas, mainLeft, mainRight, contentY, state.series, "Series")
            }
            FindroidCarBrowseTab.DOWNLOADS -> {
                contentY = drawPosterGrid(canvas, mainLeft, mainRight, contentY, state.downloads, "Downloads")
            }
            FindroidCarBrowseTab.SEARCH -> {
                contentY = drawSearchInfo(canvas, mainLeft, mainRight, contentY)
            }
        }
        contentHeight = contentY + MAIN_PADDING_BOTTOM
        canvas.restore()

        if (state.loading) drawLoadingOverlay(canvas, mainLeft, mainRight)
        state.errorMessage?.let { drawErrorOverlay(canvas, mainLeft, mainRight, it) }
    }

    private fun drawTopbar(canvas: Canvas, left: Float, right: Float, top: Float): Float {
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Findroid", left, top + 38f, textPaint)

        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(surface.title, right - 84f, top + 36f, textPaint)

        paint.color = FindroidCarSurfaceColors.SURFACE_2
        canvas.drawCircle(right - 36f, top + 32f, 32f, paint)
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textSize = 22f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("F", right - 36f, top + 40f, textPaint)
        hitRegions += HitRegion(right - 72f, top, right, top + 64f, HitRegion.Payload.ProfileTap)

        return top + 80f
    }

    private fun drawSectionTitle(canvas: Canvas, x: Float, y: Float, title: String) {
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 28f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(title, x, y + 28f, textPaint)
    }

    private fun drawContinueWatchingRow(canvas: Canvas, left: Float, right: Float, top: Float): Float {
        drawSectionTitle(canvas, left, top, "Continue Watching")
        var cardY = top + 44f
        val items = state.continueWatching.take(MAX_CONTINUE_CARDS)
        if (items.isEmpty()) {
            return drawEmptyHint(canvas, left, cardY, "Nothing in progress yet")
        }
        val cardW = CONTINUE_CARD_WIDTH
        val cardH = CONTINUE_CARD_HEIGHT
        val gap = 24f
        items.forEachIndexed { index, item ->
            val cardX = left + index * (cardW + gap)
            if (cardX + cardW > right) return@forEachIndexed
            drawHorizontalCard(canvas, item, cardX, cardY, cardW, cardH)
        }
        return cardY + cardH + 50f
    }

    private fun drawMoviesRow(canvas: Canvas, left: Float, right: Float, top: Float): Float {
        drawSectionTitle(canvas, left, top, "Movies")
        val cardY = top + 44f
        val items = state.movies.take(MAX_MOVIES_ROW)
        if (items.isEmpty()) {
            return drawEmptyHint(canvas, left, cardY, "No movies available")
        }
        val cardW = POSTER_WIDTH
        val cardH = POSTER_HEIGHT
        val gap = 24f
        items.forEachIndexed { index, item ->
            val cardX = left + index * (cardW + gap)
            if (cardX + cardW > right) return@forEachIndexed
            drawVerticalPoster(canvas, item, cardX, cardY, cardW, cardH)
        }
        return cardY + cardH + 60f
    }

    private fun drawPosterGrid(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        items: List<FindroidCarCatalogItem>,
        sectionTitle: String,
    ): Float {
        drawSectionTitle(canvas, left, top, sectionTitle)
        var cardY = top + 50f
        if (items.isEmpty()) {
            return drawEmptyHint(canvas, left, cardY, "Empty $sectionTitle")
        }
        val cardW = POSTER_WIDTH
        val cardH = POSTER_HEIGHT
        val gap = 24f
        val perRow = max(1, ((right - left + gap) / (cardW + gap)).toInt())
        val rows = (items.size + perRow - 1) / perRow
        for (row in 0 until rows) {
            for (col in 0 until perRow) {
                val idx = row * perRow + col
                if (idx >= items.size) break
                val cardX = left + col * (cardW + gap)
                drawVerticalPoster(canvas, items[idx], cardX, cardY, cardW, cardH)
            }
            cardY += cardH + 80f
        }
        return cardY
    }

    private fun drawHorizontalCard(
        canvas: Canvas,
        item: FindroidCarCatalogItem,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val bitmap = FindroidCarBitmapCache.firstBitmap(item.artworkPaths)
        drawArtworkRect(canvas, bitmap, x, y, w, h, 16f)

        rect.set(x, y, x + w, y + h)
        val gradient =
            LinearGradient(
                x,
                y + h * 0.5f,
                x,
                y + h,
                Color.TRANSPARENT,
                0xCC000000.toInt(),
                Shader.TileMode.CLAMP,
            )
        paint.shader = gradient
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.shader = null

        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 20f
        canvas.drawText(
            elide(item.subtitle.ifBlank { item.seriesName.orEmpty() }, textPaint, w - 24f),
            x + 18f,
            y + h - 56f,
            textPaint,
        )
        textPaint.textSize = 26f
        canvas.drawText(elide(item.title, textPaint, w - 24f), x + 18f, y + h - 22f, textPaint)

        val progress = progressFraction(item)
        if (progress > 0f) {
            val barH = 6f
            val barTop = y + h - barH
            paint.color = FindroidCarSurfaceColors.PROGRESS_TRACK
            canvas.drawRect(x, barTop, x + w, y + h, paint)
            paint.color = FindroidCarSurfaceColors.PRIMARY
            canvas.drawRect(x, barTop, x + w * progress, y + h, paint)
        }

        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(elide(item.title, textPaint, w), x, y + h + 28f, textPaint)

        hitRegions += HitRegion(
            x,
            y,
            x + w,
            y + h + 40f,
            HitRegion.Payload.OpenItem(item),
        )
    }

    private fun drawVerticalPoster(
        canvas: Canvas,
        item: FindroidCarCatalogItem,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val bitmap = FindroidCarBitmapCache.firstBitmap(item.artworkPaths)
        drawArtworkRect(canvas, bitmap, x, y, w, h, 16f)

        if (bitmap == null) {
            textPaint.color = FindroidCarSurfaceColors.ON_BG_SOFT
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 18f
            canvas.drawText(
                elide(item.title, textPaint, w - 16f),
                x + w / 2f,
                y + h / 2f + 6f,
                textPaint,
            )
        }

        val progress = progressFraction(item)
        if (progress > 0f) {
            val barH = 4f
            val barTop = y + h - barH
            paint.color = FindroidCarSurfaceColors.PROGRESS_TRACK
            canvas.drawRect(x, barTop, x + w, y + h, paint)
            paint.color = FindroidCarSurfaceColors.PRIMARY
            canvas.drawRect(x, barTop, x + w * progress, y + h, paint)
        }

        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 20f
        canvas.drawText(elide(item.title, textPaint, w), x, y + h + 30f, textPaint)
        if (item.subtitle.isNotBlank()) {
            textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textSize = 16f
            canvas.drawText(elide(item.subtitle, textPaint, w), x, y + h + 54f, textPaint)
        }
        hitRegions += HitRegion(x, y, x + w, y + h + 60f, HitRegion.Payload.OpenItem(item))
    }

    private fun drawArtworkRect(
        canvas: Canvas,
        bitmap: Bitmap?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
    ) {
        rect.set(x, y, x + w, y + h)
        if (bitmap != null) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(w / bitmap.width, h / bitmap.height)
            val dx = x + (w - bitmap.width * scale) / 2f
            val dy = y + (h - bitmap.height * scale) / 2f
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        } else {
            val grad =
                RadialGradient(
                    x + w / 2f,
                    y + h / 2.6f,
                    max(w, h),
                    FindroidCarSurfaceColors.POSTER_PLACEHOLDER_CENTER,
                    FindroidCarSurfaceColors.POSTER_PLACEHOLDER_EDGE,
                    Shader.TileMode.CLAMP,
                )
            paint.shader = grad
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
    }

    private fun drawEmptyHint(canvas: Canvas, x: Float, y: Float, message: String): Float {
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 22f
        canvas.drawText(message, x, y + 40f, textPaint)
        return y + 64f
    }

    private fun drawSearchInfo(canvas: Canvas, left: Float, right: Float, top: Float): Float {
        drawSectionTitle(canvas, left, top, "Search")
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 20f
        val msg =
            "Search the Findroid library from your phone. " +
                "Voice and keyboard search land here as it ships."
        canvas.drawText(msg, left, top + 80f, textPaint)
        if (state.searchResults.isNotEmpty()) {
            return drawPosterGrid(canvas, left, right, top + 120f, state.searchResults, "Results")
        }
        return top + 160f
    }

    private fun drawLoadingOverlay(canvas: Canvas, left: Float, right: Float) {
        val hasCachedContent =
            state.continueWatching.isNotEmpty() ||
                state.movies.isNotEmpty() ||
                state.series.isNotEmpty() ||
                state.downloads.isNotEmpty()
        val label = if (hasCachedContent) "Обновление…" else "Загружаю…"
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 22f
        canvas.drawText(label, left, MAIN_PADDING_TOP + 130f, textPaint)
    }

    private fun drawErrorOverlay(canvas: Canvas, left: Float, right: Float, message: String) {
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 22f
        canvas.drawText(message, left, MAIN_PADDING_TOP + 160f, textPaint)
    }

    private fun elide(text: String, textPaint: Paint, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = textPaint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && textPaint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }

    private fun progressFraction(item: FindroidCarCatalogItem): Float {
        if (item.runtimeTicks <= 0L) return 0f
        if (item.playbackPositionTicks <= 0L) return 0f
        return min(0.98f, item.playbackPositionTicks.toFloat() / item.runtimeTicks.toFloat())
    }

    companion object {
        const val SIDEBAR_WIDTH = 120f
        const val NAV_ITEM_SIZE = 96f
        const val NAV_ITEM_GAP = 8f
        const val NAV_ICON_SIZE = 36f
        const val MAIN_PADDING_LEFT = 36f
        const val MAIN_PADDING_RIGHT = 36f
        const val MAIN_PADDING_TOP = 30f
        const val MAIN_PADDING_BOTTOM = 40f
        const val CONTINUE_CARD_WIDTH = 352f
        const val CONTINUE_CARD_HEIGHT = 198f
        const val POSTER_WIDTH = 220f
        const val POSTER_HEIGHT = 308f
        const val MAX_CONTINUE_CARDS = 3
        const val MAX_MOVIES_ROW = 4
    }
}

