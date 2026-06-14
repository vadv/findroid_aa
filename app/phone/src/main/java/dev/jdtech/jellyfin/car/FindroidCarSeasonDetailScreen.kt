package dev.jdtech.jellyfin.car

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

class FindroidCarSeasonDetailScreen(
    carContext: CarContext,
    private val series: FindroidCarCatalogItem,
    private val season: FindroidCarCatalogItem,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
) : Screen(carContext), SurfaceCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: SeasonDetailPresentation? = null
    private var episodes: List<FindroidCarCatalogItem> = emptyList()
    private var loading = true
    private var errorMessage: String? = null
    private var scrollOffsetPx = 0f
    private var lastSurfaceContainer: SurfaceContainer? = null

    init {
        Timber.i(
            "FindroidCarSeasonDetailScreen init seasonId=%s at %s",
            season.itemId,
            System.currentTimeMillis(),
        )
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    Timber.i("FindroidCarSeasonDetailScreen onCreate at %s", System.currentTimeMillis())
                    loadEpisodes()
                }

                override fun onStart(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarSeasonDetailScreen onStart at %s (presentation=%s cachedSurface=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                        lastSurfaceContainer != null,
                    )
                    setSurfaceCallback()
                    invalidate()
                    val cached = lastSurfaceContainer
                    if (cached != null) {
                        Timber.i(
                            "FindroidCarSeasonDetailScreen force re-attach to cached surface on onStart"
                        )
                        attachToSurface(cached)
                    }
                }

                override fun onResume(owner: LifecycleOwner) {
                    val cached = lastSurfaceContainer ?: return
                    if (presentation == null) attachToSurface(cached)
                }

                override fun onStop(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarSeasonDetailScreen onStop at %s (presentation kept=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                    )
                    // Keep presentation alive — AA navigator may bounce us through onStop
                    // without actually destroying the surface (#aa-lifecycle-fix).
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    Timber.i("FindroidCarSeasonDetailScreen onDestroy at %s", System.currentTimeMillis())
                    clearSurfaceCallback()
                    releasePresentation()
                    lastSurfaceContainer = null
                    scope.cancel()
                }
            }
        )
    }

    override fun onGetTemplate(): Template = transparentNavigationTemplate(carContext)

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Timber.i(
            "FindroidCarSeasonDetailScreen surface available %sx%s at %s",
            surfaceContainer.width,
            surfaceContainer.height,
            System.currentTimeMillis(),
        )
        lastSurfaceContainer = surfaceContainer
        attachToSurface(surfaceContainer)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Timber.i(
            "FindroidCarSeasonDetailScreen surface destroyed at %s",
            System.currentTimeMillis(),
        )
        if (lastSurfaceContainer === surfaceContainer) {
            lastSurfaceContainer = null
        }
        releasePresentation()
    }

    private fun attachToSurface(surfaceContainer: SurfaceContainer) {
        releasePresentation()
        val dm = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val newDisplay =
            try {
                dm.createVirtualDisplay(
                    "FindroidCarSeasonDetail",
                    surfaceContainer.width,
                    surfaceContainer.height,
                    surfaceContainer.dpi,
                    surfaceContainer.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            } catch (t: Throwable) {
                Timber.w(
                    t,
                    "FindroidCarSeasonDetailScreen createVirtualDisplay failed; dropping stale surface",
                )
                if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
                return
            }
        virtualDisplay = newDisplay
        val display = newDisplay.display
        if (display == null) {
            Timber.w("FindroidCarSeasonDetailScreen VirtualDisplay had no Display; dropping stale surface")
            newDisplay.release()
            virtualDisplay = null
            if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
            return
        }
        presentation =
            try {
                SeasonDetailPresentation(carContext, display).also {
                    it.show()
                    publishState()
                }
            } catch (t: Throwable) {
                Timber.w(t, "FindroidCarSeasonDetailScreen Presentation create failed")
                newDisplay.release()
                virtualDisplay = null
                null
            }
    }

    override fun onClick(x: Float, y: Float) {
        val payload = presentation?.hitTest(x, y) ?: return
        Timber.i("FindroidCarSeasonDetailScreen click payload=%s", payload)
        val mgr = carContext.getCarService(ScreenManager::class.java)
        when (payload) {
            is SeasonDetailPayload.Back -> mgr.pop()
            is SeasonDetailPayload.PlayEpisode ->
                mgr.push(
                    FindroidCarItemScreen(
                        carContext = carContext,
                        item = payload.episode,
                        jellyfinRepository = jellyfinRepository,
                        jellyfinApi = jellyfinApi,
                    )
                )
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        scrollOffsetPx = (scrollOffsetPx + distanceY).coerceAtLeast(0f)
        presentation?.setScroll(scrollOffsetPx)
    }

    private fun loadEpisodes() {
        scope.launch {
            loading = true
            if (episodes.isEmpty()) errorMessage = null
            publishState()
            val status =
                FindroidCarNetworkRetry.attempt(
                    "SeasonDetail:${season.itemId}:episodes"
                ) {
                    withContext(Dispatchers.IO) {
                        val seriesId = UUID.fromString(series.itemId)
                        val seasonId = UUID.fromString(season.itemId)
                        val primary = jellyfinRepository.getEpisodes(seriesId, seasonId)
                        val list =
                            if (primary.isNotEmpty()) primary
                            else
                                jellyfinRepository
                                    .getItems(
                                        parentId = seriesId,
                                        includeTypes = listOf(BaseItemKind.EPISODE),
                                        recursive = true,
                                    )
                                    .filterIsInstance<FindroidEpisode>()
                                    .filter {
                                        it.belongsToOnlineSeason(
                                            seriesId,
                                            seasonId,
                                            season.parentIndexNumber,
                                        )
                                    }
                        list.sortedWith(
                                compareBy<FindroidEpisode> { it.indexNumber }.thenBy { it.name }
                            )
                            .mapNotNull { it.toFindroidCarCatalogItem(carContext.filesDir) }
                    }
                }
            when (status) {
                is FindroidCarNetworkRetry.Status.Success -> {
                    episodes = status.value
                    errorMessage = null
                }
                is FindroidCarNetworkRetry.Status.Failure -> {
                    Timber.w(
                        status.failure,
                        "FindroidCarSeasonDetailScreen load failed after %d attempts",
                        status.attempts,
                    )
                    errorMessage =
                        if (episodes.isNotEmpty()) {
                            FindroidCarNetworkRetry.staleCacheMessage(status.failure)
                        } else {
                            FindroidCarNetworkRetry.friendlyMessage(status.failure)
                        }
                }
            }
            loading = false
            publishState()
        }
    }

    private fun publishState() {
        presentation?.setState(
            SeasonDetailViewState(
                series = series,
                season = season,
                episodes = episodes,
                loading = loading,
                errorMessage = errorMessage,
            )
        )
    }

    private fun setSurfaceCallback() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    private fun clearSurfaceCallback() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    private fun releasePresentation() {
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}

internal sealed interface SeasonDetailPayload {
    data object Back : SeasonDetailPayload
    data class PlayEpisode(val episode: FindroidCarCatalogItem) : SeasonDetailPayload
}

internal data class SeasonDetailViewState(
    val series: FindroidCarCatalogItem,
    val season: FindroidCarCatalogItem,
    val episodes: List<FindroidCarCatalogItem>,
    val loading: Boolean,
    val errorMessage: String?,
)

private class SeasonDetailPresentation(context: Context, display: Display) :
    android.app.Presentation(context, display) {
    private lateinit var view: SeasonDetailView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        view = SeasonDetailView(context)
        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(FindroidCarSurfaceColors.BG)
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        )
    }

    fun setState(state: SeasonDetailViewState) {
        if (::view.isInitialized) view.setState(state)
    }

    fun setScroll(offset: Float) {
        if (::view.isInitialized) view.setScroll(offset)
    }

    fun hitTest(x: Float, y: Float): SeasonDetailPayload? =
        if (::view.isInitialized) view.hitTest(x, y) else null
}

