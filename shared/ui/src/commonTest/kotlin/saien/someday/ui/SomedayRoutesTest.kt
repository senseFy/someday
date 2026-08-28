package saien.someday.ui

import saien.someday.domain.navigation.PrimaryTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SomedayRoutesTest {
    @Test
    fun routeKindsMapToPrimaryTabsAndPageTraits() {
        assertEquals(PrimaryTab.Notes, SomedayRouteKind.Notes.primaryTab)
        assertEquals(PrimaryTab.Notes, SomedayRouteKind.NotesSearch.primaryTab)
        assertEquals(PrimaryTab.Notes, SomedayRouteKind.NoteEditor.primaryTab)
        assertEquals(PrimaryTab.Notes, SomedayRouteKind.NoteConflictResolution.primaryTab)
        assertEquals(PrimaryTab.Memories, SomedayRouteKind.Memories.primaryTab)
        assertEquals(PrimaryTab.Settings, SomedayRouteKind.Settings.primaryTab)
        assertEquals(PrimaryTab.Settings, SomedayRouteKind.SettingsDetail.primaryTab)

        assertTrue(SomedayRouteKind.NoteEditor.showsNoteEditor)
        assertTrue(SomedayRouteKind.NoteConflictResolution.showsNoteEditor)
        assertFalse(SomedayRouteKind.Notes.showsNoteEditor)
        assertTrue(SomedayRouteKind.NotesSearch.showsNotesSearch)
        assertFalse(SomedayRouteKind.Settings.showsNotesSearch)
    }

    @Test
    fun settingsPagesUseStableRouteIds() {
        for (page in SettingsPage.entries) {
            assertEquals(page, settingsPageFromRouteId(page.routeId))
        }
        assertNull(settingsPageFromRouteId("self_hosted"))
        assertNull(settingsPageFromRouteId("privacy"))
        assertNull(settingsPageFromRouteId("missing"))
    }
}
