package dev.jdtech.jellyfin.car

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FindroidCarCatalogItemPlayerTitleTest {

    @Test
    fun episodeTitleJoinsSeasonAndEpisodePrefix() {
        val item =
            sampleEpisode(
                indexNumber = 1,
                parentIndexNumber = 22,
                title = "E01 - Aw Rats, A Pool Party",
            )

        assertEquals("S22:E1 — Aw Rats, A Pool Party", item.displayTitleForPlayer())
    }

    @Test
    fun episodeTitleFallsBackToTitleWhenPrefixMissing() {
        val item =
            sampleEpisode(indexNumber = null, parentIndexNumber = null, title = "Pilot")
        assertEquals("Pilot", item.displayTitleForPlayer())
    }

    @Test
    fun movieTitleReturnedAsIs() {
        val item =
            FindroidCarCatalogItem(
                packageId = "online:abc",
                itemId = "abc",
                itemKind = FindroidCarCatalogItemKind.MOVIE,
                playerItemKind = BaseItemKind.MOVIE.serialName,
                seriesId = null,
                seriesName = null,
                seasonId = null,
                seasonName = null,
                indexNumber = null,
                parentIndexNumber = null,
                title = "Dune: Part Two",
                subtitle = "2024",
                runtimeText = "165 min",
                runtimeTicks = 0,
                artworkPaths = emptyList(),
                videoPath = null,
                streamUrl = null,
                played = false,
                favorite = false,
                playbackPositionTicks = 0,
                unplayedItemCount = null,
            )
        assertEquals("Dune: Part Two", item.displayTitleForPlayer())
    }

    private fun sampleEpisode(
        indexNumber: Int?,
        parentIndexNumber: Int?,
        title: String,
    ): FindroidCarCatalogItem =
        FindroidCarCatalogItem(
            packageId = "online:ep",
            itemId = "ep",
            itemKind = FindroidCarCatalogItemKind.EPISODE,
            playerItemKind = BaseItemKind.EPISODE.serialName,
            seriesId = "series",
            seriesName = "American Dad!",
            seasonId = "season",
            seasonName = "Season 22",
            indexNumber = indexNumber,
            parentIndexNumber = parentIndexNumber,
            title = title,
            subtitle = "American Dad! / Season 22",
            runtimeText = "22 min",
            runtimeTicks = 13_200_000_000L,
            artworkPaths = emptyList(),
            videoPath = null,
            streamUrl = null,
            played = false,
            favorite = false,
            playbackPositionTicks = 0,
            unplayedItemCount = null,
        )
}
