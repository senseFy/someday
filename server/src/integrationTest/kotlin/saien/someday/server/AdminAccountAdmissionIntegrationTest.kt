package saien.someday.server

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.AdminRepository
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.support.IsolatedPostgresDatabase

class AdminAccountAdmissionIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleOrDemotedAdministratorCannotReadOrMutateAnotherAccount() {
        database().use { database ->
            val actor = seed(database, admin = true)
            val target = seed(database, admin = false)
            val repository = AdminRepository(database.config, Instant.now())
            retire(database, actor.userId)

            assertFailure(AccountError.ACCOUNT_SESSION_STALE) { repository.dashboard(actor.request) }
            assertFailure(AccountError.ACCOUNT_SESSION_STALE) { repository.disableUser(actor.request, target.userId) }
            assertFailure(AccountError.ACCOUNT_SESSION_STALE) { repository.revokeSession(actor.request, target.sessionId) }
            assertFailure(AccountError.ACCOUNT_SESSION_STALE) { repository.revokeDevice(actor.request, target.deviceId) }
            assertUntouched(database, target)

            val demoted = seed(database, admin = true)
            database.scopedTransaction { connection ->
                connection.execute("UPDATE someday_users SET is_admin = false WHERE id = ?", demoted.userId)
            }
            assertFailure(AccountError.FORBIDDEN) { repository.listUsers(demoted.request) }
            assertFailure(AccountError.FORBIDDEN) { repository.revokeDevice(demoted.request, target.deviceId) }
            assertFailure(AccountError.FORBIDDEN) { repository.disableUser(target.request, demoted.userId) }
            assertUntouched(database, target)
        }
    }

    @Test
    fun revocationUsesSharedActorAndTargetAdmissionAndPreservesOtherAccounts() {
        database().use { database ->
            val actor = seed(database, admin = true)
            val target = seed(database, admin = false)
            val control = seed(database, admin = false)
            val repository = AdminRepository(database.config, Instant.now())
            database.connection().use { admittedUpload ->
                admittedUpload.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                admittedUpload.autoCommit = false
                AccountAdmission.lockAccounts(admittedUpload, listOf(actor.userId, target.userId), AccountLockMode.SHARED)
                try {
                    // An exclusive implementation times out behind this already-admitted work.
                    assertTrue(repository.revokeDevice(actor.request, target.deviceId))
                    assertTrue(repository.revokeSession(actor.request, actor.sessionId))
                } finally {
                    admittedUpload.rollback()
                }
            }
            database.connection().use { connection ->
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_devices WHERE id = '${target.deviceId}' AND revoked_at IS NOT NULL"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_sessions WHERE id = '${target.sessionId}' AND revoked_at IS NOT NULL"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_refresh_tokens WHERE session_id = '${target.sessionId}' AND revoked_at IS NOT NULL"))
            }
            assertUntouched(database, control)
            assertFailure(AccountError.UNAUTHORIZED) { repository.health(actor.request) }
        }
    }

    @Test
    fun actorResetWaitsForAnAdmittedCrossAccountMutation() {
        database().use { database ->
            val actor = seed(database, admin = true)
            val target = seed(database, admin = false)
            val mutationName = "admin_mutation_${UUID.randomUUID().toString().replace("-", "")}"
            val resetName = "admin_reset_${UUID.randomUUID().toString().replace("-", "")}"
            val repository = AdminRepository(database.config, Instant.now(), namedConnections(database, mutationName))
            val executor = Executors.newFixedThreadPool(2)
            database.connection().use { rowLock ->
                rowLock.autoCommit = false
                rowLock.prepareStatement("SELECT id FROM someday_devices WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setObject(1, target.deviceId)
                    statement.executeQuery().close()
                }
                try {
                    val mutation = executor.submit<Boolean> { repository.revokeDevice(actor.request, target.deviceId) }
                    awaitLockWait(database, mutationName) { mutation.isDone }
                    val reset = executor.submit<UUID> { retire(database, actor.userId, namedConnections(database, resetName)) }
                    awaitLockWait(database, resetName) { reset.isDone }
                    assertFalse(mutation.isDone)
                    assertFalse(reset.isDone)
                    rowLock.commit()
                    assertTrue(mutation.get(10, TimeUnit.SECONDS))
                    assertNotNull(reset.get(10, TimeUnit.SECONDS))
                    assertFailure(AccountError.ACCOUNT_SESSION_STALE) { repository.dashboard(actor.request) }
                    database.connection().use { connection ->
                        assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_devices WHERE id = '${target.deviceId}' AND revoked_at IS NOT NULL"))
                    }
                } finally {
                    rowLock.rollback()
                    executor.shutdownNow()
                    check(executor.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun activeContentTotalsExcludeRetiredWorkspacesWhileAuthorityHistoryRemainsVisible() {
        database().use { database ->
            val actor = seed(database, admin = true)
            val target = seed(database, admin = false)
            val control = seed(database, admin = false)
            seedObject(database, target)
            seedObject(database, control)
            val repository = AdminRepository(database.config, Instant.now())
            assertEquals(2L, repository.dashboard(actor.request).encryptedObjects)
            retire(database, target.userId)

            assertEquals(1L, repository.dashboard(actor.request).encryptedObjects)
            assertEquals(1L, repository.storage(actor.request).encryptedObjects)
            assertEquals(1L, repository.storage(actor.request).byType.single().objects)
            assertEquals(0L, repository.storage(actor.request).byUser.single { it.userId == target.userId }.objects)
            val detail = assertNotNull(repository.userDetail(actor.request, target.userId))
            assertEquals(0L, detail.user.objectCount)
            assertFalse(detail.devices.single().isCurrentIncarnation)
            assertFalse(detail.sessions.single().isCurrentIncarnation)
            assertEquals(0L, detail.sessions.single().activeRefreshTokens)
            assertEquals(1L, assertNotNull(repository.userDetail(actor.request, control.userId)).user.objectCount)
            database.scopedTransaction { connection ->
                assertEquals(2L, connection.number("SELECT COUNT(*) FROM someday_sync_v2_objects"))
            }
        }
    }

    private fun database(): IsolatedPostgresDatabase =
        IsolatedPostgresDatabase(temporaryFolder.newFolder().toPath()).also { database ->
            try {
                DatabaseMigrator.migrate(database.config)
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
        }

    private fun seed(database: IsolatedPostgresDatabase, admin: Boolean): Identity {
        val user = UUID.randomUUID()
        val device = UUID.randomUUID()
        val session = UUID.randomUUID()
        database.scopedTransaction { connection ->
            connection.execute("INSERT INTO someday_users(id, email, password_hash, is_admin) VALUES (?, ?, 'synthetic', ?)", user, "$user@example.com", admin)
            connection.execute("INSERT INTO someday_devices(id, user_id, name, platform, data_incarnation) VALUES (?, ?, 'test', 'test', ?)", device, user, ACCOUNT_INITIAL_INCARNATION)
            connection.execute("INSERT INTO someday_sessions(id, user_id, device_id, expires_at, data_incarnation) VALUES (?, ?, ?, TIMESTAMPTZ '2099-01-01 00:00:00Z', ?)", session, user, device, ACCOUNT_INITIAL_INCARNATION)
            connection.execute("INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, ?, TIMESTAMPTZ '2099-01-01 00:00:00Z')", UUID.randomUUID(), session, "synthetic-$session")
        }
        return Identity(user, device, session, AccountRequestContext(user, session, device, setOf("auth", "devices", "sync")))
    }

    private fun seedObject(database: IsolatedPostgresDatabase, identity: Identity) {
        val workspace = "workspace-${identity.userId.toString().replace("-", "")}"
        database.scopedTransaction { connection ->
            connection.execute("INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) VALUES (?, ?, ?)", identity.userId, workspace, ACCOUNT_INITIAL_INCARNATION)
            connection.execute("INSERT INTO someday_sync_v2_objects(user_id, workspace_id, epoch_id, object_id, object_type, object_digest, mutation_id, first_writer_device_id, ciphertext_digest, encrypted_object_json) VALUES (?, ?, 'epoch', 'object', 'workspace_entity_version_v2', 'digest', 'mutation', ?, 'ciphertext', '{\"opaque\":true}')", identity.userId, workspace, identity.deviceId)
        }
    }

    private fun retire(
        database: IsolatedPostgresDatabase,
        userId: UUID,
        connections: DatabaseConnectionProvider = DatabaseConnectionProvider(database::connection),
    ): UUID = AccountAdmission.transaction(connections, listOf(userId), AccountLockMode.EXCLUSIVE) { connection ->
        val next = UUID.randomUUID()
        connection.execute("UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'", userId)
        connection.execute("INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())", userId, next)
        next
    }

    private fun namedConnections(database: IsolatedPostgresDatabase, name: String) = DatabaseConnectionProvider {
        database.connection().also { connection ->
            connection.prepareStatement("SELECT set_config('application_name', ?, false)").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().close()
            }
        }
    }

    private fun awaitLockWait(database: IsolatedPostgresDatabase, name: String, completed: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        database.connection().use { connection ->
            connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { statement ->
                statement.setString(1, name)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { result ->
                        check(result.next())
                        if (result.getBoolean(1)) return
                    }
                    check(!completed()) { "Operation completed before the expected PostgreSQL lock wait" }
                    Thread.yield()
                }
            }
        }
        error("Timed out observing PostgreSQL lock wait")
    }

    private fun assertUntouched(database: IsolatedPostgresDatabase, identity: Identity) {
        database.connection().use { connection ->
            assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_users WHERE id = '${identity.userId}' AND disabled_at IS NULL"))
            assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_devices WHERE id = '${identity.deviceId}' AND revoked_at IS NULL"))
            assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_sessions WHERE id = '${identity.sessionId}' AND revoked_at IS NULL"))
        }
    }

    private fun assertFailure(expected: AccountError, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<AccountProtocolFailure>(block = block).error)
    }

    private fun Connection.execute(sql: String, vararg values: Any?) {
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun Connection.number(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }
    }

    private data class Identity(val userId: UUID, val deviceId: UUID, val sessionId: UUID, val request: AccountRequestContext)
}
