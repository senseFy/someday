package saien.someday.domain.navigation

enum class PrimaryTab(val label: String) {
    Notes("Notes"),
    Memories("Memories"),
    Settings("Settings"),
}

val primaryNavigationTabs: List<PrimaryTab> = PrimaryTab.entries
