package saien.someday.domain.client

import saien.someday.domain.navigation.PrimaryTab
import saien.someday.domain.navigation.primaryNavigationTabs

data class ClientShellSemantics(
    val platform: String,
    val sharedUiModule: String = "shared:ui",
    val startupEntry: String = "SomedayApp",
    val designSystem: String = "Material3",
    val tabs: List<PrimaryTab> = primaryNavigationTabs,
    val notesTabReselectBehavior: String = "stays-on-notes",
    val persistentAddEntry: String = "new-note",
    val settingsState: String = "local-persistent",
) {
    val tabLabels: List<String> = tabs.map { it.label }

    init {
        require(platform.isNotBlank()) { "Platform name must be present for smoke evidence." }
        require(tabLabels == listOf("Notes", "Memories", "Settings")) {
            "Primary navigation must expose exactly Notes, Memories, and Settings."
        }
    }

    fun smokeLog(): String =
        "platform=$platform shared-ui=$sharedUiModule startup=$startupEntry material=$designSystem " +
            "tabs=${tabLabels.joinToString("|")} notes-reclick=$notesTabReselectBehavior " +
            "add-entry=$persistentAddEntry settings=$settingsState"
}

fun clientShellSemanticsFor(platform: String): ClientShellSemantics = ClientShellSemantics(platform = platform)
