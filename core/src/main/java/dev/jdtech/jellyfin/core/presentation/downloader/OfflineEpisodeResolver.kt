package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import java.util.UUID

internal fun interface SeasonEpisodesFetcher {
    suspend operator fun invoke(seriesId: UUID, seasonId: UUID): List<FindroidEpisode>
}

internal fun interface SeriesSeasonsFetcher {
    suspend operator fun invoke(seriesId: UUID): List<FindroidSeason>
}

internal object OfflineEpisodeResolver {
    suspend fun resolve(
        item: FindroidItem,
        episodesForSeason: SeasonEpisodesFetcher,
        seasonsForSeries: SeriesSeasonsFetcher,
    ): List<FindroidEpisode> =
        when (item) {
            is FindroidEpisode -> listOf(item)
            is FindroidSeason -> episodesForSeason(item.seriesId, item.id)
            is FindroidShow ->
                seasonsForSeries(item.id).flatMap { season ->
                    episodesForSeason(item.id, season.id)
                }
            else -> emptyList()
        }.filterNot { it.missing }
}