private class SeasonDetailView(context: Context) : View(context) {
    private var state: SeasonDetailViewState? = null
    private var scrollOffset = 0f
    private val regions = mutableListOf<RegionEntry>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FindroidCarSurfaceColors.ON_BG
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
        }
    private val rect = RectF()

    private data class RegionEntry(val r: RectF, val payload: SeasonDetailPayload)

    fun setState(newState: SeasonDetailViewState) {
        state = newState
        scrollOffset = 0f
        invalidate()
    }

    fun setScroll(offset: Float) {
        scrollOffset = offset
        invalidate()
    }

    fun hitTest(x: Float, y: Float): SeasonDetailPayload? {
        for (entry in regions) if (entry.r.contains(x, y)) return entry.payload
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        regions.clear()
        val s = state ?: return
        canvas.drawColor(FindroidCarSurfaceColors.BG)

        drawTopbar(canvas, s)
        canvas.save()
        canvas.clipRect(0f, TOPBAR_HEIGHT.toFloat(), width.toFloat(), height.toFloat())
        canvas.translate(0f, -scrollOffset)
        drawEpisodes(canvas, s.episodes)
        canvas.restore()

        val hasCachedEpisodes = s.episodes.isNotEmpty()
        if (s.loading && !hasCachedEpisodes) {
            drawHint(canvas, "Загружаю эпизоды…")
        }
        if (s.errorMessage != null && !hasCachedEpisodes) {
            drawHint(canvas, s.errorMessage)
        }
        if (hasCachedEpisodes && (s.loading || s.errorMessage != null)) {
            val cornerText = s.errorMessage ?: "Обновление…"
            drawCornerStatus(canvas, cornerText)
        }
    }

    private fun drawCornerStatus(canvas: Canvas, message: String) {
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18f
        canvas.drawText(message, width - 32f, 40f, textPaint)
    }

    private fun drawTopbar(canvas: Canvas, s: SeasonDetailViewState) {
        val backLeft = 32f
        val backTop = 24f
        val backSize = 80f
        paint.color = FindroidCarSurfaceColors.SURFACE_1
        canvas.drawCircle(backLeft + backSize / 2f, backTop + backSize / 2f, backSize / 2f, paint)
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val cx = backLeft + backSize / 2f
        val cy = backTop + backSize / 2f
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
        regions += RegionEntry(
            RectF(backLeft, backTop, backLeft + backSize, backTop + backSize),
            SeasonDetailPayload.Back,
        )

        val textX = backLeft + backSize + 24f
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 16f
        textPaint.letterSpacing = 0.12f
        canvas.drawText(
            (s.series.title + " · " + s.season.title).uppercase(),
            textX,
            backTop + 24f,
            textPaint,
        )
        textPaint.letterSpacing = 0f
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textSize = 36f
        val watched = s.episodes.count { it.played }
        val title = "${s.episodes.size} episodes · $watched watched"
        canvas.drawText(title, textX, backTop + 64f, textPaint)
    }

    private fun drawEpisodes(canvas: Canvas, episodes: List<FindroidCarCatalogItem>) {
        val left = 32f
        val right = width - 32f
        val thumbW = 234f
        val thumbH = 132f
        val gap = 16f
        var y = (TOPBAR_HEIGHT + 24).toFloat()

        if (episodes.isEmpty()) {
            textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 22f
            canvas.drawText("No episodes available", left, y + 30f, textPaint)
            return
        }

        episodes.forEachIndexed { index, episode ->
            val rowTop = y
            val rowBottom = y + thumbH

            rect.set(left, rowTop, left + thumbW, rowBottom)
            val bitmap =
                episode.artworkPaths.firstOrNull()?.let { FindroidCarBitmapCache.bitmap(it) }
            if (bitmap != null) {
                val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = max(thumbW / bitmap.width, thumbH / bitmap.height)
                val dx = left + (thumbW - bitmap.width * scale) / 2f
                val dy = rowTop + (thumbH - bitmap.height * scale) / 2f
                val matrix = Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.shader = null
            } else {
                val grad =
                    RadialGradient(
                        left + thumbW / 2f,
                        rowTop + thumbH / 2f,
                        thumbW,
                        FindroidCarSurfaceColors.POSTER_PLACEHOLDER_CENTER,
                        FindroidCarSurfaceColors.POSTER_PLACEHOLDER_EDGE,
                        Shader.TileMode.CLAMP,
                    )
                paint.shader = grad
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.shader = null
            }

            if (index == 0) {
                paint.color = FindroidCarSurfaceColors.PRIMARY
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.style = Paint.Style.FILL
            }

            val progress = progressFraction(episode)
            if (progress > 0f) {
                val barH = 4f
                paint.color = FindroidCarSurfaceColors.PROGRESS_TRACK
                canvas.drawRect(left, rowBottom - barH, left + thumbW, rowBottom, paint)
                paint.color = FindroidCarSurfaceColors.PRIMARY
                canvas.drawRect(left, rowBottom - barH, left + thumbW * progress, rowBottom, paint)
            }

            if (episode.played) {
                paint.color = FindroidCarSurfaceColors.ICON_BG
                canvas.drawCircle(left + thumbW - 20f, rowTop + 20f, 16f, paint)
                paint.color = FindroidCarSurfaceColors.ON_BG
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                val checkBaseX = left + thumbW - 28f
                val checkBaseY = rowTop + 22f
                canvas.drawLine(checkBaseX, checkBaseY, checkBaseX + 6f, checkBaseY + 6f, paint)
                canvas.drawLine(
                    checkBaseX + 6f,
                    checkBaseY + 6f,
                    checkBaseX + 18f,
                    checkBaseY - 8f,
                    paint,
                )
                paint.style = Paint.Style.FILL
            }

            val textX = left + thumbW + 24f
            val episodeNum =
                episode.indexNumber?.let { "E${it.toString().padStart(2, '0')}" }
                    ?: "EP"
            textPaint.color =
                if (index == 0) FindroidCarSurfaceColors.PRIMARY
                else FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 16f
            textPaint.letterSpacing = 0.1f
            canvas.drawText(episodeNum.uppercase(), textX, rowTop + 30f, textPaint)
            textPaint.letterSpacing = 0f

            textPaint.color = FindroidCarSurfaceColors.ON_BG
            textPaint.textSize = 26f
            val titleX = textX + textPaint.measureText(episodeNum.uppercase()) + 18f
            canvas.drawText(
                elide(episode.titleWithoutPrefix(), textPaint, right - titleX),
                titleX,
                rowTop + 32f,
                textPaint,
            )

            textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textSize = 20f
            val statusText = buildString {
                when {
                    progress > 0f -> {
                        val remainMs =
                            ((1f - progress) * episode.runtimeTicks / 10_000L).toLong()
                        val mins = (remainMs / 60_000L).coerceAtLeast(1)
                        append("Continue · $mins min left")
                    }
                    episode.played ->
                        append("${episode.runtimeText.ifBlank { "Episode" }} · Watched")
                    else -> append(episode.runtimeText.ifBlank { "Episode" })
                }
            }
            canvas.drawText(statusText, textX, rowTop + 68f, textPaint)

            regions += RegionEntry(
                RectF(left, rowTop, right, rowBottom + gap),
                SeasonDetailPayload.PlayEpisode(episode),
            )
            y = rowBottom + gap + 16f
        }
    }

    private fun drawHint(canvas: Canvas, message: String) {
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f
        canvas.drawText(message, width / 2f, height / 2f, textPaint)
    }

    private fun progressFraction(episode: FindroidCarCatalogItem): Float {
        if (episode.runtimeTicks <= 0L) return 0f
        if (episode.playbackPositionTicks <= 0L) return 0f
        return (episode.playbackPositionTicks.toFloat() / episode.runtimeTicks.toFloat()).coerceIn(
            0f,
            0.99f,
        )
    }

    private fun elide(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }

    private fun FindroidCarCatalogItem.titleWithoutPrefix(): String {
        val prefix = indexNumber?.let { "E${it.toString().padStart(2, '0')} - " }
        return if (prefix != null && title.startsWith(prefix)) title.removePrefix(prefix) else title
    }

    companion object {
        const val TOPBAR_HEIGHT = 120
    }
}
