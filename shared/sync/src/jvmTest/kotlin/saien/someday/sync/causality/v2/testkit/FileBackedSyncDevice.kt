@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2.testkit

import app.cash.sqldelight.db.SqlDriver
import java.nio.file.Files
import java.nio.file.Path
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.sync.causality.v2.ActiveWorkspaceSystemV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.WorkspaceEntityVersionV2
import saien.someday.sync.causality.v2.WorkspaceSyncCoordinatorV2
import saien.someday.sync.causality.v2.WorkspaceSyncRemoteV2
import saien.someday.sync.causality.v2.WorkspaceSystemV2ContextProvider
import kotlin.time.Instant

/**
 * One real, file-backed client installation for crash/restart tests.
 *
 * This fixture intentionally owns only the SQLite lifecycle and production
 * object wiring. Test scenarios remain explicit in each test method.
 */
internal class FileBackedSyncDevice private constructor(
    val writerDeviceId: String,
    private val directory: Path,
    private val clock: () -> Instant,
) : AutoCloseable {
    private val jdbcUrl = "jdbc:sqlite:${directory.resolve("someday.db").toAbsolutePath()}"
    private var driver: SqlDriver? = null
    private var repository: SqlDelightLocalDataRepository? = null

    val local: SqlDelightLocalDataRepository
        get() = checkNotNull(repository) { "The test device database is closed." }

    init {
        openDatabase()
    }

    fun reopen(): SqlDelightLocalDataRepository {
        closeDatabase()
        openDatabase()
        return local
    }

    fun closeDatabase() {
        driver?.close()
        driver = null
        repository = null
    }

    fun protocolStore(): SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(local.database)

    fun requireActiveContext(
        workspaceKey: WorkspaceMasterKey,
        remoteProfile: String = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
    ): ActiveWorkspaceSystemV2 = WorkspaceSystemV2ContextProvider(
        localRepository = local,
        workspaceKeyProvider = { workspaceKey },
        writerDeviceIdProvider = { writerDeviceId },
        remoteProfileProvider = { remoteProfile },
    ).requireActive()

    fun coordinator(
        workspaceKey: WorkspaceMasterKey,
        remote: WorkspaceSyncRemoteV2,
        beforeEntityPublication: (List<WorkspaceEntityVersionV2>) -> Unit = {},
    ): WorkspaceSyncCoordinatorV2 = WorkspaceSyncCoordinatorV2(
        localRepository = local,
        workspaceKey = workspaceKey,
        localWriterDeviceId = writerDeviceId,
        remote = remote,
        beforeEntityPublication = beforeEntityPublication,
        clock = clock,
    )

    override fun close() {
        closeDatabase()
        directory.toFile().deleteRecursively()
    }

    private fun openDatabase() {
        check(driver == null && repository == null) { "The test device database is already open." }
        val openedDriver = createSomedayJdbcDriver(jdbcUrl)
        driver = openedDriver
        repository = SqlDelightLocalDataRepository(
            SomedayDatabase(openedDriver),
            "sync-test-$writerDeviceId",
            clock,
        )
    }

    companion object {
        fun create(
            writerDeviceId: String,
            clock: () -> Instant,
        ): FileBackedSyncDevice = FileBackedSyncDevice(
            writerDeviceId,
            Files.createTempDirectory("someday-sync-device-"),
            clock,
        )
    }
}
