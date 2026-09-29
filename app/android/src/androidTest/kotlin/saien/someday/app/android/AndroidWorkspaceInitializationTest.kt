@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.android

import android.Manifest
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.os.Build
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@RunWith(AndroidJUnit4::class)
class AndroidWorkspaceInitializationTest {
    @Test
    fun foregroundFirstAndOverlappingReminderShareWorkspaceAndSurviveRestart() =
        assertSharedInitialization(reminderFirst = false)

    @Test
    fun reminderFirstAndOverlappingForegroundShareWorkspaceAndSurviveRestart() =
        assertSharedInitialization(reminderFirst = true)

    @Test
    fun enabledFireReadsPriorYearNotesThroughTheSharedGraphAndLeavesItUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = IsolatedWorkspaceContext(instrumentation.targetContext)
        val application = context.newApplication()
        context.continueInitialization.countDown()
        val repositories = application.clientRepositories
        val grantNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        try {
            if (grantNotification) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date
            // Four years also keeps February 29 valid; the app query is month/day based.
            val priorYear = LocalDate(today.year - 4, today.month, today.day).atTime(12, 0).toInstant(zone)
            val notebook = repositories.notesRepository.createNotebook("Reminder regression")
            val note = repositories.notesRepository.createNote(NoteInput(
                notebook.id, "Prior-year memory", "Reminder shares the active workspace", priorYear, timeZoneId = zone.id,
            ))
            repositories.settingsRepository.save(repositories.settingsRepository.load().copy(
                onThisDayNotifications = OnThisDayNotificationPreferences(enabled = true, hour = 9, minute = 0),
            ))
            assertEquals(listOf(note.id), repositories.notesRepository.listPriorYearNotesForDate(today).map { it.id })

            // Enabled + a matching note reaches both the production notes query and notification path.
            handleOnThisDayBroadcast(context, OnThisDayBroadcastKind.Fire)

            assertSame(repositories, application.clientRepositories)
            assertEquals(1, context.keyStoreOpenCount.get())
            assertEquals("Reminder shares the active workspace", assertNotNull(repositories.notesRepository.getNoteDetails(note.id)).markdownBody)
            val current = repositories.notesRepository.createNote(NoteInput(notebook.id, "After Fire", "Still writable"))
            assertNotNull(repositories.notesRepository.getNoteDetails(current.id))
        } finally {
            AndroidOnThisDayNotificationScheduler(context).cancel()
            context.getSystemService(NotificationManager::class.java)?.cancel(OnThisDayNotificationContract.NotificationId)
            // Runtime revocation kills the target process, including this instrumentation.
            // This suite runs only in its disposable emulator installation.
            repositories.close()
            context.cleanUp()
        }
    }

    private fun assertSharedInitialization(reminderFirst: Boolean) {
        val context = IsolatedWorkspaceContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val application = context.newApplication()
        val workers = Executors.newFixedThreadPool(2)
        val openedRepositories = ConcurrentHashMap.newKeySet<AndroidClientRepositories>()
        fun access(fromReminder: Boolean): AndroidClientRepositories {
            if (fromReminder) {
                handleOnThisDayBroadcast(context, OnThisDayBroadcastKind.Reschedule)
            }
            return application.clientRepositories.also { openedRepositories.add(it) }
        }

        try {
            // Merely constructing/attaching Application must not initialize storage.
            assertEquals(0, context.keyStoreOpenCount.get())
            val first = workers.submit<AndroidClientRepositories> { access(reminderFirst) }
            assertTrue(context.initializationEntered.await(10, TimeUnit.SECONDS))
            val secondEntered = CountDownLatch(1)
            val second = workers.submit<AndroidClientRepositories> {
                secondEntered.countDown()
                access(!reminderFirst)
            }
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
            // The initial workspace is not published while its key store is still opening.
            assertFalse(first.isDone)
            assertFalse(second.isDone)
            context.continueInitialization.countDown()

            val repositories = first.get(20, TimeUnit.SECONDS)
            assertSame(repositories, second.get(20, TimeUnit.SECONDS))
            assertEquals(1, context.keyStoreOpenCount.get(), "Only one initialization may open the workspace key store")
            assertFalse(repositories.settingsRepository.load().onThisDayNotifications.enabled)
            val writer = repositories.settingsRepository.load().activeDeviceId
            val notebook = repositories.notesRepository.createNotebook("Initialization regression")
            val note = repositories.notesRepository.createNote(
                NoteInput(notebook.id, "Shared workspace", "Written after the background worker finished"),
            )

            // Finishing either notification action must not close the application's driver.
            handleOnThisDayBroadcast(context, OnThisDayBroadcastKind.Fire)
            assertEquals(
                "Written after the background worker finished",
                assertNotNull(repositories.notesRepository.getNoteDetails(note.id)).markdownBody,
            )
            assertEquals(1, context.keyStoreOpenCount.get())
            repositories.close()

            // Reopen the same isolated files with a new Application/key repository. Cached
            // in-memory keys must not hide a mismatch between persisted keys and DAG data.
            val restarted = context.newApplication().clientRepositories.also { openedRepositories.add(it) }
            assertEquals(2, context.keyStoreOpenCount.get())
            assertEquals(writer, restarted.settingsRepository.load().activeDeviceId)
            assertEquals(
                "Written after the background worker finished",
                assertNotNull(restarted.notesRepository.getNoteDetails(note.id)).markdownBody,
            )
        } finally {
            context.continueInitialization.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS))
            openedRepositories.forEach { it.close() }
            context.cleanUp()
        }
    }

    /** Runs the production bootstrap against unique test-owned files, never the installed workspace. */
    private class IsolatedWorkspaceContext(base: Context) : ContextWrapper(base) {
        private val prefix = "workspace-init-${UUID.randomUUID()}-"
        private val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        private lateinit var application: SomedayApplication
        val initializationEntered = CountDownLatch(1)
        val continueInitialization = CountDownLatch(1)
        val keyStoreOpenCount = AtomicInteger()

        fun newApplication(): SomedayApplication =
            (Instrumentation.newApplication(SomedayApplication::class.java, this) as SomedayApplication)
                .also { application = it }

        override fun getApplicationContext(): Context = application

        override fun getFilesDir(): File = root

        override fun getDatabasePath(name: String): File = File(root, name)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase =
            baseContext.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase =
            baseContext.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Workspace storage was accessed on the main thread" }
            if (name == "someday_workspace_keys") {
                keyStoreOpenCount.incrementAndGet()
                initializationEntered.countDown()
                check(continueInitialization.await(15, TimeUnit.SECONDS)) { "Initialization gate timed out" }
            }
            preferenceNames.add(prefix + name)
            return baseContext.getSharedPreferences(prefix + name, mode)
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(root.deleteRecursively())
        }
    }
}
