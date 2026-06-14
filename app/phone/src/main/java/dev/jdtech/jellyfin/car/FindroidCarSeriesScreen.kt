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
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FindroidCarSeriesScreen(
    carContext: CarContext,
    private val series: FindroidCarCatalogItem,
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading = true
    private var seasons: List<FindroidCarCatalogItem> = emptyList()
    private var errorMessage: String? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    loadSeasons()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            }
        )
    }

    override fun onGetTemplate(): Template {
        if (loading && seasons.isEmpty()) {
            return MessageTemplate.Builder("Загружаю сезоны…")
                .setTitle(series.title)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        if (seasons.isEmpty()) {
            errorMessage?.let { message ->
                return MessageTemplate.Builder(message)
                    .setTitle(series.title)
                    .setHeaderAction(Action.BACK)
                    .build()
            }
        }

        val listBuilder = ItemList.Builder().setNoItemsMessage("No seasons")
        listBuilder.addItem(backRow())
        statusRow(loading = loading, message = errorMessage)?.let(listBuilder::addItem)
        seasons.take(MAX_SEASON_ROWS).forEach { season ->
            val artwork = FindroidCarArtwork.iconFor(season.artworkPaths)
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(season.title)
                    .apply {
                        artwork?.let { setImage(it, Row.IMAGE_TYPE_LARGE) }
                        if (season.subtitle.isNotBlank()) addText(season.subtitle)
                        season.watchStatusText().takeIf { it.isNotBlank() }?.let { addText(it) }
                    }
                    .setBrowsable(true)
                    .setOnClickListener {
                        carContext
                            .getCarService(ScreenManager::class.java)
                            .push(
                                FindroidCarSeasonScreen(
                                    carContext = carContext,
                                    series = series,
                                    season = season,
                                    jellyfinRepository = jellyfinRepository,
                                    jellyfinApi = jellyfinApi,
                                )
                            )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(series.title)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun loadSeasons() {
        scope.launch {
            loading = true
            if (seasons.isEmpty()) errorMessage = null
            invalidate()

            val status =
                FindroidCarNetworkRetry.attempt("Series:${series.itemId}:seasons") {
                    withContext(Dispatchers.IO) {
                        val seriesId = UUID.fromString(series.itemId)
                        jellyfinRepository
                            .getSeasons(seriesId)
                            .sortedBy { it.indexNumber }
                            .mapNotNull { season ->
                                season.toFindroidCarCatalogItemWithCachedArtwork(
                                    carContext.filesDir,
                                    jellyfinApi.api.accessToken,
                                ) ?: season.toFindroidCarCatalogItem(carContext.filesDir)
                            }
                    }
                }
            when (status) {
                is FindroidCarNetworkRetry.Status.Success -> {
                    seasons = status.value
                    errorMessage = null
                }
                is FindroidCarNetworkRetry.Status.Failure -> {
                    errorMessage =
                        if (seasons.isNotEmpty()) {
                            FindroidCarNetworkRetry.staleCacheMessage(status.failure)
                        } else {
                            FindroidCarNetworkRetry.friendlyMessage(status.failure)
                        }
                }
            }
            loading = false
            invalidate()
        }
    }

    private fun backRow(): Row =
        Row.Builder()
            .setTitle("Back to series")
            .setImage(
                CarIcon.Builder(IconCompat.createWithResource(carContext, CoreR.drawable.ic_arrow_left))
                    .build(),
                Row.IMAGE_TYPE_ICON,
            )
            .setOnClickListener {
                carContext.getCarService(ScreenManager::class.java).pop()
            }
            .build()

    private fun statusRow(loading: Boolean, message: String?): Row? {
        val text =
            when {
                loading -> "Обновление…"
                message != null -> message
                else -> return null
            }
        return Row.Builder().setTitle(text).build()
    }

    private companion object {
        const val MAX_SEASON_ROWS = 100
    }
}
