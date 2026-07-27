package saien.someday.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFirstBoundaryTest {
    @Test
    fun dataModuleNamesRequiredFutureTables() {
        val boundary = LocalFirstBoundary()

        assertEquals("SQLDelight/SQLite", boundary.storageEngine)
        assertFalse(boundary.localWritesRequireNetwork)
        assertTrue(requiredLocalTables.containsAll(listOf("notebooks", "notes", "note_versions", "tombstones")))
        assertTrue(boundary.smokeDescription().contains("offlineWritesRequireNetwork=false"))
    }
}
