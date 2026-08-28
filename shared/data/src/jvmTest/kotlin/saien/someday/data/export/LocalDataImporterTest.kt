@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class LocalDataImporterTest {
    @Test
    fun importJsonDelegatesTheDecodedDocumentToTheRequiredWorkspaceDagImporter() {
        val document = emptyDocument()
        val json = LocalDataExporter(
            authoritativeDocumentProvider = { document },
            clock = { Instant.parse(document.exportedAt) },
        ).exportJson()
        var imported: LocalDataExportDocument? = null
        val expected = LocalDataImportSummary(
            notebooksCreated = 1,
            notebooksReused = 0,
            notesCreated = 2,
            notesSkipped = 0,
        )

        val actual = LocalDataImporter { decoded ->
            imported = decoded
            expected
        }.importJson(json)

        assertEquals(expected, actual)
        assertEquals(document, imported)
    }

    @Test
    fun unsupportedFormatIsRejectedBeforeTheWorkspaceDagImporterRuns() {
        var imported: LocalDataExportDocument? = null
        val importer = LocalDataImporter { document ->
            imported = document
            error("must not run")
        }

        assertFailsWith<IllegalArgumentException> {
            importer.importDocument(emptyDocument().copy(format = "unknown"))
        }
        assertNull(imported)
    }

    private fun emptyDocument(): LocalDataExportDocument =
        LocalDataExportDocument(
            exportedAt = "2026-08-25T10:00:00Z",
            notebooks = emptyList(),
            notes = emptyList(),
        )
}
