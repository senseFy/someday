package saien.someday.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountDataRepository
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountAdmissionIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("admission").toPath())
        database.reset()
        identity = database.seedIdentity()
    }

    @AfterTest fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test fun exclusiveTransitionWaitsForAdmittedWorkAndFencesFollowingRequests() {
        val control = database.seedIdentity()
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val next = UUID.randomUUID()
        val resetName = "admission_reset_${UUID.randomUUID().toString().take(8)}"
        val resetConfig = database.configWithApplicationName(resetName)
        try {
            val work = executor.submit {
                AccountAdmission.transaction(provider(), listOf(identity.userId), scope = ::workspaceScope) { connection ->
                    val account = AccountAdmission.admit(connection, identity.request, AccountAccess.SYNC, WORKSPACE)
                    admitted.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                    connection.prepareStatement(
                        "INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.setObject(1, account.userId)
                        statement.setString(2, WORKSPACE)
                        statement.setObject(3, account.incarnation)
                        statement.executeUpdate()
                    }
                }
            }
            assertTrue(admitted.await(30, TimeUnit.SECONDS))
            val reset = executor.submit {
                AccountAdmission.transaction(provider(resetConfig), listOf(identity.userId), AccountLockMode.EXCLUSIVE) {
                    retire(it, next)
                }
            }
            database.awaitAdvisoryLockWait(resetName, reset::isDone)
            val busy = assertFailsWith<AccountProtocolFailure> {
                AccountDataRepository(database.config).discover(identity.request)
            }
            assertEquals(AccountError.ACCOUNT_BUSY, busy.error)
            assertEquals(ACCOUNT_INITIAL_INCARNATION.toString(), AccountDataRepository(database.config).discover(control.request).accountIncarnation)
            release.countDown()
            work.get(30, TimeUnit.SECONDS)
            reset.get(30, TimeUnit.SECONDS)
            val stale = assertFailsWith<AccountProtocolFailure> {
                AccountDataRepository(database.config).discover(identity.request)
            }
            assertEquals(AccountError.ACCOUNT_SESSION_STALE, stale.error)
            assertEquals(1L, database.countRows("someday_entity_workspaces", identity.userId, WORKSPACE))
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun currentCredentialsCannotRelabelARetiredWorkspaceOrIgnoreProtocol() {
        val next = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        AccountAdmission.transaction(provider(), listOf(identity.userId), AccountLockMode.EXCLUSIVE, ::workspaceScope) { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.setString(2, WORKSPACE)
                statement.setObject(3, ACCOUNT_INITIAL_INCARNATION)
                statement.executeUpdate()
            }
            retire(connection, next)
            connection.prepareStatement("UPDATE someday_devices SET data_incarnation = ? WHERE user_id = ? AND id = ?").use { statement ->
                statement.setObject(1, next)
                statement.setObject(2, identity.userId)
                statement.setObject(3, identity.deviceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO someday_sessions(id, user_id, device_id, data_incarnation, expires_at) VALUES (?, ?, ?, ?, NOW() + INTERVAL '1 hour')",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, identity.userId)
                statement.setObject(3, identity.deviceId)
                statement.setObject(4, next)
                statement.executeUpdate()
            }
        }
        val request = identity.request.copy(sessionId = sessionId)
        fun denied(protocol: AccountProtocolExpectation, expected: AccountError) {
            val failure = assertFailsWith<AccountProtocolFailure> {
                AccountAdmission.transaction(provider(), listOf(identity.userId), scope = ::workspaceScope) {
                    AccountAdmission.admit(it, request.copy(protocol = protocol), AccountAccess.SYNC, WORKSPACE)
                }
            }
            assertEquals(expected, failure.error)
        }
        denied(AccountProtocolExpectation.Missing, AccountError.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED)
        denied(AccountProtocolExpectation.V1(ACCOUNT_INITIAL_INCARNATION), AccountError.ACCOUNT_INCARNATION_MISMATCH)
        denied(AccountProtocolExpectation.V1(next), AccountError.WORKSPACE_INCARNATION_RETIRED)
    }

    @Test fun admissionRestoresLockTimeoutAndUsesFreshStatementSnapshots() {
        val connections = DatabaseConnectionProvider {
            provider().connection().also { connection ->
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                connection.createStatement().use { it.execute("SET lock_timeout = '19ms'") }
            }
        }
        AccountAdmission.transaction(connections, listOf(identity.userId)) { connection ->
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW lock_timeout").use { result ->
                    assertTrue(result.next())
                    assertEquals("19ms", result.getString(1))
                }
            }
        }
        val failure = assertFailsWith<SQLException> {
            AccountAdmission.transaction(provider(), listOf(identity.userId)) {
                throw SQLException("Synthetic narrower-lock timeout", "55P03")
            }
        }
        assertEquals("55P03", failure.sqlState)
    }

    @Test fun sessionOwnershipIsRecheckedInsideAdmission() {
        val other = database.seedIdentity()
        val failure = assertFailsWith<AccountProtocolFailure> {
            AccountDataRepository(database.config).discover(identity.request.copy(userId = other.userId))
        }
        assertEquals(AccountError.UNAUTHORIZED, failure.error)
    }

    private fun provider(config: ServerConfig = database.config) = DatabaseConnectionProvider {
        DriverManager.getConnection(config.databaseConnectionUrl, config.databaseUser, config.databasePassword)
    }

    private fun workspaceScope(connection: Connection) {
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, true), set_config('someday.workspace_id', ?, true)").use {
            it.setString(1, identity.userId.toString())
            it.setString(2, WORKSPACE)
            it.executeQuery().close()
        }
    }

    private fun retire(connection: Connection, next: UUID) {
        connection.prepareStatement(
            "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'",
        ).use { it.setObject(1, identity.userId); assertEquals(1, it.executeUpdate()) }
        connection.prepareStatement(
            "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())",
        ).use { it.setObject(1, identity.userId); it.setObject(2, next); it.executeUpdate() }
    }

    private companion object {
        const val WORKSPACE = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
