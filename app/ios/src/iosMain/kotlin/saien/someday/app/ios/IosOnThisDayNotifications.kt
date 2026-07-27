@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package saien.someday.app.ios

import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.OnThisDayNotificationTimeFormatter
import saien.someday.domain.notifications.OnThisDayOccurrence
import saien.someday.domain.notifications.findUpcomingOnThisDayOccurrences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.currentLocale
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.time.Clock

internal object IosOnThisDayNotificationContract {
    const val RequestIdentifier: String = "saien.someday.on_this_day"
    const val RequestIdentifierPrefix: String = "$RequestIdentifier."
    const val UserInfoOpenMemoriesKey: String = "open_memories"
    const val MaxPendingRequestCount: Int = 64
    const val SearchDays: Int = 367
}

class IosOnThisDayNotificationScheduler(
    private val notesRepository: NotesRepository,
    private val notificationCenter: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
) : OnThisDayNotificationScheduler {
    override val isSupported: Boolean = true

    override suspend fun ensurePermission(): Boolean =
        suspendCancellableCoroutine { continuation ->
            notificationCenter.getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                if (status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional) {
                    continuation.resume(true)
                    return@getNotificationSettingsWithCompletionHandler
                }
                notificationCenter.requestAuthorizationWithOptions(
                    options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
                ) { granted, _ ->
                    continuation.resume(granted)
                }
            }
        }

    override fun syncSchedule(preferences: OnThisDayNotificationPreferences) {
        if (!preferences.enabled) {
            cancel()
            return
        }
        val timeZone = TimeZone.currentSystemDefault()
        val occurrences = findUpcomingOnThisDayOccurrences(
            notesRepository = notesRepository,
            preferences = preferences,
            timeZone = timeZone,
            searchDays = IosOnThisDayNotificationContract.SearchDays,
            limit = IosOnThisDayNotificationContract.MaxPendingRequestCount,
        )
        removeScheduledNotifications(timeZone)
        occurrences.forEach { occurrence ->
            scheduleOccurrence(
                occurrence = occurrence,
                timeZone = timeZone,
            )
        }
    }

    private fun scheduleOccurrence(
        occurrence: OnThisDayOccurrence,
        timeZone: TimeZone,
    ) {
        val local = occurrence.fireAt.toLocalDateTime(timeZone)
        val content = UNMutableNotificationContent().apply {
            setTitle(iosLocalizedString("on_this_day_notification_title"))
            setBody(iosOnThisDayNotificationBody(occurrence.memoryCount))
            setUserInfo(
                mapOf(
                    IosOnThisDayNotificationContract.UserInfoOpenMemoriesKey to NSNumber(bool = true),
                ),
            )
            setSound(UNNotificationSound.defaultSound)
        }
        val components = NSDateComponents().apply {
            year = local.year.toLong()
            month = local.month.ordinal.toLong() + 1
            day = local.day.toLong()
            hour = local.hour.toLong()
            minute = local.minute.toLong()
            second = 0
        }
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
            dateComponents = components,
            repeats = false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = iosOnThisDayRequestIdentifier(local.date),
            content = content,
            trigger = trigger,
        )
        notificationCenter.addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun cancel() {
        removeScheduledNotifications(TimeZone.currentSystemDefault())
    }

    private fun removeScheduledNotifications(timeZone: TimeZone) {
        val identifiers = iosOnThisDayRequestIdentifiersAroundNow(timeZone)
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers,
        )
        notificationCenter.removeDeliveredNotificationsWithIdentifiers(
            identifiers,
        )
    }
}

class IosOnThisDayNotificationTimeFormatter : OnThisDayNotificationTimeFormatter {
    override val is24Hour: Boolean
        get() = preferredTimePattern()?.contains("a") != true

    override fun format(
        hour: Int,
        minute: Int,
    ): String {
        val pattern = preferredTimePattern()
        val date = NSCalendar.currentCalendar.dateFromComponents(
            NSDateComponents().apply {
                year = 2000
                month = 1
                day = 1
                this.hour = hour.toLong()
                this.minute = minute.toLong()
            },
        )
        if (pattern != null && date != null) {
            return NSDateFormatter().run {
                locale = NSLocale.currentLocale
                dateFormat = pattern
                stringFromDate(date)
            }
        }
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun preferredTimePattern(): String? =
        NSDateFormatter.dateFormatFromTemplate(
            tmplate = "j:mm",
            options = 0u,
            locale = NSLocale.currentLocale,
        )
}

private fun iosOnThisDayNotificationBody(memoryCount: Int): String {
    val key = if (memoryCount == 1) {
        "on_this_day_notification_body_one"
    } else {
        "on_this_day_notification_body_other"
    }
    return iosLocalizedString(key).replace("%d", memoryCount.toString())
}

private fun iosLocalizedString(key: String): String =
    NSBundle.mainBundle.localizedStringForKey(
        key = key,
        value = null,
        table = null,
    )

internal fun iosOnThisDayRequestIdentifier(date: LocalDate): String =
    "${IosOnThisDayNotificationContract.RequestIdentifierPrefix}$date"

private fun iosOnThisDayRequestIdentifiersAroundNow(timeZone: TimeZone): List<String> {
    val today = Clock.System.now().toLocalDateTime(timeZone).date
    return buildList {
        add(IosOnThisDayNotificationContract.RequestIdentifier)
        for (offset in -IosOnThisDayNotificationContract.SearchDays..IosOnThisDayNotificationContract.SearchDays) {
            add(iosOnThisDayRequestIdentifier(today.plus(offset, DateTimeUnit.DAY)))
        }
    }
}

internal fun iosLaunchWantsOpenMemories(
    userInfo: Map<Any?, *>?,
): Boolean {
    val value = userInfo?.get(IosOnThisDayNotificationContract.UserInfoOpenMemoriesKey) ?: return false
    return when (value) {
        is Boolean -> value
        is NSNumber -> value.boolValue
        else -> false
    }
}
