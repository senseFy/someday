package saien.someday.domain.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientShellSemanticsTest {
    @Test
    fun primaryNavigationIsSharedAndStable() {
        val semantics = clientShellSemanticsFor("test")

        assertEquals(listOf("Notes", "Memories", "Settings"), semantics.tabLabels)
        assertEquals("shared:ui", semantics.sharedUiModule)
        assertEquals("SomedayApp", semantics.startupEntry)
        assertEquals("Material3", semantics.designSystem)
        assertEquals("stays-on-notes", semantics.notesTabReselectBehavior)
        assertEquals("new-note", semantics.persistentAddEntry)
        assertEquals("local-persistent", semantics.settingsState)
    }

    @Test
    fun smokeLogContainsValidationEvidence() {
        val log = clientShellSemanticsFor("desktop").smokeLog()

        assertTrue(log.contains("platform=desktop"))
        assertTrue(log.contains("shared-ui=shared:ui"))
        assertTrue(log.contains("startup=SomedayApp"))
        assertTrue(log.contains("material=Material3"))
        assertTrue(log.contains("tabs=Notes|Memories|Settings"))
        assertTrue(log.contains("notes-reclick=stays-on-notes"))
        assertTrue(log.contains("add-entry=new-note"))
        assertTrue(log.contains("settings=local-persistent"))
    }
}
