package saien.someday.ui

import saien.someday.domain.navigation.PrimaryTab
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("notes")
internal data object NotesRoute

@Serializable
@SerialName("notes_search")
internal data object NotesSearchRoute

@Serializable
@SerialName("note_editor")
internal data class NoteEditorRoute(
    val noteId: String? = null,
    val notebookId: String? = null,
)

@Serializable
@SerialName("note_conflict")
internal data class NoteConflictResolutionRoute(
    val conflictNoteId: String,
)

@Serializable
@SerialName("memories")
internal data object MemoriesRoute

@Serializable
@SerialName("settings")
internal data object SettingsRoute

@Serializable
@SerialName("settings_detail")
internal data class SettingsDetailRoute(
    val pageId: String,
)

internal enum class SettingsPage {
    Appearance,
    Editor,
    Notifications,
    Sync,
    SelfHosted,
    Import,
    Export,
    Developer,
}

internal val SettingsPage.routeId: String
    get() = when (this) {
        SettingsPage.Appearance -> "appearance"
        SettingsPage.Editor -> "editor"
        SettingsPage.Notifications -> "notifications"
        SettingsPage.Sync -> "sync"
        SettingsPage.SelfHosted -> "self_hosted"
        SettingsPage.Import -> "import"
        SettingsPage.Export -> "export"
        SettingsPage.Developer -> "developer"
    }

internal fun settingsPageFromRouteId(routeId: String): SettingsPage? =
    when (routeId) {
        SettingsPage.Appearance.routeId -> SettingsPage.Appearance
        SettingsPage.Editor.routeId -> SettingsPage.Editor
        SettingsPage.Notifications.routeId -> SettingsPage.Notifications
        SettingsPage.Sync.routeId -> SettingsPage.Sync
        SettingsPage.SelfHosted.routeId -> SettingsPage.SelfHosted
        SettingsPage.Import.routeId -> SettingsPage.Import
        SettingsPage.Export.routeId -> SettingsPage.Export
        SettingsPage.Developer.routeId -> SettingsPage.Developer
        else -> null
    }

internal enum class SomedayRouteKind {
    Notes,
    NotesSearch,
    NoteEditor,
    NoteConflictResolution,
    Memories,
    Settings,
    SettingsDetail,
}

internal val SomedayRouteKind.primaryTab: PrimaryTab
    get() = when (this) {
        SomedayRouteKind.Notes -> PrimaryTab.Notes
        SomedayRouteKind.NotesSearch -> PrimaryTab.Notes
        SomedayRouteKind.NoteEditor -> PrimaryTab.Notes
        SomedayRouteKind.NoteConflictResolution -> PrimaryTab.Notes
        SomedayRouteKind.Memories -> PrimaryTab.Memories
        SomedayRouteKind.Settings -> PrimaryTab.Settings
        SomedayRouteKind.SettingsDetail -> PrimaryTab.Settings
    }

internal val SomedayRouteKind.showsNoteEditor: Boolean
    get() = this == SomedayRouteKind.NoteEditor || this == SomedayRouteKind.NoteConflictResolution

internal val SomedayRouteKind.showsNotesSearch: Boolean
    get() = this == SomedayRouteKind.NotesSearch
