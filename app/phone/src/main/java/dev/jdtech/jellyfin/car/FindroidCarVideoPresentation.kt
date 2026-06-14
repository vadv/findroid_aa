package dev.jdtech.jellyfin.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import timber.log.Timber

class FindroidCarVideoPresentation(
    context: Context,
    display: Display,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
) : Presentation(context, display) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressRunnable =
        object : Runnable {
            override fun run() {
                updateProgress()
                mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }

    private var player: ExoPlayer? = null
    private lateinit var aspectFrame: AspectRatioFrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlayView: FindroidCarPlayerOverlayView
    private var fallbackDurationMs = 0L
    private var positionOffsetMs = 0L
    private var timelineDurationMs = 0L
    private var aspectMode = FindroidCarVideoAspectMode.CROP
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelWidthHeightRatio = 1f
    private var titleText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        textureView =
            TextureView(context).apply {
                isOpaque = true
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
            }

        aspectFrame =
            AspectRatioFrameLayout(context).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(Color.BLACK)
                // Gravity.CENTER keeps the resized rectangle centered inside the surface.
                // AspectRatioFrameLayout shrinks itself for FIT or overflows for ZOOM via
                // setMeasuredDimension(); without an explicit gravity FrameLayout would pin the
                // shrunk/overflowed view to TOP|START and leave all the black space on the right
                // (FIT) or crop only the bottom (ZOOM). See FindroidCarAspectMath for the math.
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    )
                addView(textureView)
            }

        overlayView =
            FindroidCarPlayerOverlayView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    )
            }

        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(aspectFrame)
                addView(overlayView)
            }
        )
        overlayView.updateTitle(titleText)
        overlayView.updateAspectMode(aspectMode)
    }

    override fun dismiss() {
        stopProgressUpdates()
        setPlayer(null)
        super.dismiss()
    }

    fun setPlayer(player: ExoPlayer?) {
        this.player?.clearVideoTextureView(textureView)
        this.player = player
        player?.setVideoTextureView(textureView)
        if (::overlayView.isInitialized) overlayView.updateIsPlaying(player?.isPlaying == true)
        updateProgress()
    }

    fun setControlsVisible(visible: Boolean) {
        if (::overlayView.isInitialized) overlayView.setControlsVisible(visible)
        if (visible) startProgressUpdates() else stopProgressUpdates()
        updateProgress()
    }

    fun setFallbackDuration(durationMs: Long) {
        fallbackDurationMs = durationMs.coerceAtLeast(0L)
        updateProgress()
    }

    fun setPlaybackTimeline(positionOffsetMs: Long, durationMs: Long) {
        this.positionOffsetMs = positionOffsetMs.coerceAtLeast(0L)
        timelineDurationMs = durationMs.coerceAtLeast(0L)
        updateProgress()
    }

    fun setTitle(title: String) {
        titleText = title
        if (::overlayView.isInitialized) overlayView.updateTitle(title)
    }

    fun setIsPlaying(isPlaying: Boolean) {
        if (::overlayView.isInitialized) overlayView.updateIsPlaying(isPlaying)
    }

    fun setAspectMode(mode: FindroidCarVideoAspectMode): String {
        aspectMode = mode
        if (::overlayView.isInitialized) overlayView.updateAspectMode(mode)
        return applyAspectMode()
    }

    fun setVideoSize(
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float,
        mode: FindroidCarVideoAspectMode,
    ): String {
        videoWidth = width
        videoHeight = height
        videoPixelWidthHeightRatio = pixelWidthHeightRatio
        aspectMode = mode
        if (::overlayView.isInitialized) overlayView.updateAspectMode(mode)
        return applyAspectMode()
    }

    private fun applyAspectMode(): String {
        val decision =
            FindroidCarAspectMath.decide(
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pixelWidthHeightRatio = videoPixelWidthHeightRatio,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                mode = aspectMode,
            )
        if (decision == null) {
            aspectFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            return "FIT"
        }
        aspectFrame.resizeMode =
            when (decision.resize) {
                FindroidCarAspectResize.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                FindroidCarAspectResize.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        aspectFrame.setAspectRatio(decision.sourceDar)
        Timber.i(
            "FindroidCarVideoPresentation aspect source=%sx%s par=%s sourceDar=%s surface=%sx%s surfaceDar=%s crop=%s requestedMode=%s appliedMode=%s dest=%sx%s pillarbox=%spx letterbox=%spx",
            videoWidth,
            videoHeight,
            videoPixelWidthHeightRatio,
            decision.sourceDar,
            surfaceWidth,
            surfaceHeight,
            decision.surfaceDar,
            decision.fillCropFraction,
            aspectMode.name,
            decision.appliedLabel,
            decision.destWidth,
            decision.destHeight,
            FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth),
            FindroidCarAspectMath.symmetricInset(surfaceHeight, decision.destHeight),
        )
        return decision.appliedLabel
    }

    fun updateProgress() {
        val player = player ?: return
        val duration =
            timelineDurationMs.takeIf { it > 0L }
                ?: player.duration.takeIf { it > 0L }?.let { it + positionOffsetMs }
                ?: fallbackDurationMs
        val position =
            FindroidCarPlaybackTimeline.absolutePositionMs(
                playerPositionMs = player.currentPosition,
                streamStartPositionMs = positionOffsetMs,
                durationMs = duration,
            )
        if (::overlayView.isInitialized) overlayView.updateProgress(position, duration)
    }

    private fun startProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}

