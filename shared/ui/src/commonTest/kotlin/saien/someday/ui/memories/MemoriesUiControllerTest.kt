package saien.someday.ui.memories

import saien.someday.ui.notes.InMemoryNotesRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoriesUiControllerTest {
    @Test
    fun calendarMonthNavigationSelectedDayAndPriorYearListsRefreshFromLocalNotes() = runBlocking {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Diary")
        val current = repository.seedNote(
            notebookId = notebook.id,
            title = "May 22 current",
            markdownBody = "Current year selected day",
            createdDate = LocalDate(2026, 5, 22),
        )
        repository.seedNote(
            notebookId = notebook.id,
            title = "May 23 current",
            markdownBody = "Another current day",
            createdDate = LocalDate(2026, 5, 23),
        )
        val priorYear = repository.seedNote(
            notebookId = notebook.id,
            title = "May 22 prior",
            markdownBody = "Same month/day in a prior year",
            createdDate = LocalDate(2025, 5, 22),
        )
        repository.seedNote(
            notebookId = notebook.id,
            title = "April memory",
            markdownBody = "Appears after previous-month navigation",
            createdDate = LocalDate(2026, 4, 22),
        )

        val controller = MemoriesUiController(
            repository = repository,
            initialSelectedDate = LocalDate(2026, 5, 22),
        )
        controller.refresh()

        assertEquals("2026-05", controller.state.month.label)
        assertEquals(1, controller.state.countForDay(22))
        assertEquals(1, controller.state.countForDay(23))
        assertEquals(listOf(current.id), controller.state.selectedDayNotes.map { it.id })
        assertEquals(listOf(priorYear.id), controller.state.priorYearNotes.map { it.id })

        controller.goToPreviousMonth()

        assertEquals("2026-04", controller.state.month.label)
        assertEquals(LocalDate(2026, 4, 22), controller.state.selectedDate)
        assertEquals(1, controller.state.countForDay(22))
        assertTrue(controller.state.priorYearNotes.isEmpty())

        controller.goToNextMonth()
        controller.selectDate(LocalDate(2026, 5, 23))

        assertEquals("2026-05", controller.state.month.label)
        assertEquals(listOf("May 23 current"), controller.state.selectedDayNotes.map { it.title })
        assertTrue(controller.state.priorYearNotes.isEmpty())
    }
}
