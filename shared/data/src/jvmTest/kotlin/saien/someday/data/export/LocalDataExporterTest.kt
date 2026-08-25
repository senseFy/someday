@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class LocalDataExporterTest {
    @Test
    fun exportUsesTheRequiredWorkspaceDagProviderAndDeclaresTheMediaBoundary() {
        val exportedAt = Instant.parse("2026-08-25T10:00:00Z")
        var providerTime: Instant? = null
        val expected = document(exportedAt)
        val json = LocalDataExporter(
            authoritativeDocumentProvider = { requestedAt ->
                providerTime = requestedAt
                expected
            },
            clock = { exportedAt },
        ).exportJson()

        val decoded = Json.decodeFromString<LocalDataExportDocument>(json)
        assertEquals(exportedAt, providerTime)
        assertEquals(expected, decoded)
        assertFalse(decoded.includesMediaBytes)
        assertTrue(decoded.assetReferencesMayBeUnresolved)
        assertTrue(decoded.formatDescription.contains("Image bytes are not included"))
        assertTrue(decoded.formatDescription.contains("may remain unresolved after restore"))
        assertTrue(decoded.notes.single().markdownBody.contains("someday-asset://"))
    }

    private fun document(exportedAt: Instant): LocalDataExportDocument =
        LocalDataExportDocument(
            exportedAt = exportedAt.toString(),
            notebooks = listOf(
                ExportedNotebook(
                    id = "notebook-1",
                    title = "Diary",
                    sortOrder = 1,
                    createdAt = exportedAt.toString(),
                    updatedAt = exportedAt.toString(),
                ),
            ),
            notes = listOf(
                ExportedNote(
                    id = "note-1",
                    notebookId = "notebook-1",
                    title = "Photo reference",
                    markdownBody = "![photo](someday-asset://${"ab".repeat(32)})",
                    excerpt = "photo",
                    createdAt = exportedAt.toString(),
                    updatedAt = exportedAt.toString(),
                    revision = 1,
                ),
            ),
        )
}
