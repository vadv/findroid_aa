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
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.OfflinePackageRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class FindroidCarSeriesDetailScreen(
    carContext: CarContext,
    private val series: FindroidCarCatalogItem,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
    private val offlinePackageRepository: OfflinePackageRepository,
    private val appPreferences: AppPreferences,
) : Screen(carContext), SurfaceCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: SeriesDetailPresentation? = null
    private var seasons: List<FindroidCarCatalogItem> = emptyList()
    private var resumeEpisode: FindroidCarCatalogItem? = null
    private var seriesOverview: String = ""
    private var loading: Boolean = true
    private var errorMessage: String? = null
    private var lastSurfaceContainer: SurfaceContainer? = null

    init {
        Timber.i(
            "FindroidCarSeriesDetailScreen init seriesId=%s at %s",
            series.itemId,
            System.currentTimeMillis(),
        )
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    Timber.i("FindroidCarSeriesDetailScreen onCreate at %s", System.currentTimeMillis())
                    loadSeasons()
                }

                override fun onStart(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarSeriesDetailScreen onStart at %s (presentation=%s cachedSurface=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                        lastSurfaceContainer != null,
                    )
                    setSurfaceCallback()
                    invalidate()
                    val cached = lastSurfaceContainer
                    if (cached != null) {
                        Timber.i(
                            "FindroidCarSeriesDetailScreen force re-attach to cached surface on onStart"
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
                        "FindroidCarSeriesDetailScreen onStop at %s (presentation kept=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                    )
                    // Keep presentation alive across Screen.onStop so AA navigator
                    // round-trips do not blank the surface (#aa-lifecycle-fix).
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    Timber.i("FindroidCarSeriesDetailScreen onDestroy at %s", System.currentTimeMillis())
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
            "FindroidCarSeriesDetailScreen surface available %sx%s at %s",
            surfaceContainer.width,
            surfaceContainer.height,
            System.currentTimeMillis(),
        )
        lastSurfaceContainer = surfaceContainer
        attachToSurface(surfaceContainer)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Timber.i(
            "FindroidCarSeriesDetailScreen surface destroyed at %s",
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
                    "FindroidCarSeriesDetail",
                    surfaceContainer.width,
                    surfaceContainer.height,
                    surfaceContainer.dpi,
                    surfaceContainer.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            } catch (t: Throwable) {
                Timber.w(
                    t,
                    "FindroidCarSeriesDetailScreen createVirtualDisplay failed; dropping stale surface",
                )
                if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
                return
            }
        virtualDisplay = newDisplay
        val display = newDisplay.display
        if (display == null) {
            Timber.w("FindroidCarSeriesDetailScreen VirtualDisplay had no Display; dropping stale surface")
            newDisplay.release()
            virtualDisplay = null
            if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
            return
        }
        presentation =
            try {
                SeriesDetailPresentation(carContext, display).also {
                    it.show()
                    publishState()
                }
            } catch (t: Throwable) {
                Timber.w(t, "FindroidCarSeriesDetailScreen Presentation create failed")
                newDisplay.release()
                virtualDisplay = null
                null
            }
    }

    override fun onClick(x: Float, y: Float) {
        val payload = presentation?.hitTest(x, y) ?: return
        Timber.i("FindroidCarSeriesDetailScreen click payload=%s", payload)
        val mgr = carContext.getCarService(ScreenManager::class.java)
        when (payload) {
            is SeriesDetailPayload.Back -> mgr.pop()
            is SeriesDetailPayload.PlayContinue -> {
                val episode = resumeEpisode
                if (episode != null) {
                    mgr.push(
                        FindroidCarItemScreen(
                            carContext = carContext,
                            item = episode,
                            jellyfinRepository = jellyfinRepository,
                            jellyfinApi = jellyfinApi,
                        )
                    )
                } else if (seasons.isNotEmpty()) {
                    mgr.push(buildSeasonScreen(seasons.first()))
                }
            }
            is SeriesDetailPayload.OpenSeason -> mgr.push(buildSeasonScreen(payload.season))
        }
    }

    private fun buildSeasonScreen(season: FindroidCarCatalogItem): Screen =
        FindroidCarSeasonDetailScreen(
            carContext = carContext,
            series = series,
            season = season,
            jellyfinRepository = jellyfinRepository,
            jellyfinApi = jellyfinApi,
        )

    private fun loadSeasons() {
        scope.launch {
            loading = true
            if (seasons.isEmpty()) errorMessage = null
            publishState()
            val status =
                FindroidCarNetworkRetry.attempt(
                    "SeriesDetail:${series.itemId}:seasons"
                ) {
                    withContext(Dispatchers.IO) {
                        val seriesId = UUID.fromString(series.itemId)
                        val seasonsList =
                            jellyfinRepository
                                .getSeasons(seriesId)
                                .sortedBy { it.indexNumber }
                                .mapNotNull { it.toFindroidCarCatalogItem(carContext.filesDir) }
                        val resumeItem =
                            runCatching {
                                    jellyfinRepository
                                        .getNextUp(seriesId)
                                        .firstOrNull()
                                        ?.toFindroidCarCatalogItem(carContext.filesDir)
                                }
                                .getOrNull()
                        val overview =
                            runCatching {
                                    val item = jellyfinRepository.getItem(seriesId)
                                    (item as? FindroidShow)?.overview.orEmpty()
                                }
                                .getOrDefault("")
                        SeasonsResult(seasonsList, resumeItem, overview)
                    }
                }
            when (status) {
                is FindroidCarNetworkRetry.Status.Success -> {
                    val value = status.value
                    seasons = value.seasons
                    resumeEpisode = value.resume
                    seriesOverview = value.overview
                    errorMessage = null
                }
                is FindroidCarNetworkRetry.Status.Failure -> {
                    Timber.w(
                        status.failure,
                        "FindroidCarSeriesDetailScreen load failed after %d attempts",
                        status.attempts,
                    )
                    errorMessage =
                        if (seasons.isNotEmpty()) {
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
            SeriesDetailViewState(
                series = series,
                seasons = seasons,
                resumeEpisode = resumeEpisode,
                overview = seriesOverview,
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

    private data class SeasonsResult(
        val seasons: List<FindroidCarCatalogItem>,
        val resume: FindroidCarCatalogItem?,
        val overview: String,
    )
}

internal sealed interface SeriesDetailPayload {
    data object Back : SeriesDetailPayload
    data object PlayContinue : SeriesDetailPayload
    data class OpenSeason(val season: FindroidCarCatalogItem) : SeriesDetailPayload
}

internal data class SeriesDetailViewState(
    val series: FindroidCarCatalogItem,
    val seasons: List<FindroidCarCatalogItem>,
    val resumeEpisode: FindroidCarCatalogItem?,
    val overview: String,
    val loading: Boolean,
    val errorMessage: String?,
)

private class SeriesDetailPresentation(context: Context, display: Display) :
    android.app.Presentation(context, display) {
    private lateinit var view: SeriesDetailView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        view = SeriesDetailView(context)
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

    fun setState(state: SeriesDetailViewState) {
        if (::view.isInitialized) view.setState(state)
    }

    fun hitTest(x: Float, y: Float): SeriesDetailPayload? =
        if (::view.isInitialized) view.hitTest(x, y) else null
}

private class SeriesDetailView(context: Context) : View(context) {
    private var state: SeriesDetailViewState? = null
    private val regions = mutableListOf<RegionEntry>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FindroidCarSurfaceColors.ON_BG
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
        }
    private val rect = RectF()

    private data class RegionEntry(val r: RectF, val payload: SeriesDetailPayload)

    fun setState(newState: SeriesDetailViewState) {
        state = newState
        invalidate()
    }

    fun hitTest(x: Float, y: Float): SeriesDetailPayload? {
        for (entry in regions) {
            if (entry.r.contains(x, y)) return entry.payload
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        regions.clear()
        val s = state ?: return
        canvas.drawColor(FindroidCarSurfaceColors.BG)

        drawHeroBackdrop(canvas, s.series.artworkPaths.firstOrNull()?.let { FindroidCarBitmapCache.bitmap(it) })
        drawBackButton(canvas)
        drawMeta(canvas, s)
        drawSeasonsRow(canvas, s.seasons)
        val hasCachedSeasons = s.seasons.isNotEmpty()
        if (s.loading && !hasCachedSeasons) {
            drawCenteredText(canvas, "Загружаю сериал…", FindroidCarSurfaceColors.ON_BG_MUTED, 22f)
        }
        if (s.errorMessage != null && !hasCachedSeasons) {
            drawCenteredText(canvas, s.errorMessage, FindroidCarSurfaceColors.ON_BG, 22f)
        }
        if (hasCachedSeasons && (s.loading || s.errorMessage != null)) {
            val cornerText = s.errorMessage ?: "Обновление…"
            drawCornerStatus(canvas, cornerText)
        }
    }

    private fun drawCornerStatus(canvas: Canvas, message: String) {
        val savedAlign = textPaint.textAlign
        val savedColor = textPaint.color
        val savedSize = textPaint.textSize
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18f
        canvas.drawText(message, width - 32f, 40f, textPaint)
        textPaint.textAlign = savedAlign
        textPaint.color = savedColor
        textPaint.textSize = savedSize
    }

    private fun drawHeroBackdrop(canvas: Canvas, bitmap: Bitmap?) {
        val heroBottom = HERO_HEIGHT.toFloat()
        rect.set(0f, 0f, width.toFloat(), heroBottom)
        if (bitmap != null) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(width.toFloat() / bitmap.width, heroBottom / bitmap.height)
            val dx = (width - bitmap.width * scale) / 2f
            val dy = (heroBottom - bitmap.height * scale) / 2f
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            paint.style = Paint.Style.FILL
            canvas.drawRect(rect, paint)
            paint.shader = null
        } else {
            val grad =
                RadialGradient(
                    width / 2.5f,
                    heroBottom * 0.4f,
                    width * 1.1f,
                    FindroidCarSurfaceColors.POSTER_PLACEHOLDER_CENTER,
                    FindroidCarSurfaceColors.POSTER_PLACEHOLDER_EDGE,
                    Shader.TileMode.CLAMP,
                )
            paint.shader = grad
            canvas.drawRect(rect, paint)
            paint.shader = null
        }

        val gradTop =
            LinearGradient(
                0f,
                0f,
                0f,
                heroBottom,
                0x4D111318,
                FindroidCarSurfaceColors.BG,
                Shader.TileMode.CLAMP,
            )
        paint.shader = gradTop
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawBackButton(canvas: Canvas) {
        val left = 24f
        val top = 24f
        val size = 80f
        paint.color = FindroidCarSurfaceColors.ICON_BG
        canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f, paint)
        paint.color = FindroidCarSurfaceColors.ON_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val cx = left + size / 2f
        val cy = top + size / 2f
        canvas.drawLine(cx + 16f, cy, cx - 14f, cy, paint)
        val path = Path()
        path.moveTo(cx - 14f, cy)
        path.lineTo(cx - 4f, cy - 10f)
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(cx - 14f, cy)
        path.lineTo(cx - 4f, cy + 10f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        regions += RegionEntry(RectF(left, top, left + size, top + size), SeriesDetailPayload.Back)
    }

    private fun drawMeta(canvas: Canvas, state: SeriesDetailViewState) {
        val left = 132f
        var y = 110f
        textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 18f
        textPaint.letterSpacing = 0.12f
        val crumb = "SERIES · ${state.seasons.size} SEASONS"
        canvas.drawText(crumb.uppercase(), left, y, textPaint)
        textPaint.letterSpacing = 0f
        y += 56f

        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textSize = 64f
        canvas.drawText(elide(state.series.title, textPaint, width - left - 280f), left, y, textPaint)
        y += 36f

        if (state.overview.isNotBlank()) {
            textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textSize = 22f
            val firstLine = elide(state.overview, textPaint, width - left - 280f)
            canvas.drawText(firstLine, left, y + 20f, textPaint)
            y += 56f
        }

        val resumeText =
            state.resumeEpisode?.let { episode ->
                val remainMs = (episode.runtimeTicks - episode.playbackPositionTicks).coerceAtLeast(0L) / 10_000L
                val minutes = (remainMs / 60_000L).coerceAtLeast(1)
                "Continue · $minutes min left"
            } ?: "Play first episode"

        val btnLeft = left
        val btnTop = y + 16f
        val btnHeight = 80f
        textPaint.color = FindroidCarSurfaceColors.ON_PRIMARY
        textPaint.textSize = 22f
        val txtWidth = textPaint.measureText(resumeText)
        val btnWidth = txtWidth + 120f
        rect.set(btnLeft, btnTop, btnLeft + btnWidth, btnTop + btnHeight)
        paint.color = FindroidCarSurfaceColors.PRIMARY
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, btnHeight / 2f, btnHeight / 2f, paint)

        val iconCx = btnLeft + 42f
        val iconCy = btnTop + btnHeight / 2f
        val path = Path()
        path.moveTo(iconCx - 10f, iconCy - 14f)
        path.lineTo(iconCx + 14f, iconCy)
        path.lineTo(iconCx - 10f, iconCy + 14f)
        path.close()
        canvas.drawPath(path, paint.apply { color = FindroidCarSurfaceColors.ON_PRIMARY })
        paint.color = FindroidCarSurfaceColors.PRIMARY

        textPaint.color = FindroidCarSurfaceColors.ON_PRIMARY
        textPaint.textSize = 22f
        canvas.drawText(resumeText, btnLeft + 76f, btnTop + btnHeight / 2f + 8f, textPaint)
        regions += RegionEntry(RectF(rect), SeriesDetailPayload.PlayContinue)
    }

    private fun drawSeasonsRow(canvas: Canvas, seasons: List<FindroidCarCatalogItem>) {
        if (seasons.isEmpty()) return
        val rowTop = HERO_HEIGHT + 60f
        val left = 32f
        textPaint.color = FindroidCarSurfaceColors.ON_BG
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 26f
        canvas.drawText("Seasons", left, rowTop, textPaint)
        val tileW = 220f
        val tileH = 124f
        val gap = 24f
        val tilesY = rowTop + 24f
        seasons.forEachIndexed { index, season ->
            val tx = left + index * (tileW + gap)
            if (tx + tileW > width - 24f) return@forEachIndexed
            val bitmap =
                season.artworkPaths.firstOrNull()?.let { FindroidCarBitmapCache.bitmap(it) }
            rect.set(tx, tilesY, tx + tileW, tilesY + tileH)
            if (bitmap != null) {
                val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = max(tileW / bitmap.width, tileH / bitmap.height)
                val dx = tx + (tileW - bitmap.width * scale) / 2f
                val dy = tilesY + (tileH - bitmap.height * scale) / 2f
                val matrix = Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.shader = null
            } else {
                paint.color = FindroidCarSurfaceColors.SURFACE_1
                canvas.drawRoundRect(rect, 12f, 12f, paint)
            }

            if (index == 0) {
                paint.color = FindroidCarSurfaceColors.PRIMARY
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.style = Paint.Style.FILL
            }

            textPaint.color = FindroidCarSurfaceColors.ON_BG
            textPaint.textSize = 20f
            canvas.drawText(elide(season.title, textPaint, tileW), tx, tilesY + tileH + 32f, textPaint)
            textPaint.color = FindroidCarSurfaceColors.ON_BG_MUTED
            textPaint.textSize = 16f
            canvas.drawText(
                season.runtimeText.ifBlank { "Episodes" },
                tx,
                tilesY + tileH + 56f,
                textPaint,
            )
            regions += RegionEntry(
                RectF(tx, tilesY, tx + tileW, tilesY + tileH + 70f),
                SeriesDetailPayload.OpenSeason(season),
            )
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, color: Int, size: Float) {
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = size
        canvas.drawText(text, width / 2f, height / 2f, textPaint)
    }

    companion object {
        const val HERO_HEIGHT = 380
    }
}
