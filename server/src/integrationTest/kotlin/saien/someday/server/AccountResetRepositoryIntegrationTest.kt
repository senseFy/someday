package saien.someday.server

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.api.AccountDataResetReceiptResponse
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountDataRepository
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.AccountResetIdentity
import saien.someday.server.persistence.AccountResetPrecheck
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.VerifiedPasswordAccount
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountResetRepositoryIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity
    private val config: ServerConfig get() = database.config.copy(accountResetEnabled = true)

    @BeforeTest fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("reset-transaction").toPath())
        database.reset()
        identity = database.seedIdentity()
    }

    @AfterTest fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test fun concurrentOperationIdsCommitOnceAndRequireFreshAuthorityAfterTheWinner() {
        val firstId = AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION)
        val secondId = AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION)
        val request = initialRequest()
        val firstName = applicationName("reset_first")
        val secondName = applicationName("reset_second")
        val executor = Executors.newFixedThreadPool(2)
        try {
            // Observe both real PostgreSQL X waiters before releasing the S holder.
            val operations = AccountAdmission.transaction(provider(), listOf(identity.userId)) {
                val first = executor.submit<AccountDataResetReceiptResponse> {
                    AccountDataRepository(config, provider(database.configWithApplicationName(firstName)))
                        .reset(request, firstId, verified())
                }
                database.awaitAdvisoryLockWait(firstName, first::isDone)
                val second = executor.submit<AccountDataResetReceiptResponse> {
                    AccountDataRepository(config, provider(database.configWithApplicationName(secondName)))
                        .reset(request, secondId, verified())
                }
                database.awaitAdvisoryLockWait(secondName, second::isDone)
                first to second
            }
            val receipt = operations.first.get(30, TimeUnit.SECONDS)
            val loser = assertFailsWith<ExecutionException> { operations.second.get(30, TimeUnit.SECONDS) }
            assertEquals(AccountError.ACCOUNT_SESSION_STALE, assertIs<AccountProtocolFailure>(loser.cause).error)
            assertEquals(firstId.operationId.toString(), receipt.operationId)
            assertEquals(1L, count("someday_account_data_resets"))
            assertEquals(2L, count("someday_account_data_incarnations"))
            val fresh = freshRequest()
            val repository = AccountDataRepository(config)
            val mismatchingExpected = assertFailsWith<AccountProtocolFailure> {
                repository.reset(fresh.copy(protocol = AccountProtocolExpectation.V1(ACCOUNT_INITIAL_INCARNATION)), secondId, verified())
            }
            assertEquals(AccountError.ACCOUNT_INCARNATION_MISMATCH, mismatchingExpected.error)
            assertEquals(receipt.newIncarnation, repository.discover(fresh).accountIncarnation)
            assertNull(repository.receipt(fresh, secondId.operationId))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Reset workers did not stop." }
        }
    }

    @Test fun receiptInsertFailureRollsBackRetirementSuccessorAndRecoveryDeletionTogether() {
        seedRecoveryEnvelope()
        val failingConnections = interceptConnections { method, args, delegate ->
            if (method == "prepareStatement" && (args?.firstOrNull() as? String)?.startsWith("INSERT INTO someday_account_data_resets") == true) {
                throw SQLException("Injected receipt insertion failure", "XX000")
            }
            delegate()
        }
        val repository = AccountDataRepository(config, failingConnections)
        val operation = AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION)
        val failure = assertFailsWith<SQLException> { repository.reset(initialRequest(), operation, verified()) }
        assertEquals("XX000", failure.sqlState)
        assertEquals(0L, count("someday_account_data_resets"))
        assertEquals(1L, count("someday_account_data_incarnations"))
        assertEquals(ACCOUNT_INITIAL_INCARNATION.toString(), AccountDataRepository(config).discover(initialRequest()).accountIncarnation)
        assertEquals(RECOVERY_ENVELOPE, recoveryEnvelope())
        assertEquals(1L, database.countRows("someday_entity_workspaces", identity.userId, WORKSPACE))
        connection().use { connection ->
            connection.prepareStatement("SELECT state, retired_at, media_reclaimed_at FROM someday_account_data_incarnations WHERE user_id = ?").use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    assertEquals("active", result.getString("state"))
                    assertNull(result.getObject("retired_at"))
                    assertNull(result.getObject("media_reclaimed_at"))
                }
            }
        }
    }

    @Test fun completedCommitWithLostAcknowledgementRemainsDiscoverableAsExactlyOneReceipt() {
        seedRecoveryEnvelope()
        val operation = AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION)
        val uncertainConnections = interceptConnections { method, _, delegate ->
            val result = delegate()
            if (method == "commit") throw SQLException("Injected lost COMMIT acknowledgement", "08006")
            result
        }
        val failure = assertFailsWith<SQLException> {
            AccountDataRepository(config, uncertainConnections).reset(initialRequest(), operation, verified())
        }
        assertEquals("08006", failure.sqlState, "Unknown COMMIT must not be converted into account_busy.")
        assertEquals(1L, count("someday_account_data_resets"))
        assertEquals(2L, count("someday_account_data_incarnations"))
        assertNull(recoveryEnvelope())
        val repository = AccountDataRepository(config)
        assertEquals(
            AccountError.ACCOUNT_SESSION_STALE,
            assertFailsWith<AccountProtocolFailure> { repository.receipt(initialRequest(), operation.operationId) }.error,
        )
        val fresh = freshRequest()
        val receipt = assertNotNull(repository.receipt(fresh, operation.operationId))
        val replay = assertIs<AccountResetPrecheck.Replay>(
            repository.prepareReset(fresh.copy(protocol = AccountProtocolExpectation.V1(operation.expectedIncarnation)), operation),
        )
        assertEquals(receipt, replay.receipt)
        assertEquals(receipt.newIncarnation, repository.discover(fresh).accountIncarnation)
        assertEquals(1L, count("someday_account_data_resets"))
    }

    @Test fun threePerDayBudgetSurvivesReclamationAndReopensOnlyOutsideItsWindow() {
        val repository = AccountDataRepository(config)
        var request = initialRequest()
        repeat(3) {
            val expected = (request.protocol as AccountProtocolExpectation.V1).incarnation
            repository.reset(request, AccountResetIdentity(UUID.randomUUID(), expected), verified())
            request = freshRequest()
            // Test-only certification deliberately removes the independent media
            // gate; successful cleanup must not replenish the reset rate budget.
            AccountAdmission.transaction(provider(), listOf(identity.userId), AccountLockMode.EXCLUSIVE) { connection ->
                connection.prepareStatement(
                    "UPDATE someday_account_data_incarnations SET media_reclaimed_at = clock_timestamp() WHERE user_id = ? AND state = 'retired' AND media_reclaimed_at IS NULL",
                ).use { statement -> statement.setObject(1, identity.userId); assertEquals(1, statement.executeUpdate()) }
            }
        }
        val expected = (request.protocol as AccountProtocolExpectation.V1).incarnation
        val fourth = AccountResetIdentity(UUID.randomUUID(), expected)
        val failure = assertFailsWith<AccountProtocolFailure> { repository.reset(request, fourth, verified()) }
        assertEquals(AccountError.RESET_RATE_LIMITED, failure.error)
        assertEquals(3L, count("someday_account_data_resets"))
        assertEquals(expected.toString(), repository.discover(request).accountIncarnation)
        assertNull(repository.receipt(request, fourth.operationId))
        val later = AccountDataRepository(config, clock = Clock.offset(Clock.systemUTC(), Duration.ofHours(25)))
        val receipt = later.reset(request, fourth, verified())
        assertEquals(expected.toString(), receipt.previousIncarnation)
        assertEquals(4L, count("someday_account_data_resets"))
    }

    private fun initialRequest(): AccountRequestContext = identity.request.copy(
        protocol = AccountProtocolExpectation.V1(ACCOUNT_INITIAL_INCARNATION),
    )

    private fun freshRequest(): AccountRequestContext {
        val issued = AuthRepository(config).issuePasswordSession(
            verified(), refreshTokenHash = UUID.randomUUID().toString(),
            sessionExpiresAt = Instant.now().plusSeconds(3600), refreshExpiresAt = Instant.now().plusSeconds(3600),
        )
        return AccountRequestContext(
            identity.userId, issued.sessionId, null, scopesForDevice(null), AccountProtocolExpectation.V1(issued.incarnation),
        )
    }

    private fun verified() = VerifiedPasswordAccount(identity.userId, "test-only")

    private fun provider(value: ServerConfig = config) = DatabaseConnectionProvider {
        DriverManager.getConnection(value.databaseConnectionUrl, value.databaseUser, value.databasePassword)
    }

    private fun connection(): Connection = provider().connection()

    private fun interceptConnections(
        intercept: (String, Array<out Any?>?, () -> Any?) -> Any?,
    ): DatabaseConnectionProvider = DatabaseConnectionProvider {
        val delegate = connection()
        Connection::class.java.cast(
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                intercept(method.name, args) {
                    try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                }
            },
        )
    }

    private fun count(table: String): Long {
        require(table in setOf("someday_account_data_resets", "someday_account_data_incarnations"))
        return connection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE user_id = ?").use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { result -> check(result.next()); result.getLong(1) }
            }
        }
    }

    private fun seedRecoveryEnvelope() {
        AccountAdmission.transaction(provider(), listOf(identity.userId), scope = ::selectScope) { connection ->
            connection.prepareStatement("INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) VALUES (?, ?, ?)").use { statement ->
                statement.setObject(1, identity.userId)
                statement.setString(2, WORKSPACE)
                statement.setObject(3, ACCOUNT_INITIAL_INCARNATION)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO workspace_recovery_envelopes(user_id, workspace_id, key_fingerprint, envelope_json, envelope_digest, revision, created_by_device_id, updated_by_device_id) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.setString(2, WORKSPACE)
                statement.setString(3, "a".repeat(32))
                statement.setString(4, RECOVERY_ENVELOPE)
                statement.setString(5, "b".repeat(43))
                statement.setObject(6, identity.deviceId)
                statement.setObject(7, identity.deviceId)
                statement.executeUpdate()
            }
        }
    }

    private fun recoveryEnvelope(): String? = AccountAdmission.transaction(provider(), listOf(identity.userId), scope = ::selectScope) { connection ->
        connection.prepareStatement("SELECT envelope_json FROM workspace_recovery_envelopes WHERE user_id = ? AND workspace_id = ?").use { statement ->
            statement.setObject(1, identity.userId)
            statement.setString(2, WORKSPACE)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    private fun selectScope(connection: Connection) {
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, true), set_config('someday.workspace_id', ?, true)").use { statement ->
            statement.setString(1, identity.userId.toString())
            statement.setString(2, WORKSPACE)
            statement.executeQuery().close()
        }
    }

    private fun applicationName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(8)}"

    private companion object {
        const val WORKSPACE = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RECOVERY_ENVELOPE = "{\"wrappedKey\":\"preserved-until-commit\"}"
    }
}
