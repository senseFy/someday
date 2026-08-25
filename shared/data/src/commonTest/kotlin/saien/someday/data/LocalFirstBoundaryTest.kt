package saien.someday.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFirstBoundaryTest {
    @Test
    fun dataModuleNamesTheDagOnlyProductBoundary() {
        val boundary = LocalFirstBoundary()

        assertEquals("SQLDelight/SQLite", boundary.storageEngine)
        assertFalse(boundary.localWritesRequireNetwork)
        assertTrue(requiredLocalTables.containsAll(listOf("workspace_entity_versions_v2", "media_assets", "settings", "devices")))
        assertFalse(requiredLocalTables.any { it in setOf("notebooks", "notes", "note_versions", "tombstones", "locations", "sync_metadata") })
        assertTrue(boundary.smokeDescription().contains("offlineWritesRequireNetwork=false"))
    }
}
