package saien.someday.ui.notifications

import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notifications.findNextOnThisDayFireAt
import saien.someday.domain.notifications.findUpcomingOnThisDayOccurrences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.ui.notes.InMemoryNotesRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class OnThisDayScheduleTest {
    @Test
    fun findsTodayWhenPriorYearNotesExistAndTimeHasNotPassed() {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = notebook.id,
            title = "Last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 22),
        )
        val preferences = OnThisDayNotificationPreferences(enabled = true, hour = 10, minute = 0)
        val now = Instant.parse("2026-05-22T01:00:00Z")
        val fireAt = findNextOnThisDayFireAt(
            notesRepository = repository,
            preferences = preferences,
            now = now,
            timeZone = TimeZone.UTC,
        )
        assertEquals(
            LocalDateTime(2026, 5, 22, 10, 0).toInstant(TimeZone.UTC),
            fireAt,
        )
    }

    @Test
    fun skipsTodayWhenNotificationTimeAlreadyPassed() {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = notebook.id,
            title = "Last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 22),
        )
        repository.seedNote(
            notebookId = notebook.id,
            title = "Tomorrow last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 23),
        )
        val preferences = OnThisDayNotificationPreferences(enabled = true, hour = 10, minute = 0)
        val now = Instant.parse("2026-05-22T12:00:00Z")
        val fireAt = findNextOnThisDayFireAt(
            notesRepository = repository,
            preferences = preferences,
            now = now,
            timeZone = TimeZone.UTC,
        )
        assertEquals(
            LocalDateTime(2026, 5, 23, 10, 0).toInstant(TimeZone.UTC),
            fireAt,
        )
    }

    @Test
    fun includesCandidateExactly366DaysAhead() {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = notebook.id,
            title = "Earlier leap-cycle memory",
            markdownBody = "memory",
            createdDate = LocalDate(2026, 3, 1),
        )

        val fireAt = findNextOnThisDayFireAt(
            notesRepository = repository,
            preferences = OnThisDayNotificationPreferences(enabled = true, hour = 10, minute = 0),
            now = Instant.parse("2027-03-01T12:00:00Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(
            LocalDateTime(2028, 3, 1, 10, 0).toInstant(TimeZone.UTC),
            fireAt,
        )
    }

    @Test
    fun findsMultipleUpcomingNotificationDates() {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = notebook.id,
            title = "Today last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 22),
        )
        repository.seedNote(
            notebookId = notebook.id,
            title = "Tomorrow last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 23),
        )

        val occurrences = findUpcomingOnThisDayOccurrences(
            notesRepository = repository,
            preferences = OnThisDayNotificationPreferences(enabled = true, hour = 10, minute = 0),
            now = Instant.parse("2026-05-22T01:00:00Z"),
            timeZone = TimeZone.UTC,
            searchDays = 2,
            limit = 2,
        )

        assertEquals(
            listOf(
                LocalDateTime(2026, 5, 22, 10, 0).toInstant(TimeZone.UTC),
                LocalDateTime(2026, 5, 23, 10, 0).toInstant(TimeZone.UTC),
            ),
            occurrences.map { it.fireAt },
        )
        assertEquals(listOf(1, 1), occurrences.map { it.memoryCount })
    }

    @Test
    fun readsActiveNoteDatesOnceForTheEntireSearchWindow() {
        val source = InMemoryNotesRepository()
        val notebook = source.createNotebook("Diary")
        source.seedNote(
            notebookId = notebook.id,
            title = "Last year",
            markdownBody = "memory",
            createdDate = LocalDate(2025, 5, 22),
        )
        val repository = CountingNotesRepository(source)

        findUpcomingOnThisDayOccurrences(
            notesRepository = repository,
            preferences = OnThisDayNotificationPreferences(enabled = true),
            now = Instant.parse("2026-05-22T01:00:00Z"),
            timeZone = TimeZone.UTC,
            searchDays = 367,
        )

        assertEquals(1, repository.activeNoteDatesReads)
        assertEquals(0, repository.singleDateReads)
    }

    @Test
    fun returnsNullWhenDisabledOrNoPriorYearNotes() {
        val repository = InMemoryNotesRepository()
        assertNull(
            findNextOnThisDayFireAt(
                notesRepository = repository,
                preferences = OnThisDayNotificationPreferences(enabled = false),
                now = Instant.parse("2026-05-22T01:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
        assertNull(
            findNextOnThisDayFireAt(
                notesRepository = repository,
                preferences = OnThisDayNotificationPreferences(enabled = true),
                now = Instant.parse("2026-05-22T01:00:00Z"),
                timeZone = TimeZone.UTC,
                searchDays = 3,
            ),
        )
    }
}

private class CountingNotesRepository(
    private val delegate: NotesRepository,
) : NotesRepository by delegate {
    var activeNoteDatesReads: Int = 0
        private set
    var singleDateReads: Int = 0
        private set

    override fun listActiveNoteDates(): List<LocalDate> {
        activeNoteDatesReads += 1
        return delegate.listActiveNoteDates()
    }

    override fun listPriorYearNotesForDate(date: LocalDate): List<NoteSummary> {
        singleDateReads += 1
        return delegate.listPriorYearNotesForDate(date)
    }
}
