package saien.someday.domain.notifications

import saien.someday.domain.settings.OnThisDayNotificationPreferences

/**
 * Platform scheduler for daily "On This Day" local notifications.
 *
 * Desktop and unsupported builds use [UnavailableOnThisDayNotificationScheduler]
 * so the settings UI can hide the controls when [isSupported] is false.
 */
interface OnThisDayNotificationScheduler {
    val isSupported: Boolean

    /**
     * Request notification permission if needed.
     * Returns true when notifications may be scheduled.
     */
    suspend fun ensurePermission(): Boolean

    /**
     * Align the platform schedule with the persisted preferences.
     * When [preferences].enabled is false, cancels any pending schedule.
     */
    fun syncSchedule(preferences: OnThisDayNotificationPreferences)

    fun cancel()
}

interface OnThisDayNotificationTimeFormatter {
    val is24Hour: Boolean

    fun format(
        hour: Int,
        minute: Int,
    ): String
}

object TwentyFourHourOnThisDayNotificationTimeFormatter : OnThisDayNotificationTimeFormatter {
    override val is24Hour: Boolean = true

    override fun format(
        hour: Int,
        minute: Int,
    ): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

object UnavailableOnThisDayNotificationScheduler : OnThisDayNotificationScheduler {
    override val isSupported: Boolean = false

    override suspend fun ensurePermission(): Boolean = false

    override fun syncSchedule(preferences: OnThisDayNotificationPreferences) = Unit

    override fun cancel() = Unit
}
