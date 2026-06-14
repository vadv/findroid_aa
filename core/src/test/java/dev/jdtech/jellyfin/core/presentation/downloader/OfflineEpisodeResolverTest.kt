package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineEpisodeResolverTest {
    @Test
    fun seasonDownloadResolvesOnlyEpisodesForSelectedSeason() = runBlocking {
        val seriesId = UUID.fromString("00000000-0000-0000-0000-0000000000a0")
        val seasonOneId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val seasonTwoId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val seasonOneEpisodes =
            listOf(
                episode(id = "11", seriesId = seriesId, seasonId = seasonOneId),
                episode(id = "12", seriesId = seriesId, seasonId = seasonOneId),
                episode(id = "13", seriesId = seriesId, seasonId = seasonOneId),
            )
        val episodesQueries = mutableListOf<Pair<UUID, UUID>>()
        val seasonsQueries = mutableListOf<UUID>()

        val resolved =
            OfflineEpisodeResolver.resolve(
                item = season(seriesId = seriesId, seasonId = seasonOneId),
                episodesForSeason = { qSeriesId, qSeasonId ->
                    episodesQueries += qSeriesId to qSeasonId
                    when (qSeasonId) {
                        seasonOneId -> seasonOneEpisodes
                        else -> error("Resolver must not request siblings of season under download")
                    }
                },
                seasonsForSeries = { qSeriesId ->
                    seasonsQueries += qSeriesId
                    error("Resolver must not enumerate sibling seasons for a season download")
                },
            )

        assertEquals(3, resolved.size)
        assertEquals(listOf(seriesId to seasonOneId), episodesQueries)
        assertEquals(emptyList<UUID>(), seasonsQueries)
        assertEquals(seasonOneEpisodes.map { it.id }, resolved.map { it.id })
        // The sibling season's id was never queried — ensure tests check distinct ids.
        assertNull(resolved.firstOrNull { it.seasonId == seasonTwoId })
    }

    @Test
    fun seasonDownloadFiltersOutMissingEpisodes() = runBlocking {
        val seriesId = UUID.fromString("00000000-0000-0000-0000-0000000000c0")
        val seasonId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val mixed =
            listOf(
                episode(id = "21", seriesId = seriesId, seasonId = seasonId, missing = false),
                episode(id = "22", seriesId = seriesId, seasonId = seasonId, missing = true),
                episode(id = "23", seriesId = seriesId, seasonId = seasonId, missing = false),
            )

        val resolved =
            OfflineEpisodeResolver.resolve(
                item = season(seriesId = seriesId, seasonId = seasonId),
                episodesForSeason = { _, _ -> mixed },
                seasonsForSeries = { error("Should not enumerate seasons") },
            )

        assertEquals(listOf("21", "23").map(::idFromShortId), resolved.map { it.id })
    }

    @Test
    fun showDownloadEnumeratesAllSeasonsAndCollectsEpisodes() = runBlocking {
        val seriesId = UUID.fromString("00000000-0000-0000-0000-0000000000d0")
        val seasonOneId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
        val seasonTwoId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
        val episodesQueries = mutableListOf<Pair<UUID, UUID>>()

        val resolved =
            OfflineEpisodeResolver.resolve(
                item = show(id = seriesId),
                episodesForSeason = { qSeriesId, qSeasonId ->
                    episodesQueries += qSeriesId to qSeasonId
                    when (qSeasonId) {
                        seasonOneId ->
                            listOf(episode(id = "31", seriesId = seriesId, seasonId = seasonOneId))
                        seasonTwoId ->
                            listOf(episode(id = "32", seriesId = seriesId, seasonId = seasonTwoId))
                        else -> emptyList()
                    }
                },
                seasonsForSeries = { qSeriesId ->
                    assertEquals(seriesId, qSeriesId)
                    listOf(
                        season(seriesId = seriesId, seasonId = seasonOneId, indexNumber = 1),
                        season(seriesId = seriesId, seasonId = seasonTwoId, indexNumber = 2),
                    )
                },
            )

        assertEquals(2, resolved.size)
        assertEquals(
            listOf(
                seriesId to seasonOneId,
                seriesId to seasonTwoId,
            ),
            episodesQueries,
        )
    }

    @Test
    fun episodeDownloadShortCircuitsAndDoesNotHitRepository() = runBlocking {
        val seriesId = UUID.fromString("00000000-0000-0000-0000-0000000000e0")
        val seasonId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
        val episode = episode(id = "41", seriesId = seriesId, seasonId = seasonId)

        val resolved =
            OfflineEpisodeResolver.resolve(
                item = episode,
                episodesForSeason = { _, _ ->
                    error("Single-episode resolution must not call episodesForSeason")
                },
                seasonsForSeries = { _ ->
                    error("Single-episode resolution must not call seasonsForSeries")
                },
            )

        assertEquals(listOf(episode), resolved)
    }

    private fun idFromShortId(short: String): UUID =
        UUID.fromString("00000000-0000-0000-0000-${short.padStart(12, '0')}")

    private fun episode(
        id: String,
        seriesId: UUID,
        seasonId: UUID,
        missing: Boolean = false,
    ): FindroidEpisode =
        FindroidEpisode(
            id = idFromShortId(id),
            name = "Episode $id",
            originalTitle = null,
            overview = "",
            indexNumber = id.toInt(),
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "Series",
            seasonId = seasonId,
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            missing = missing,
            images = FindroidImages(),
            chapters = emptyList<FindroidChapter>(),
            trickplayInfo = null,
        )

    private fun season(
        seriesId: UUID,
        seasonId: UUID,
        indexNumber: Int = 1,
    ): FindroidSeason =
        FindroidSeason(
            id = seasonId,
            name = "Season $indexNumber",
            seriesId = seriesId,
            seriesName = "Series",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = indexNumber,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = null,
            images = FindroidImages(),
        )

    private fun show(id: UUID): FindroidShow =
        FindroidShow(
            id = id,
            name = "Series",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            playbackPositionTicks = 0L,
            unplayedItemCount = null,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Continuing",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
        )
}
