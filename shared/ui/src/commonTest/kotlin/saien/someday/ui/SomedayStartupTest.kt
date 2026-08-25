package saien.someday.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SomedayStartupTest {
    @Test
    fun sharedUiStartupUsesMaterial3AndThreeTabs() {
        val startup = sharedUiStartupSemantics("shared-test")

        assertEquals("Material3", startup.designSystem)
        assertEquals(listOf("Notes", "Memories", "Settings"), startup.tabLabels)
        assertEquals("SomedayApp", startup.startupEntry)
    }

    @Test
    fun sharedUiStartupLogIsSmokeFriendly() {
        val log = sharedUiStartupLog("shared-test")

        println(log)
        assertTrue(log.contains("platform=shared-test"))
        assertTrue(log.contains("shared-ui=shared:ui"))
        assertTrue(log.contains("tabs=Notes|Memories|Settings"))
        assertTrue(log.contains("notes-reclick=stays-on-notes"))
        assertTrue(log.contains("add-entry=new-note"))
        assertTrue(log.contains("settings=local-persistent"))
        assertTrue(log.contains("memories=calendar-counts|month-navigation|selected-day|prior-year"))
        assertTrue(log.contains("location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk"))
        assertTrue(log.contains("platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence"))
        assertTrue(log.contains("search=local-title-body-active-only"))
    }
}
