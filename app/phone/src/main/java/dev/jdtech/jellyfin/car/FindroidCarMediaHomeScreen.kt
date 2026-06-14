package dev.jdtech.jellyfin.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
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

class FindroidCarMediaHomeScreen(
    carContext: CarContext,
    private val offlinePackageRepository: OfflinePackageRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeTabId = TAB_CONTINUE
    private var loading = true
    private var items: List<FindroidCarCatalogItem> = emptyList()
    private var errorMessage: String? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    loadItems()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            }
        )
    }

    override fun onGetTemplate(): Template {
        if (loading && items.isEmpty()) {
            return MessageTemplate.Builder("Загружаю…")
                .setTitle("Findroid Media")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        val content = buildListTemplate()
        return TabTemplate.Builder(
                object : TabTemplate.TabCallback {
                    override fun onTabSelected(tabContentId: String) {
                        if (activeTabId == tabContentId) return
                        activeTabId = tabContentId
                        loadItems()
                    }
                }
            )
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab(TAB_CONTINUE, "Continue", CoreR.drawable.ic_play))
            .addTab(tab(TAB_MOVIES, "Movies", CoreR.drawable.ic_film))
            .addTab(tab(TAB_SERIES, "Series", CoreR.drawable.ic_tv))
            .addTab(tab(TAB_OFFLINE, "Offline", CoreR.drawable.ic_download))
            .setActiveTabContentId(activeTabId)
            .setTabContents(TabContents.Builder(content).build())
            .build()
    }

    private fun loadItems() {
        scope.launch {
            loading = true
            errorMessage = null
            invalidate()
            val status =
                FindroidCarNetworkRetry.attempt("MediaHome:$activeTabId") {
                    withContext(Dispatchers.IO) {
                        when (activeTabId) {
                            TAB_CONTINUE -> loadContinueWatching()
                            TAB_MOVIES -> loadOnlineMovies()
                            TAB_SERIES -> loadOnlineSeries()
                            TAB_OFFLINE -> loadOfflineItems()
                            else -> emptyList()
                        }
                    }
                }
            when (status) {
                is FindroidCarNetworkRetry.Status.Success -> {
                    items = status.value
                    loading = false
                    errorMessage = null
                }
                is FindroidCarNetworkRetry.Status.Failure -> {
                    loading = false
                    errorMessage =
                        if (items.isNotEmpty()) {
                            FindroidCarNetworkRetry.staleCacheMessage(status.failure)
                        } else {
                            FindroidCarNetworkRetry.friendlyMessage(status.failure)
                        }
                }
            }
            invalidate()
        }
    }

    private suspend fun loadContinueWatching(): List<FindroidCarCatalogItem> {
        val offlineItems =
            appPreferences
                .getValue(appPreferences.currentServer)
                ?.let { offlinePackageRepository.getReadyItemSnapshotsByServerId(it) }
                .orEmpty()
                .map { snapshot ->
                    snapshot.toFindroidCarCatalogItem(
                        offlinePackageRepository.getPackage(snapshot.packageId)
                    )
                }
                .filter { !it.videoPath.isNullOrBlank() }
        val onlineResume =
            runCatching { jellyfinRepository.getResumeItems() }.getOrDefault(emptyList())
        val serverResumeItems =
            onlineResume.mapNotNull { it.toFindroidCarCatalogItem(carContext.filesDir) }
        val history = FindroidCarPlaybackHistory.loadEntries(carContext)
        return FindroidCarContinueWatchingResolver.resolve(
            serverResumeItems = serverResumeItems,
            offlineItems = offlineItems,
            historyEntries = history,
            userDataByItemId = emptyMap(),
            maxItems = MAX_ROWS,
        )
    }

    private suspend fun loadOnlineMovies(): List<FindroidCarCatalogItem> {
        if (!hasOnlineSession()) return emptyList()
        return jellyfinRepository
            .getItems(
                includeTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                sortBy = SortBy.NAME,
                sortOrder = SortOrder.ASCENDING,
                limit = MAX_ROWS,
            )
            .mapNotNull { it.toFindroidCarCatalogItem(carContext.filesDir) }
    }

    private suspend fun loadOnlineSeries(): List<FindroidCarCatalogItem> {
        if (!hasOnlineSession()) return emptyList()
        return jellyfinRepository
            .getItems(
                includeTypes = listOf(BaseItemKind.SERIES),
                recursive = true,
                sortBy = SortBy.NAME,
                sortOrder = SortOrder.ASCENDING,
                limit = MAX_ROWS,
            )
            .mapNotNull { it.toFindroidCarCatalogItem(carContext.filesDir) }
    }

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

    private fun buildListTemplate(): ListTemplate {
        val builder = ItemList.Builder().setNoItemsMessage(noItemsMessage())
        errorMessage?.let { msg -> builder.addItem(Row.Builder().setTitle(msg).build()) }
        items.take(MAX_ROWS).forEach { item ->
            val artwork = FindroidCarArtwork.iconFor(item.artworkPaths)
            builder.addItem(
                Row.Builder()
                    .setTitle(item.title)
                    .apply {
                        artwork?.let { setImage(it, Row.IMAGE_TYPE_LARGE) }
                        if (item.subtitle.isNotBlank()) addText(item.subtitle)
                        item.runtimeText.takeIf { it.isNotBlank() }?.let { addText(it) }
                    }
                    .setBrowsable(true)
                    .setOnClickListener { openItem(item) }
                    .build()
            )
        }
        return ListTemplate.Builder().setSingleList(builder.build()).build()
    }

    private fun openItem(item: FindroidCarCatalogItem) {
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        when (item.itemKind) {
            FindroidCarCatalogItemKind.SERIES ->
                if (item.packageId.startsWith("offline-series")) {
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
                } else {
                    screenManager.push(
                        FindroidCarSeriesScreen(
                            carContext = carContext,
                            series = item,
                            jellyfinRepository = jellyfinRepository,
                            jellyfinApi = jellyfinApi,
                        )
                    )
                }
            else ->
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

    private fun noItemsMessage(): String =
        when (activeTabId) {
            TAB_CONTINUE -> "Nothing in progress"
            TAB_MOVIES -> "No movies"
            TAB_SERIES -> "No series"
            TAB_OFFLINE -> "No downloads"
            else -> "Empty"
        }

    private fun tab(contentId: String, title: String, iconRes: Int): Tab =
        Tab.Builder()
            .setContentId(contentId)
            .setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes)).build())
            .build()

    private companion object {
        const val MAX_ROWS = 60
        const val TAB_CONTINUE = "continue"
        const val TAB_MOVIES = "movies"
        const val TAB_SERIES = "series"
        const val TAB_OFFLINE = "offline"
    }
}
