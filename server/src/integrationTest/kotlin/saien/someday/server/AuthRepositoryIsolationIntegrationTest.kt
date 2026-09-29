package saien.someday.server

import java.sql.Connection
import java.sql.DriverManager
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
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DeviceRevokedException
import saien.someday.server.persistence.DeviceSessionRecord
import saien.someday.server.persistence.RefreshSessionSnapshot
import saien.someday.server.persistence.VerifiedPasswordAccount
import saien.someday.server.auth.AccountRequestContext
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.RepeatableReadConnectionProvider
import saien.someday.server.support.TestServerIdentity

class AuthRepositoryIsolationIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("auth-isolation").toPath())
        database.reset()
        identity = database.seedIdentity("auth-isolation-${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun refreshWaiterObservesRevocationDespiteRepeatableReadConnectionDefault() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, identity.sessionId)
                statement.setString(3, OLD_REFRESH_HASH)
                statement.setObject(4, expiresAt.atOffset(java.time.ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
        val applicationName = applicationName("rr_refresh")
        val config = database.configWithApplicationName(applicationName)
        val repository = AuthRepository(config, RepeatableReadConnectionProvider(config))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = connection().use { revocation ->
                revocation.autoCommit = false
                val backendId = backendId(revocation)
                revocation.prepareStatement(
                    "UPDATE someday_refresh_tokens SET revoked_at = NOW() WHERE token_hash = ?",
                ).use { statement ->
                    statement.setString(1, OLD_REFRESH_HASH)
                    assertEquals(1, statement.executeUpdate())
                }
                val pending = executor.submit<RefreshSessionSnapshot?> {
                    repository.rotateRefreshToken(OLD_REFRESH_HASH, NEW_REFRESH_HASH, expiresAt, now)
                }
                database.awaitTransactionLockWait(applicationName, backendId, pending::isDone)
                revocation.commit()
                pending
            }

            // The operation must interpret the newly committed revocation, not
            // expose a serialization failure or issue a replacement credential.
            assertNull(refresh.get(30, TimeUnit.SECONDS))
            assertEquals(0L, refreshTokenCount(NEW_REFRESH_HASH))
            assertEquals(1L, sessionCount())
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Refresh test worker did not stop." }
        }
    }

    @Test
    fun refreshRechecksSessionAfterWaitingForAnUnchangedTokenRow() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, identity.sessionId)
                statement.setString(3, OLD_REFRESH_HASH)
                statement.setObject(4, expiresAt.atOffset(java.time.ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
        val applicationName = applicationName("refresh_session_recheck")
        val config = database.configWithApplicationName(applicationName)
        val repository = AuthRepository(config, RepeatableReadConnectionProvider(config))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = connection().use { blocker ->
                blocker.autoCommit = false
                val backendId = backendId(blocker)
                blocker.prepareStatement(
                    "SELECT id FROM someday_refresh_tokens WHERE token_hash = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, OLD_REFRESH_HASH)
                    statement.executeQuery().use { result -> check(result.next()) }
                }
                val pending = executor.submit<RefreshSessionSnapshot?> {
                    repository.rotateRefreshToken(OLD_REFRESH_HASH, NEW_REFRESH_HASH, expiresAt, now)
                }
                database.awaitTransactionLockWait(applicationName, backendId, pending::isDone)
                // Keep rt itself unchanged: refreshing only its locked row must
                // not let the earlier joined session snapshot authorize issuance.
                blocker.prepareStatement(
                    "UPDATE someday_sessions SET revoked_at = NOW() WHERE id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setObject(1, identity.sessionId)
                    statement.setObject(2, identity.userId)
                    assertEquals(1, statement.executeUpdate())
                }
                blocker.commit()
                pending
            }
            assertNull(refresh.get(30, TimeUnit.SECONDS))
            assertEquals(0L, refreshTokenCount(NEW_REFRESH_HASH))
            assertEquals(1L, sessionCount())
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Refresh test worker did not stop." }
        }
    }

    @Test
    fun registrationWaiterObservesDeviceRevocationDespiteRepeatableReadConnectionDefault() {
        val expiresAt = Instant.now().plusSeconds(3600)
        val applicationName = applicationName("rr_register")
        val config = database.configWithApplicationName(applicationName)
        val repository = AuthRepository(config, RepeatableReadConnectionProvider(config))
        val issued = repository.issuePasswordSession(
            verified = VerifiedPasswordAccount(identity.userId, "test-only"),
            refreshTokenHash = "c".repeat(64),
            sessionExpiresAt = expiresAt,
            refreshExpiresAt = expiresAt,
        )
        val loginRequest = AccountRequestContext(identity.userId, issued.sessionId, null, scopesForDevice(null))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val registration = connection().use { revocation ->
                revocation.autoCommit = false
                val backendId = backendId(revocation)
                revocation.prepareStatement(
                    "UPDATE someday_devices SET revoked_at = NOW() WHERE id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setObject(1, identity.deviceId)
                    statement.setObject(2, identity.userId)
                    assertEquals(1, statement.executeUpdate())
                }
                val pending = executor.submit<DeviceSessionRecord> {
                    repository.registerDevice(
                        loginRequest,
                        identity.deviceId,
                        "Retried installation",
                        "integration",
                        NEW_REFRESH_HASH,
                        expiresAt,
                        expiresAt,
                    )
                }
                database.awaitTransactionLockWait(applicationName, backendId, pending::isDone)
                revocation.commit()
                pending
            }

            val failure = assertFailsWith<ExecutionException> { registration.get(30, TimeUnit.SECONDS) }
            assertIs<DeviceRevokedException>(failure.cause)
            assertNotNull(repository.listDevices(loginRequest).single().revokedAt)
            assertEquals(1L, sessionCount())
            assertEquals(0L, refreshTokenCount(NEW_REFRESH_HASH))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Registration test worker did not stop." }
        }
    }

    private fun applicationName(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun connection(): Connection = DriverManager.getConnection(
        database.config.databaseConnectionUrl,
        database.config.databaseUser,
        database.config.databasePassword,
    )

    private fun backendId(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_backend_pid()").use { result ->
            check(result.next())
            result.getInt(1)
        }
    }

    private fun sessionCount(): Long = connection().use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM someday_sessions WHERE user_id = ? AND device_id = ?",
        ).use { statement ->
            statement.setObject(1, identity.userId)
            statement.setObject(2, identity.deviceId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    private fun refreshTokenCount(hash: String): Long = connection().use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM someday_refresh_tokens WHERE token_hash = ?",
        ).use { statement ->
            statement.setString(1, hash)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    private companion object {
        val OLD_REFRESH_HASH = "a".repeat(64)
        val NEW_REFRESH_HASH = "b".repeat(64)
    }
}
