package saien.someday.server

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DeviceRevokedException
import saien.someday.server.persistence.DeviceSessionRecord
import saien.someday.server.persistence.PairingInviteCreateResult
import saien.someday.server.persistence.VerifiedPasswordAccount
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountAuthIncarnationIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity
    private lateinit var repository: AuthRepository

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("account-auth").toPath())
        database.reset()
        identity = database.seedIdentity()
        repository = AuthRepository(database.config)
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun passwordSnapshotChangeRefusesIssuanceWithoutCreatingCredentials() {
        connection().use { connection ->
            connection.prepareStatement("UPDATE someday_users SET password_hash = ? WHERE id = ?").use { statement ->
                statement.setString(1, "changed-test-hash")
                statement.setObject(2, identity.userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val failure = assertFailsWith<AccountProtocolFailure> { login() }
        assertEquals(AccountError.UNAUTHORIZED, failure.error)
        assertEquals(1L, count("someday_sessions"))
        assertEquals(0L, count("someday_refresh_tokens", viaSession = true))
    }

    @Test
    fun oldSessionCannotRefreshOrRegisterAndReenrollmentPreservesItsIncarnation() {
        val oldRefresh = UUID.randomUUID().toString()
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, identity.sessionId)
                statement.setString(3, oldRefresh)
                statement.setObject(4, expiry().atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
        val current = advanceIncarnation()
        assertEquals(
            AccountError.ACCOUNT_SESSION_STALE,
            assertFailsWith<AccountProtocolFailure> {
                repository.rotateRefreshToken(oldRefresh, UUID.randomUUID().toString(), expiry())
            }.error,
        )
        assertEquals(
            AccountError.ACCOUNT_SESSION_STALE,
            assertFailsWith<AccountProtocolFailure> { register(identity.request) }.error,
        )
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT revoked_at FROM someday_refresh_tokens WHERE token_hash = ?",
            ).use { statement ->
                statement.setString(1, oldRefresh)
                statement.executeQuery().use { result ->
                    check(result.next())
                    assertNull(result.getObject(1))
                }
            }
        }

        val login = login()
        val enrolled = register(login)
        assertEquals(identity.deviceId, enrolled.device.id)
        assertEquals(current, enrolled.device.incarnation)
        assertEquals(current, enrolled.issuance.incarnation)
        assertEquals(current, repository.admitRequest(enrolled.request(), AccountAccess.SYNC).incarnation)
        connection().use { connection ->
            connection.prepareStatement("SELECT data_incarnation FROM someday_sessions WHERE id = ?").use { statement ->
                statement.setObject(1, identity.sessionId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    assertEquals(ACCOUNT_INITIAL_INCARNATION, result.getObject(1, UUID::class.java))
                }
            }
        }

        repository.revokeDevice(login, identity.deviceId)
        assertFailsWith<DeviceRevokedException> { register(login()) }
        assertEquals(current, repository.listDevices(login).single().incarnation)
    }

    @Test
    fun boundCurrentSessionCannotReenrollAnotherHistoricalDevice() {
        val historicalDevice = UUID.randomUUID()
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_devices(id, user_id, name, platform, data_incarnation) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, historicalDevice)
                statement.setObject(2, identity.userId)
                statement.setString(3, "Historical installation")
                statement.setString(4, "integration")
                statement.setObject(5, ACCOUNT_INITIAL_INCARNATION)
                statement.executeUpdate()
            }
        }
        advanceIncarnation()
        val currentBound = register(login()).request()
        val failure = assertFailsWith<AccountProtocolFailure> {
            register(currentBound, historicalDevice)
        }
        assertEquals(AccountError.FORBIDDEN, failure.error)
        val historical = repository.listDevices(currentBound).single { it.id == historicalDevice }
        assertEquals(ACCOUNT_INITIAL_INCARNATION, historical.incarnation)
    }

    @Test
    fun registrationCompletesWhileAnotherRequestHoldsSharedAccountAdmission() {
        val login = login()
        val executor = Executors.newSingleThreadExecutor()
        try {
            connection().use { otherRequest ->
                otherRequest.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                otherRequest.autoCommit = false
                AccountAdmission.lockAccounts(otherRequest, listOf(identity.userId), AccountLockMode.SHARED)
                try {
                    val registration = executor.submit<DeviceSessionRecord> { register(login) }
                    assertEquals(identity.deviceId, registration.get(10, TimeUnit.SECONDS).device.id)
                } finally {
                    otherRequest.rollback()
                }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test
    fun retiredInvitationsCannotReplayAndDoNotConsumeCurrentAllowance() {
        repeat(8) { index -> assertIs<PairingInviteCreateResult.Created>(invite(identity.request, "old", index)) }
        val current = advanceIncarnation()
        val currentRequest = register(login()).request()
        assertEquals(current, (currentRequest.protocol as AccountProtocolExpectation.V1).incarnation)
        assertEquals(
            AccountError.ACCOUNT_INCARNATION_MISMATCH,
            assertFailsWith<AccountProtocolFailure> { invite(currentRequest, "old", 0) }.error,
        )
        val oldId = inviteId("old", 0)
        listOf<() -> Unit>(
            { repository.claimWorkspacePairingInvite(currentRequest, oldId, "claim", Instant.now()) },
            { repository.completeWorkspacePairingInvite(currentRequest, oldId, "claim", Instant.now()) },
            { repository.cancelWorkspacePairingInvite(currentRequest, oldId, Instant.now()) },
        ).forEach { operation ->
            assertEquals(
                AccountError.ACCOUNT_INCARNATION_MISMATCH,
                assertFailsWith<AccountProtocolFailure> { operation() }.error,
            )
        }
        repeat(8) { index -> assertIs<PairingInviteCreateResult.Created>(invite(currentRequest, "new", index)) }
        assertEquals(PairingInviteCreateResult.LimitReached, invite(currentRequest, "new", 8))
        assertEquals(16L, count("workspace_pairing_invites"))
    }

    @Test
    fun concurrentInvitationCreationCannotExceedTheActiveLimit() {
        val creators = 12
        val barrier = CyclicBarrier(creators)
        val executor = Executors.newFixedThreadPool(creators)
        try {
            val pending = (0 until creators).map { index ->
                executor.submit<PairingInviteCreateResult> {
                    barrier.await(10, TimeUnit.SECONDS)
                    invite(identity.request, "parallel", index)
                }
            }
            val results = pending.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(8, results.count { it is PairingInviteCreateResult.Created })
            assertEquals(4, results.count { it == PairingInviteCreateResult.LimitReached })
            assertEquals(8L, count("workspace_pairing_invites"))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun login(): AccountRequestContext {
        val issued = repository.issuePasswordSession(
            VerifiedPasswordAccount(identity.userId, "test-only"),
            refreshTokenHash = UUID.randomUUID().toString(),
            sessionExpiresAt = expiry(),
            refreshExpiresAt = expiry(),
        )
        return AccountRequestContext(
            issued.userId, issued.sessionId, null, scopesForDevice(null),
            AccountProtocolExpectation.V1(issued.incarnation),
        )
    }

    private fun register(request: AccountRequestContext, deviceId: UUID = identity.deviceId): DeviceSessionRecord =
        repository.registerDevice(
            request, deviceId, "Re-enrolled installation", "integration",
            UUID.randomUUID().toString(), expiry(), expiry(),
        )

    private fun DeviceSessionRecord.request(): AccountRequestContext = AccountRequestContext(
        issuance.userId, issuance.sessionId, device.id, scopesForDevice(device.id),
        AccountProtocolExpectation.V1(issuance.incarnation),
    )

    private fun invite(request: AccountRequestContext, prefix: String, index: Int): PairingInviteCreateResult =
        repository.createWorkspacePairingInvite(
            request, inviteId(prefix, index), "{}", "test-digest", Instant.now().plusSeconds(600), 8,
        )

    private fun inviteId(prefix: String, index: Int): String = (prefix + index).padEnd(22, 'a')
    private fun expiry(): Instant = Instant.now().plusSeconds(3600)

    /** Simulates only the authority commit; no reset endpoint or real account is involved. */
    private fun advanceIncarnation(): UUID {
        val next = UUID.randomUUID()
        AccountAdmission.transaction(
            DatabaseConnectionProvider(::connection), listOf(identity.userId), AccountLockMode.EXCLUSIVE,
        ) { connection ->
            connection.prepareStatement(
                "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() " +
                    "WHERE user_id = ? AND state = 'active'",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) " +
                    "VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.setObject(2, next)
                statement.executeUpdate()
            }
        }
        return next
    }

    private fun count(table: String, viaSession: Boolean = false): Long {
        require(table in setOf("someday_sessions", "someday_refresh_tokens", "workspace_pairing_invites"))
        val sql = if (viaSession) {
            "SELECT COUNT(*) FROM someday_refresh_tokens rt JOIN someday_sessions s ON s.id = rt.session_id WHERE s.user_id = ?"
        } else {
            "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?"
        }
        return connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(
        database.config.databaseConnectionUrl, database.config.databaseUser, database.config.databasePassword,
    )
}
