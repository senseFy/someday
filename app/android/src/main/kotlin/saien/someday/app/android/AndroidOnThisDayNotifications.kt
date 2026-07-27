package saien.someday.app.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.OnThisDayNotificationTimeFormatter
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Calendar
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal object OnThisDayNotificationContract {
    const val ChannelId: String = "on_this_day"
    const val NotificationId: Int = 4101
    const val AlarmRequestCode: Int = 4102
    const val ExtraOpenMemories: String = "saien.someday.app.OPEN_MEMORIES"

    fun actionFire(packageName: String): String = "$packageName.action.ON_THIS_DAY_FIRE"
}

class AndroidOnThisDayNotificationScheduler(
    private val context: Context,
    private val requestPermission: suspend () -> Boolean = { true },
) : OnThisDayNotificationScheduler {
    override val isSupported: Boolean = true

    override suspend fun ensurePermission(): Boolean {
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            return true
        }
        return requestPermission()
    }

    override fun syncSchedule(preferences: OnThisDayNotificationPreferences) {
        if (!preferences.enabled) {
            cancel()
            return
        }
        ensureNotificationChannel()
        scheduleNextAlarm(preferences.hour, preferences.minute)
    }

    override fun cancel() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(alarmPendingIntent())
    }

    internal fun scheduleNextAlarm(
        hour: Int,
        minute: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = nextTriggerEpochMillis(
            hour = hour,
            minute = minute,
            nowEpochMillis = nowEpochMillis,
        )
        val pendingIntent = alarmPendingIntent()
        // A daily memory reminder does not justify exact-alarm privilege.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(context, OnThisDayAlarmReceiver::class.java).apply {
            action = OnThisDayNotificationContract.actionFire(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            OnThisDayNotificationContract.AlarmRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        ensureOnThisDayNotificationChannel(context)
    }
}

class AndroidOnThisDayNotificationTimeFormatter(
    private val context: Context,
) : OnThisDayNotificationTimeFormatter {
    override val is24Hour: Boolean
        get() = DateFormat.is24HourFormat(context)

    override fun format(
        hour: Int,
        minute: Int,
    ): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }
}

internal fun ensureOnThisDayNotificationChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(
            OnThisDayNotificationContract.ChannelId,
            context.getString(R.string.on_this_day_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.on_this_day_notification_channel_description)
        },
    )
}

class OnThisDayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val broadcastKind = onThisDayBroadcastKind(
            action = intent?.action,
            packageName = context.packageName,
        ) ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handleOnThisDayBroadcast(
                    context = context.applicationContext,
                    broadcastKind = broadcastKind,
                )
            } catch (failure: Throwable) {
                Log.e("Someday", "On This Day broadcast processing failed.", failure)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}

internal enum class OnThisDayBroadcastKind {
    Fire,
    Reschedule,
}

internal fun onThisDayBroadcastKind(
    action: String?,
    packageName: String,
): OnThisDayBroadcastKind? =
    when (action) {
        null,
        OnThisDayNotificationContract.actionFire(packageName),
        -> OnThisDayBroadcastKind.Fire
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        -> OnThisDayBroadcastKind.Reschedule
        else -> null
    }

@OptIn(ExperimentalTime::class)
private fun handleOnThisDayBroadcast(
    context: Context,
    broadcastKind: OnThisDayBroadcastKind,
) {
    val repositories = createAndroidOnThisDayRepositories(context)
    try {
        val preferences = repositories.settingsRepository.load().onThisDayNotifications
        if (!preferences.enabled) {
            return
        }

        val scheduler = AndroidOnThisDayNotificationScheduler(context)
        if (broadcastKind == OnThisDayBroadcastKind.Reschedule) {
            scheduler.syncSchedule(preferences)
            return
        }

        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        try {
            val priorYearNotes = repositories.notesRepository.listPriorYearNotesForDate(today)
            if (priorYearNotes.isNotEmpty()) {
                postOnThisDayNotification(
                    context = context,
                    memoryCount = priorYearNotes.size,
                )
            }
        } finally {
            scheduler.scheduleNextAlarm(preferences.hour, preferences.minute)
        }
    } finally {
        repositories.close()
    }
}

internal fun postOnThisDayNotification(
    context: Context,
    memoryCount: Int,
) {
    ensureOnThisDayNotificationChannel(context)

    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OnThisDayNotificationContract.ExtraOpenMemories, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val body = context.resources.getQuantityString(
        R.plurals.on_this_day_notification_body,
        memoryCount,
        memoryCount,
    )
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val notification = Notification.Builder(context, OnThisDayNotificationContract.ChannelId)
        .setSmallIcon(android.R.drawable.ic_menu_today)
        .setContentTitle(context.getString(R.string.on_this_day_notification_title))
        .setContentText(body)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(OnThisDayNotificationContract.NotificationId, notification)
}

internal fun nextTriggerEpochMillis(
    hour: Int,
    minute: Int,
    nowEpochMillis: Long,
): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowEpochMillis
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    if (calendar.timeInMillis <= nowEpochMillis) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}

internal class AndroidNotificationPermissionBridge {
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun awaitPermission(launchRequest: () -> Unit): Boolean {
        pending?.cancel()
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return withContext(Dispatchers.Main) {
            launchRequest()
            deferred.await()
        }
    }

    fun complete(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }
}
