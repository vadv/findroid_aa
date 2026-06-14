package dev.jdtech.jellyfin.car

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.OfflinePackageRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

class FindroidCarBrowseScreen(
    carContext: CarContext,
    private val surface: FindroidCarSurface,
    private val offlinePackageRepository: OfflinePackageRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
) : Screen(carContext), SurfaceCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: FindroidCarBrowsePresentation? = null
    private var state = FindroidCarBrowseState()
    // Last SurfaceContainer delivered by AppManager. Cleared on onSurfaceDestroyed; held
    // across Screen.onStop so we can self-recover if AA stops re-issuing onSurfaceAvailable
    // after a navigator round-trip (AA Home → back to Findroid).
    private var lastSurfaceContainer: SurfaceContainer? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    Timber.i("FindroidCarBrowseScreen lifecycle onCreate at %s", System.currentTimeMillis())
                    loadAll()
                }

                override fun onStart(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarBrowseScreen lifecycle onStart at %s (presentation=%s cachedSurface=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                        lastSurfaceContainer != null,
                    )
                    setSurfaceCallback()
                    invalidate()
                    loadAll()
                    val cached = lastSurfaceContainer
                    if (cached != null) {
                        Timber.i(
                            "FindroidCarBrowseScreen force re-attach to cached surface on onStart"
                        )
                        attachToSurface(cached)
                    }
                }

                override fun onResume(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarBrowseScreen lifecycle onResume at %s (presentation=%s cachedSurface=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                        lastSurfaceContainer != null,
                    )
                    val cached = lastSurfaceContainer ?: return
                    if (presentation == null) {
                        Timber.i(
                            "FindroidCarBrowseScreen recovering presentation on onResume (no onSurfaceAvailable since stop)"
                        )
                        attachToSurface(cached)
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    Timber.i(
                        "FindroidCarBrowseScreen lifecycle onStop at %s (presentation kept=%s cachedSurface=%s)",
                        System.currentTimeMillis(),
                        presentation != null,
                        lastSurfaceContainer != null,
                    )
                    // Do NOT release VirtualDisplay/Presentation here. AA keeps the underlying
                    // Surface valid across navigator transitions and only fires
                    // onSurfaceDestroyed when the surface is genuinely gone. Releasing in
                    // onStop tore down rendering on AA Home → back navigation and left the
                    // surface gray (#aa-lifecycle-fix).
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    Timber.i("FindroidCarBrowseScreen lifecycle onDestroy at %s", System.currentTimeMillis())
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
            "FindroidCarBrowseScreen surface available %sx%s dpi=%s at %s",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            System.currentTimeMillis(),
        )
        lastSurfaceContainer = surfaceContainer
        attachToSurface(surfaceContainer)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Timber.i(
            "FindroidCarBrowseScreen surface destroyed at %s (sameAsCached=%s)",
            System.currentTimeMillis(),
            lastSurfaceContainer === surfaceContainer,
        )
        if (lastSurfaceContainer === surfaceContainer) {
            lastSurfaceContainer = null
        }
        releasePresentation()
    }

    private fun attachToSurface(surfaceContainer: SurfaceContainer) {
        releasePresentation()
        val displayManager =
            carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val newDisplay =
            try {
                displayManager.createVirtualDisplay(
                    "FindroidCarBrowse",
                    surfaceContainer.width,
                    surfaceContainer.height,
                    surfaceContainer.dpi,
                    surfaceContainer.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            } catch (t: Throwable) {
                Timber.w(
                    t,
                    "FindroidCarBrowseScreen createVirtualDisplay failed; discarding stale SurfaceContainer",
                )
                if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
                return
            }
        virtualDisplay = newDisplay
        val display = newDisplay.display
        if (display == null) {
            Timber.w("FindroidCarBrowseScreen VirtualDisplay had no Display; treating as stale surface")
            newDisplay.release()
            virtualDisplay = null
            if (lastSurfaceContainer === surfaceContainer) lastSurfaceContainer = null
            return
        }
        presentation =
            try {
                FindroidCarBrowsePresentation(carContext, display, surface).also {
                    it.show()
                    it.setState(state)
                }
            } catch (t: Throwable) {
                Timber.w(t, "FindroidCarBrowseScreen Presentation create failed")
                newDisplay.release()
                virtualDisplay = null
                null
            }
    }

    override fun onClick(x: Float, y: Float) {
        val payload = presentation?.hitTest(x, y) ?: return
        Timber.i("FindroidCarBrowseScreen surface click x=%s y=%s payload=%s", x, y, payload)
        when (payload) {
            is HitRegion.Payload.TabSelect -> selectTab(payload.tab)
            is HitRegion.Payload.OpenItem -> openItem(payload.item)
            HitRegion.Payload.ProfileTap -> Unit
            HitRegion.Payload.ScrollUp -> presentation?.scrollBy(-180f)
            HitRegion.Payload.ScrollDown -> presentation?.scrollBy(180f)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        presentation?.scrollBy(distanceY)
    }

    private fun selectTab(tab: FindroidCarBrowseTab) {
        if (state.activeTab == tab) return
        state = state.copy(activeTab = tab)
        presentation?.setState(state)
    }

    private fun openItem(item: FindroidCarCatalogItem) {
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        when (item.itemKind) {
            FindroidCarCatalogItemKind.SERIES ->
                if (item.videoPath == null && item.streamUrl == null && !item.packageId.startsWith("offline-series")) {
                    screenManager.push(
                        FindroidCarSeriesScreen(
                            carContext = carContext,
                            series = item,
                            jellyfinRepository = jellyfinRepository,
                            jellyfinApi = jellyfinApi,
                        )
                    )
                } else {
                    screenManager.push(
                        FindroidCarOfflineSeriesScreen(
                            carContext = carContext,
                            series = item,
                            offlinePackageRepository = offlinePackageRepository,
                            jellyfinRepository = jellyfinRepository,
                            jellyfinApi = jellyfinApi,
                            appPreferences = appPreferences,
                        )
                    )
                }
            FindroidCarCatalogItemKind.MOVIE,
            FindroidCarCatalogItemKind.EPISODE -> {
                if (!item.videoPath.isNullOrBlank() || !item.streamUrl.isNullOrBlank()) {
                    screenManager.push(
                        FindroidCarVideoScreen(
                            carContext = carContext,
                            item = item,
                            jellyfinRepository = jellyfinRepository,
                        )
                    )
                } else {
                    screenManager.push(
                        FindroidCarItemScreen(
                            carContext = carContext,
                            item = item,
                            jellyfinRepository = jellyfinRepository,
                            jellyfinApi = jellyfinApi,
                        )
                    )
                }
            }
            FindroidCarCatalogItemKind.SEASON -> Unit
        }
    }

    private fun loadAll() {
        scope.launch {
            val hasCached =
                state.movies.isNotEmpty() ||
                    state.series.isNotEmpty() ||
                    state.continueWatching.isNotEmpty() ||
                    state.downloads.isNotEmpty()
            state =
                state.copy(
                    loading = true,
                    errorMessage = if (hasCached) state.errorMessage else null,
                )
            presentation?.setState(state)

            val offlineItems =
                runCatching { withContext(Dispatchers.IO) { loadOfflineItems() } }
                    .getOrElse {
                        Timber.w(it, "FindroidCarBrowseScreen offline items load failed")
                        state.downloads
                    }

            val moviesOutcome = fetchOnlineMovies()
            val seriesOutcome = fetchOnlineSeries()
            val resumeOutcome = fetchContinueWatching(offlineItems)

            val freshMovies = moviesOutcome.valueOrNull
            val freshSeries = seriesOutcome.valueOrNull
            val freshResume = resumeOutcome.valueOrNull

            val errors =
                listOfNotNull(
                        moviesOutcome.failureOrNull,
                        seriesOutcome.failureOrNull,
                        resumeOutcome.failureOrNull,
                    )
                    .filterNot { it.isCancellation() }

            val nextMovies = freshMovies ?: state.movies
            val nextSeries = freshSeries ?: state.series
            val nextResume = freshResume ?: state.continueWatching

            val errorText =
                if (errors.isEmpty()) {
                    null
                } else {
                    val anchor = errors.first()
                    if (nextMovies.isEmpty() && nextSeries.isEmpty() && nextResume.isEmpty()) {
                        FindroidCarNetworkRetry.friendlyMessage(anchor)
                    } else {
                        FindroidCarNetworkRetry.staleCacheMessage(anchor)
                    }
                }

            state =
                state.copy(
                    loading = false,
                    errorMessage = errorText,
                    continueWatching = nextResume,
                    movies = nextMovies,
                    series = nextSeries,
                    downloads = offlineItems,
                )
            presentation?.setState(state)
        }
    }

    private suspend fun fetchOnlineMovies(): SectionOutcome<List<FindroidCarCatalogItem>> {
        if (!hasOnlineSession()) return SectionOutcome.Success(emptyList())
        return FindroidCarNetworkRetry.attempt("Browse:movies") {
                withContext(Dispatchers.IO) {
                    jellyfinRepository
                        .getItems(
                            includeTypes = listOf(BaseItemKind.MOVIE),
                            recursive = true,
                            sortBy = SortBy.NAME,
                            sortOrder = SortOrder.ASCENDING,
                            limit = MAX_ITEMS,
                        )
                        .mapNotNull {
                            it.toFindroidCarCatalogItemWithCachedArtwork(
                                carContext.filesDir,
                                jellyfinApi.api.accessToken,
                            )
                        }
                }
            }
            .toSectionOutcome()
    }

    private suspend fun fetchOnlineSeries(): SectionOutcome<List<FindroidCarCatalogItem>> {
        if (!hasOnlineSession()) return SectionOutcome.Success(emptyList())
        return FindroidCarNetworkRetry.attempt("Browse:series") {
                withContext(Dispatchers.IO) {
                    jellyfinRepository
                        .getItems(
                            includeTypes = listOf(BaseItemKind.SERIES),
                            recursive = true,
                            sortBy = SortBy.NAME,
                            sortOrder = SortOrder.ASCENDING,
                            limit = MAX_ITEMS,
                        )
                        .mapNotNull {
                            it.toFindroidCarCatalogItemWithCachedArtwork(
                                carContext.filesDir,
                                jellyfinApi.api.accessToken,
                            )
                        }
                }
            }
            .toSectionOutcome()
    }

    private suspend fun fetchContinueWatching(
        offlineItems: List<FindroidCarCatalogItem>,
    ): SectionOutcome<List<FindroidCarCatalogItem>> {
        val historyEntries = FindroidCarPlaybackHistory.loadEntries(carContext)
        val downloadable =
            offlineItems
                .filter { !it.videoPath.isNullOrBlank() }
                .takeIf { it.isNotEmpty() }
                .orEmpty()
        if (!hasOnlineSession()) {
            return SectionOutcome.Success(
                FindroidCarContinueWatchingResolver.resolve(
                    serverResumeItems = emptyList(),
                    offlineItems = downloadable,
                    historyEntries = historyEntries,
                    userDataByItemId = emptyMap(),
                    maxItems = 10,
                )
            )
        }
        return FindroidCarNetworkRetry.attempt("Browse:continueWatching") {
                withContext(Dispatchers.IO) {
                    val onlineResume = jellyfinRepository.getResumeItems()
                    val serverResumeItems =
                        onlineResume.mapNotNull {
                            it.toFindroidCarCatalogItem(carContext.filesDir)
                        }
                    FindroidCarContinueWatchingResolver.resolve(
                        serverResumeItems = serverResumeItems,
                        offlineItems = downloadable,
                        historyEntries = historyEntries,
                        userDataByItemId = emptyMap(),
                        maxItems = 10,
                    )
                }
            }
            .toSectionOutcome()
    }

    private sealed interface SectionOutcome<out T> {
        data class Success<T>(val value: T) : SectionOutcome<T>

        data class Failure(val failure: Throwable) : SectionOutcome<Nothing>

        val valueOrNull: T?
            get() = (this as? Success<T>)?.value

        val failureOrNull: Throwable?
            get() = (this as? Failure)?.failure
    }

    private fun <T> FindroidCarNetworkRetry.Status<T>.toSectionOutcome(): SectionOutcome<T> =
        when (this) {
            is FindroidCarNetworkRetry.Status.Success -> SectionOutcome.Success(value)
            is FindroidCarNetworkRetry.Status.Failure -> SectionOutcome.Failure(failure)
        }

    private fun Throwable.isCancellation(): Boolean =
        this is kotlinx.coroutines.CancellationException

    private suspend fun loadOfflineItems(): List<FindroidCarCatalogItem> {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return emptyList()
        val snapshots = offlinePackageRepository.getReadyItemSnapshotsByServerId(serverId)
        val carItems =
            snapshots.map { snapshot ->
                snapshot.toFindroidCarCatalogItem(
                    offlinePackageRepository.getPackage(snapshot.packageId)
                )
            }
        val movies = carItems.filter { it.itemKind == FindroidCarCatalogItemKind.MOVIE }
        val series = carItems.offlineSeriesItems()
        return (movies + series).sortedBy { it.title }
    }

    private fun hasOnlineSession(): Boolean {
        val hasServer = appPreferences.getValue(appPreferences.currentServer) != null
        val hasUser = jellyfinApi.userId != null
        val hasToken = !jellyfinApi.api.accessToken.isNullOrBlank()
        return hasServer && hasUser && hasToken
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

    private companion object {
        const val MAX_ITEMS = 60
    }
}
