package saien.someday.app.android

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OnThisDayNotificationSchedulingTest {
    @Test
    fun broadcastActionsClassifyClockChangesForRescheduling() {
        val packageName = "saien.someday"
        assertEquals(
            OnThisDayBroadcastKind.Fire,
            onThisDayBroadcastKind(
                OnThisDayNotificationContract.actionFire(packageName),
                packageName,
            ),
        )
        assertEquals(OnThisDayBroadcastKind.Fire, onThisDayBroadcastKind(null, packageName))
        assertEquals(
            OnThisDayBroadcastKind.Reschedule,
            onThisDayBroadcastKind(Intent.ACTION_BOOT_COMPLETED, packageName),
        )
        assertEquals(
            OnThisDayBroadcastKind.Reschedule,
            onThisDayBroadcastKind(Intent.ACTION_MY_PACKAGE_REPLACED, packageName),
        )
        assertEquals(
            OnThisDayBroadcastKind.Reschedule,
            onThisDayBroadcastKind(Intent.ACTION_TIME_CHANGED, packageName),
        )
        assertEquals(
            OnThisDayBroadcastKind.Reschedule,
            onThisDayBroadcastKind(Intent.ACTION_TIMEZONE_CHANGED, packageName),
        )
        assertNull(onThisDayBroadcastKind("unexpected", packageName))
    }

    @Test
    fun nextTriggerUsesTodayWhenTimeHasNotPassed() {
        val now = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MAY)
            set(Calendar.DAY_OF_MONTH, 22)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val trigger = nextTriggerEpochMillis(hour = 10, minute = 0, nowEpochMillis = now)
        val calendar = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, calendar.get(Calendar.MONTH))
        assertEquals(22, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun nextTriggerRollsToTomorrowWhenTimeHasPassed() {
        val now = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MAY)
            set(Calendar.DAY_OF_MONTH, 22)
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val trigger = nextTriggerEpochMillis(hour = 10, minute = 0, nowEpochMillis = now)
        val calendar = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(23, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY))
        assertTrue(trigger > now)
    }
}
