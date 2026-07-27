package saien.someday.domain.notifications

import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class OnThisDayOccurrence(
    val fireAt: Instant,
    val memoryCount: Int,
)

/**
 * Finds the next local calendar day that has prior-year notes and whose
 * notification time is still in the future (or today if not yet passed).
 */
@OptIn(ExperimentalTime::class)
fun findNextOnThisDayFireAt(
    notesRepository: NotesRepository,
    preferences: OnThisDayNotificationPreferences,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    searchDays: Int = 367,
): Instant? =
    findUpcomingOnThisDayOccurrences(
        notesRepository = notesRepository,
        preferences = preferences,
        now = now,
        timeZone = timeZone,
        searchDays = searchDays,
        limit = 1,
    ).firstOrNull()?.fireAt

@OptIn(ExperimentalTime::class)
fun findUpcomingOnThisDayOccurrences(
    notesRepository: NotesRepository,
    preferences: OnThisDayNotificationPreferences,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    searchDays: Int = 367,
    limit: Int = Int.MAX_VALUE,
): List<OnThisDayOccurrence> {
    if (!preferences.enabled || searchDays <= 0 || limit <= 0) {
        return emptyList()
    }
    val nowLocal = now.toLocalDateTime(timeZone)
    var candidateDate: LocalDate = nowLocal.date
    val occurrences = mutableListOf<OnThisDayOccurrence>()
    val noteDatesByMonthDay = notesRepository.listActiveNoteDates().groupBy(LocalDate::monthDayKey)
    repeat(searchDays) {
        val memoryCount = noteDatesByMonthDay[candidateDate.monthDayKey()]
            .orEmpty()
            .count { noteDate -> noteDate < candidateDate }
        if (memoryCount > 0) {
            val fireAt = LocalDateTime(
                year = candidateDate.year,
                month = candidateDate.month,
                day = candidateDate.day,
                hour = preferences.hour,
                minute = preferences.minute,
                second = 0,
                nanosecond = 0,
            ).toInstant(timeZone)
            if (fireAt > now) {
                occurrences += OnThisDayOccurrence(
                    fireAt = fireAt,
                    memoryCount = memoryCount,
                )
                if (occurrences.size == limit) {
                    return occurrences
                }
            }
        }
        candidateDate = candidateDate.plus(1, DateTimeUnit.DAY)
    }
    return occurrences
}

private fun LocalDate.monthDayKey(): Int =
    month.ordinal * 32 + day
