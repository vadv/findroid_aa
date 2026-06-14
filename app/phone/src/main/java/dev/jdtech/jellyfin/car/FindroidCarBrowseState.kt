package dev.jdtech.jellyfin.car

internal enum class FindroidCarBrowseTab {
    HOME,
    MOVIES,
    SERIES,
    DOWNLOADS,
    SEARCH,
    ;

    val label: String
        get() =
            when (this) {
                HOME -> "Home"
                MOVIES -> "Movies"
                SERIES -> "Series"
                DOWNLOADS -> "Downloads"
                SEARCH -> "Search"
            }
}

internal data class FindroidCarBrowseState(
    val activeTab: FindroidCarBrowseTab = FindroidCarBrowseTab.HOME,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val continueWatching: List<FindroidCarCatalogItem> = emptyList(),
    val movies: List<FindroidCarCatalogItem> = emptyList(),
    val series: List<FindroidCarCatalogItem> = emptyList(),
    val downloads: List<FindroidCarCatalogItem> = emptyList(),
    val searchResults: List<FindroidCarCatalogItem> = emptyList(),
    val searchQuery: String = "",
)