enum class FindroidCarVideoAspectMode(val label: String) {
    AUTO("Smart"),
    FIT("Fit"),
    CROP("Fill"),
    ;

    fun next(): FindroidCarVideoAspectMode =
        when (this) {
            AUTO -> FIT
            FIT -> CROP
            CROP -> AUTO
        }
}

internal class FindroidCarPlayerOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tabularPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FindroidCarSurfaceColors.ON_BG
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
            textSize = 26f
        }
    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FindroidCarSurfaceColors.ON_BG
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
            textSize = 30f
            setShadowLayer(8f, 0f, 2f, 0xB3000000.toInt())
        }
    private val rect = RectF()

    private var controlsVisible = false
    private var title: String = ""
    private var aspectMode = FindroidCarVideoAspectMode.CROP
    private var isPlaying: Boolean = true
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        invalidate()
    }

    fun updateTitle(text: String) {
        title = text
        invalidate()
    }

    fun updateAspectMode(mode: FindroidCarVideoAspectMode) {
        aspectMode = mode
        invalidate()
    }

    fun updateIsPlaying(playing: Boolean) {
        isPlaying = playing
        invalidate()
    }

    fun updateProgress(position: Long, duration: Long) {
        positionMs = position.coerceAtLeast(0L)
        durationMs = duration
        invalidate()
    }

    fun controlsVisible(): Boolean = controlsVisible

    fun progressBarBounds(): RectF =
        if (controlsVisible) progressBarVisible() else progressBarHidden()

    private fun progressBarVisible(): RectF =
        RectF(SEEK_INSET, height - SEEK_BAR_BOTTOM, width - SEEK_INSET, height - SEEK_BAR_BOTTOM + 4f)

    private fun progressBarHidden(): RectF =
        RectF(0f, height.toFloat() - 3f, width.toFloat(), height.toFloat())

    fun playButtonCenter(): Pair<Float, Float> = Pair(width / 2f, height / 2f)

    fun skipMinus10Center(): Pair<Float, Float> = Pair(width / 2f - 160f, height / 2f)

    fun skipMinus30Center(): Pair<Float, Float> = Pair(width / 2f - 320f, height / 2f)

    fun skipPlus10Center(): Pair<Float, Float> = Pair(width / 2f + 160f, height / 2f)

    fun skipPlus30Center(): Pair<Float, Float> = Pair(width / 2f + 320f, height / 2f)

    fun backButtonBounds(): RectF =
        RectF(BACK_LEFT, BACK_TOP, BACK_LEFT + ICON_BTN_SIZE, BACK_TOP + ICON_BTN_SIZE)

    fun zoomButtonBounds(): RectF {
        val left = width - ZOOM_RIGHT - ICON_BTN_SIZE
        return RectF(left, BACK_TOP, left + ICON_BTN_SIZE, BACK_TOP + ICON_BTN_SIZE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (controlsVisible) {
            drawVisibleState(canvas)
        } else {
            drawCleanState(canvas)
        }
    }

    private fun drawVisibleState(canvas: Canvas) {
        drawScrimTop(canvas)
        drawScrimBottom(canvas)
        drawBackButton(canvas)
        drawTitle(canvas)
        drawZoomButton(canvas)
        drawCenterCluster(canvas)
        drawSeekbarLarge(canvas)
    }

    private fun drawCleanState(canvas: Canvas) {
        val scrimTop =
            LinearGradient(
                0f,
                height - 80f,
                0f,
                height.toFloat(),
                Color.TRANSPARENT,
                0x66000000,
                Shader.TileMode.CLAMP,
            )
        paint.shader = scrimTop
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, height - 80f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val barH = 3f
        val barY = height.toFloat() - barH
        paint.color = 0x26FFFFFF
        canvas.drawRect(0f, barY, width.toFloat(), height.toFloat(), paint)
        val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        paint.color = FindroidCarSurfaceColors.ON_BG
        canvas.drawRect(0f, barY, width * progress, height.toFloat(), paint)

        if (durationMs > 0L) {
            tabularPaint.color = 0xA6E1E2E8.toInt()
            tabularPaint.textAlign = Paint.Align.LEFT
            tabularPaint.textSize = 18f
            canvas.drawText(
                "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                32f,
                height - 18f,
                tabularPaint,
            )
        }
    }

    private fun drawScrimTop(canvas: Canvas) {
        val grad =
            LinearGradient(
                0f,
                0f,
                0f,
                160f,
                FindroidCarSurfaceColors.SCRIM_TOP,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        paint.shader = grad
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), 160f, paint)
        paint.shader = null
    }

    private fun drawScrimBottom(canvas: Canvas) {
        val grad =
            LinearGradient(
                0f,
                height - 220f,
                0f,
                height.toFloat(),
                Color.TRANSPARENT,
                FindroidCarSurfaceColors.SCRIM_BOTTOM,
                Shader.TileMode.CLAMP,
            )
        paint.shader = grad
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, height - 220f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawBackButton(canvas: Canvas) {
        val b = backButtonBounds()
        paint.color = FindroidCarSurfaceColors.ICON_BG
        paint.style = Paint.Style.FILL
        canvas.drawCircle(b.centerX(), b.centerY(), ICON_BTN_SIZE / 2f, paint)
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val cx = b.centerX()
        val cy = b.centerY()
        canvas.drawLine(cx + 16f, cy, cx - 14f, cy, paint)
        val arrow = Path()
        arrow.moveTo(cx - 14f, cy)
        arrow.lineTo(cx - 4f, cy - 10f)
        canvas.drawPath(arrow, paint)
        arrow.reset()
        arrow.moveTo(cx - 14f, cy)
        arrow.lineTo(cx - 4f, cy + 10f)
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawZoomButton(canvas: Canvas) {
        val b = zoomButtonBounds()
        paint.color = FindroidCarSurfaceColors.ICON_BG
        paint.style = Paint.Style.FILL
        canvas.drawCircle(b.centerX(), b.centerY(), ICON_BTN_SIZE / 2f, paint)

        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val cx = b.centerX()
        val cy = b.centerY()
        val arm = 14f
        canvas.drawLine(cx - arm, cy - arm, cx - arm + 12f, cy - arm, paint)
        canvas.drawLine(cx - arm, cy - arm, cx - arm, cy - arm + 12f, paint)
        canvas.drawLine(cx + arm, cy - arm, cx + arm - 12f, cy - arm, paint)
        canvas.drawLine(cx + arm, cy - arm, cx + arm, cy - arm + 12f, paint)
        canvas.drawLine(cx - arm, cy + arm, cx - arm + 12f, cy + arm, paint)
        canvas.drawLine(cx - arm, cy + arm, cx - arm, cy + arm - 12f, paint)
        canvas.drawLine(cx + arm, cy + arm, cx + arm - 12f, cy + arm, paint)
        canvas.drawLine(cx + arm, cy + arm, cx + arm, cy + arm - 12f, paint)
        paint.style = Paint.Style.FILL

        titlePaint.color = FindroidCarSurfaceColors.ON_BG
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = 18f
        canvas.drawText(aspectMode.label.uppercase(), b.centerX(), b.bottom + 28f, titlePaint)
    }

    private fun drawTitle(canvas: Canvas) {
        if (title.isBlank()) return
        titlePaint.color = FindroidCarSurfaceColors.ON_BG
        titlePaint.textAlign = Paint.Align.LEFT
        titlePaint.textSize = 28f
        val left = BACK_LEFT + ICON_BTN_SIZE + 24f
        val right = width - ZOOM_RIGHT - ICON_BTN_SIZE - 80f
        val maxWidth = right - left
        canvas.drawText(elide(title, titlePaint, maxWidth), left, BACK_TOP + ICON_BTN_SIZE / 2f + 10f, titlePaint)
    }

    private fun drawCenterCluster(canvas: Canvas) {
        val (px, py) = playButtonCenter()
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.FILL
        canvas.drawCircle(px, py, PLAY_BTN_RADIUS, paint)
        paint.color = FindroidCarSurfaceColors.BG
        if (isPlaying) {
            drawPauseIcon(canvas, px, py)
        } else {
            drawPlayIcon(canvas, px, py)
        }

        val (m30x, m30y) = skipMinus30Center()
        drawSkip(canvas, m30x, m30y, "30", reverse = true)
        val (m10x, m10y) = skipMinus10Center()
        drawEpisodeNav(canvas, m10x, m10y, prev = true)
        val (p10x, p10y) = skipPlus10Center()
        drawEpisodeNav(canvas, p10x, p10y, prev = false)
        val (p30x, p30y) = skipPlus30Center()
        drawSkip(canvas, p30x, p30y, "30", reverse = false)
    }

    private fun drawEpisodeNav(canvas: Canvas, cx: Float, cy: Float, prev: Boolean) {
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.FILL
        val sign = if (prev) -1f else 1f
        val barOffset = 22f * sign
        val triBaseOffset = 8f * sign
        val triTip = -10f * sign
        val barW = 5f
        val barH = 36f

        rect.set(cx + barOffset - barW / 2f, cy - barH / 2f, cx + barOffset + barW / 2f, cy + barH / 2f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)

        val path = Path()
        val triHalf = 18f
        path.moveTo(cx + triBaseOffset, cy - triHalf)
        path.lineTo(cx + triBaseOffset + triTip, cy)
        path.lineTo(cx + triBaseOffset, cy + triHalf)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, label: String, reverse: Boolean) {
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        rect.set(cx - 32f, cy - 32f, cx + 32f, cy + 32f)
        val sweep = 280f
        val startAngle = if (reverse) 180f - sweep / 2f + 40f else -40f
        canvas.drawArc(rect, startAngle, sweep * if (reverse) -1f else 1f, false, paint)

        val tipX = if (reverse) cx - 26f else cx + 26f
        val tipY = cy - 24f
        val arrow = Path()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(tipX + (if (reverse) 12f else -12f), tipY - 6f)
        canvas.drawPath(arrow, paint)
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(tipX + (if (reverse) 4f else -4f), tipY + 12f)
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL

        tabularPaint.color = FindroidCarSurfaceColors.ON_BG
        tabularPaint.textAlign = Paint.Align.CENTER
        tabularPaint.textSize = 18f
        canvas.drawText(label, cx, cy + 8f, tabularPaint)
    }

    private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float) {
        val w = 12f
        val h = 38f
        paint.color = FindroidCarSurfaceColors.BG
        paint.style = Paint.Style.FILL
        rect.set(cx - 16f, cy - h / 2f, cx - 16f + w, cy + h / 2f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
        rect.set(cx + 4f, cy - h / 2f, cx + 4f + w, cy + h / 2f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = FindroidCarSurfaceColors.BG
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(cx - 14f, cy - 20f)
        path.lineTo(cx + 18f, cy)
        path.lineTo(cx - 14f, cy + 20f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawSeekbarLarge(canvas: Canvas) {
        val bottomBaseline = height.toFloat() - SEEK_BAR_BOTTOM
        val left = SEEK_INSET
        val right = width - SEEK_INSET

        tabularPaint.color = FindroidCarSurfaceColors.ON_BG
        tabularPaint.textAlign = Paint.Align.LEFT
        tabularPaint.textSize = 22f
        val timeText =
            if (durationMs > 0L) "${formatDuration(positionMs)} / ${formatDuration(durationMs)}"
            else "${formatDuration(positionMs)} / --:--"
        canvas.drawText(timeText, left, bottomBaseline - 28f, tabularPaint)

        paint.color = 0x33FFFFFF
        rect.set(left, bottomBaseline - 2f, right, bottomBaseline + 2f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)

        val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val thumbX = left + (right - left) * progress
        paint.color = FindroidCarSurfaceColors.ON_BG
        rect.set(left, bottomBaseline - 2f, thumbX, bottomBaseline + 2f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)

        val tickPositions = floatArrayOf(0.04f, 0.22f, 0.41f, 0.58f, 0.73f, 0.87f)
        paint.color = 0xD9FFFFFF.toInt()
        for (frac in tickPositions) {
            val x = left + (right - left) * frac
            rect.set(x - 1f, bottomBaseline - 8f, x + 1f, bottomBaseline + 8f)
            canvas.drawRoundRect(rect, 1f, 1f, paint)
        }

        paint.color = FindroidCarSurfaceColors.ON_BG
        canvas.drawCircle(thumbX, bottomBaseline, 18f, paint)
        paint.color = 0x2EE1E2E8
        canvas.drawCircle(thumbX, bottomBaseline, 30f, paint)
    }

    private fun elide(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }

    companion object {
        const val ICON_BTN_SIZE = 80f
        const val BACK_LEFT = 24f
        const val BACK_TOP = 24f
        const val ZOOM_RIGHT = 128f
        const val PLAY_BTN_RADIUS = 64f
        const val SEEK_INSET = 96f
        const val SEEK_BAR_BOTTOM = 56f
    }
}
