@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.local

import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.notes.MemoryMonth
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightMemoriesSearchRepositoryTest {
    @Test
    fun memoriesCountsSelectedDayAndPriorYearsComeFromActiveLocalNotesOnly() =
        withFixture { repository ->
            val notebook = repository.createNotebook("Diary")
            val selected = repository.createNote(
                notebookId = notebook.id,
                title = "Selected day",
                markdownBody = "A current memory",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.createNote(
                notebookId = notebook.id,
                title = "Second selected day",
                markdownBody = "Another current memory",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.createNote(
                notebookId = notebook.id,
                title = "Another day",
                markdownBody = "Counts separately",
                createdAt = Instant.parse("2026-05-23T00:00:00Z"),
            )
            val priorYear = repository.createNote(
                notebookId = notebook.id,
                title = "Prior year",
                markdownBody = "Same month and day from last year",
                createdAt = Instant.parse("2025-05-22T00:00:00Z"),
            )
            val deleted = repository.createNote(
                notebookId = notebook.id,
                title = "Deleted same day",
                markdownBody = "Must not count",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.deleteNote(deleted.id)

            val counts = repository.listMemoryDayCounts(MemoryMonth(2026, 5))
            val selectedDayNotes = repository.listActiveNotesForDate(LocalDate(2026, 5, 22))
            val priorYearNotes = repository.listPriorYearSameDayNotes(LocalDate(2026, 5, 22))

            assertEquals(
                mapOf(
                    LocalDate(2026, 5, 22) to 2,
                    LocalDate(2026, 5, 23) to 1,
                ),
                counts.associate { it.date to it.noteCount },
            )
            assertEquals(
                listOf("Second selected day", "Selected day"),
                selectedDayNotes.map { it.title },
            )
            assertEquals(listOf(priorYear.id), priorYearNotes.map { it.id })
            assertTrue(selectedDayNotes.none { it.id == deleted.id })
            assertTrue(priorYearNotes.none { it.id == selected.id })
        }

    @Test
    fun localSearchMatchesTitleAndBodyButExcludesTombstonedNotes() =
        withFixture { repository ->
            val notebook = repository.createNotebook("Diary")
            val titleMatch = repository.createNote(
                notebookId = notebook.id,
                title = "Kyoto breakfast",
                markdownBody = "Quiet morning",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val bodyMatch = repository.createNote(
                notebookId = notebook.id,
                title = "Train ride",
                markdownBody = "Lunch near the bamboo grove in Arashiyama",
                createdAt = Instant.parse("2026-05-23T00:00:00Z"),
            )
            val deletedMatch = repository.createNote(
                notebookId = notebook.id,
                title = "Kyoto deleted",
                markdownBody = "A tombstone should remain for sync",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            )
            repository.deleteNote(deletedMatch.id)

            assertEquals(listOf(titleMatch.id), repository.searchActiveNotes("kyoto").map { it.id })
            assertEquals(listOf(bodyMatch.id), repository.searchActiveNotes("BAMBOO").map { it.id })
            assertTrue(repository.searchActiveNotes("tombstone").none { it.id == deletedMatch.id })
            assertTrue(repository.searchActiveNotes("   ").isEmpty())
        }

    private fun withFixture(block: (SqlDelightLocalDataRepository) -> Unit) {
        val dbPath = Files.createTempFile("someday-memories-search-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val repository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = "test-device",
            clock = { Instant.fromEpochMilliseconds(1_000) },
            idGenerator = SequentialTestIdGenerator(),
        )

        try {
            block(repository)
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }

    private class SequentialTestIdGenerator : LocalIdGenerator {
        private var next = 0

        override fun newId(prefix: String): String {
            next += 1
            return "$prefix-$next"
        }
    }
}
