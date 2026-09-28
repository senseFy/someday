package saien.someday.app.android

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
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

@RunWith(AndroidJUnit4::class)
class AndroidWorkspaceInitializationTest {
    @Test
    fun foregroundFirstAndOverlappingReminderShareWorkspaceAndSurviveRestart() =
        assertSharedInitialization(reminderFirst = false)

    @Test
    fun reminderFirstAndOverlappingForegroundShareWorkspaceAndSurviveRestart() =
        assertSharedInitialization(reminderFirst = true)

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
