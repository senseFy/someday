@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.memories

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import saien.someday.domain.notes.MemoryDayCount
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NotesRepository
import saien.someday.ui.i18n.MemoriesUiStrings
import saien.someday.ui.i18n.formatUiString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class MemoriesUiController(
    private val repository: NotesRepository,
    strings: MemoriesUiStrings = MemoriesUiStrings(),
    initialSelectedDate: LocalDate = currentLocalDate(),
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var strings = strings
    var state: MemoriesUiState by mutableStateOf(
        MemoriesUiState(
            month = MemoryMonth(initialSelectedDate.year, initialSelectedDate.month.ordinal + 1),
            selectedDate = initialSelectedDate,
        ),
    )
        private set

    fun updateLocalizedStrings(updated: MemoriesUiStrings) {
        strings = updated
    }

    suspend fun refresh() {
        applyRepositoryData(
            loadRepositoryData(
                month = state.month,
                selectedDate = state.selectedDate,
            ),
        )
    }

    suspend fun loadRepositoryData(
        month: MemoryMonth = state.month,
        selectedDate: LocalDate = state.selectedDate,
    ): MemoriesRepositoryData =
        withContext(backgroundDispatcher) {
            MemoriesRepositoryData(
                month = month,
                selectedDate = selectedDate,
                dayCounts = repository.listMemoryDayCounts(month),
                selectedDayNotes = repository.listNotesForDate(selectedDate),
                priorYearNotes = repository.listPriorYearNotesForDate(selectedDate),
            )
        }

    fun applyRepositoryData(data: MemoriesRepositoryData) {
        if (state.month == data.month && state.selectedDate == data.selectedDate) {
            state = state.withRepositoryData(data)
        }
    }

    suspend fun goToPreviousMonth() {
        goToMonth(state.month.previous())
    }

    suspend fun goToNextMonth() {
        goToMonth(state.month.next())
    }

    suspend fun goToMonth(month: MemoryMonth) {
        if (state.month == month) return
        val selectedDate = month.clampDay(state.selectedDate.day)
        state = state.copy(
            month = month,
            selectedDate = selectedDate,
            feedbackMessage = formatUiString(strings.showingMonth, month.label),
        )
        applyRepositoryData(loadRepositoryData(month = month, selectedDate = selectedDate))
    }

    suspend fun selectDate(date: LocalDate): Boolean {
        if (!state.month.contains(date)) {
            state = state.copy(feedbackMessage = formatUiString(strings.chooseDateInMonth, state.month.label))
            return false
        }
        state = state.copy(
            selectedDate = date,
            feedbackMessage = formatUiString(strings.selectedDate, date),
        )
        applyRepositoryData(loadRepositoryData(selectedDate = date))
        return true
    }

    suspend fun selectDay(dayOfMonth: Int): Boolean =
        runCatching { state.month.dateForDay(dayOfMonth) }
            .fold(
                onSuccess = { selectDate(it) },
                onFailure = {
                    state = state.copy(feedbackMessage = it.message)
                    false
                },
            )

    private companion object {
        fun currentLocalDate(): LocalDate =
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }
}

data class MemoriesUiState(
    val month: MemoryMonth,
    val selectedDate: LocalDate,
    val dayCounts: List<MemoryDayCount> = emptyList(),
    val selectedDayNotes: List<NoteSummary> = emptyList(),
    val priorYearNotes: List<NoteSummary> = emptyList(),
    val feedbackMessage: String? = null,
) {
    val dayCells: List<MemoryCalendarDay> =
        (1..month.daysInMonth).map { day ->
            val date = month.dateForDay(day)
            MemoryCalendarDay(
                date = date,
                noteCount = countForDate(date),
                selected = date == selectedDate,
            )
        }

    fun countForDay(dayOfMonth: Int): Int =
        countForDate(month.dateForDay(dayOfMonth))

    private fun countForDate(date: LocalDate): Int =
        dayCounts.firstOrNull { it.date == date }?.noteCount ?: 0

    internal fun withRepositoryData(data: MemoriesRepositoryData): MemoriesUiState =
        copy(
            dayCounts = data.dayCounts,
            selectedDayNotes = data.selectedDayNotes,
            priorYearNotes = data.priorYearNotes,
        )
}

data class MemoriesRepositoryData(
    val month: MemoryMonth,
    val selectedDate: LocalDate,
    val dayCounts: List<MemoryDayCount>,
    val selectedDayNotes: List<NoteSummary>,
    val priorYearNotes: List<NoteSummary>,
)

data class MemoryCalendarDay(
    val date: LocalDate,
    val noteCount: Int,
    val selected: Boolean,
)
