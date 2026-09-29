package saien.someday.server

import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountDataRepository
import saien.someday.server.persistence.AccountResetIdentity
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.VerifiedPasswordAccount
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountResetBarrierIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("reset-barrier").toPath())
        database.reset()
        identity = database.seedIdentity()
    }

    @AfterTest fun tearDown() { if (::database.isInitialized) database.reset() }

    @Test fun discoveryCanRecoverReadinessWithoutHoldingItsAdmissionConnection() {
        val config = database.config.copy(accountResetEnabled = true, databaseMaxPoolSize = 1)
        DatabaseConnectionPool.create(config).use { pool ->
            var ready = false
            var probes = 0
            val repository = AccountDataRepository(config, pool, readiness = { ready }, verifyReadiness = {
                pool.connection().use { connection -> assertTrue(connection.autoCommit) }
                probes++
                ready = true
                true
            })
            assertTrue(repository.discover(identity.request).resetAvailable)
            assertEquals(1, probes)
            assertTrue(repository.discover(identity.request).resetAvailable)
            assertEquals(1, probes)
        }
    }

    @Test fun retirementClockStartsAfterAdmittedPublisherDrains() {
        val provider = DatabaseConnectionProvider {
            DriverManager.getConnection(database.config.databaseConnectionUrl, database.config.databaseUser, database.config.databasePassword)
        }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val resetName = "reset_clock_${UUID.randomUUID().toString().take(8)}"
        val resetConfig = database.configWithApplicationName(resetName).copy(accountResetEnabled = true)
        try {
            val publisher = executor.submit {
                AccountAdmission.transaction(provider, listOf(identity.userId)) {
                    held.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(30, TimeUnit.SECONDS))
            val reset = executor.submit {
                AccountDataRepository(resetConfig).reset(
                    identity.request.copy(protocol = AccountProtocolExpectation.V1(ACCOUNT_INITIAL_INCARNATION)),
                    AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION),
                    VerifiedPasswordAccount(identity.userId, "test-only"),
                )
            }
            database.awaitAdvisoryLockWait(resetName, reset::isDone)
            val stillBlockedAt = provider.connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT clock_timestamp()").use { result ->
                        check(result.next()); result.getObject(1, OffsetDateTime::class.java).toInstant()
                    }
                }
            }
            release.countDown()
            publisher.get(30, TimeUnit.SECONDS)
            reset.get(30, TimeUnit.SECONDS)
            val retiredAt = provider.connection().use { connection ->
                connection.prepareStatement("SELECT retired_at FROM someday_account_data_incarnations WHERE user_id = ? AND incarnation = ?").use { statement ->
                    statement.setObject(1, identity.userId)
                    statement.setObject(2, ACCOUNT_INITIAL_INCARNATION)
                    statement.executeQuery().use { result -> check(result.next()); result.getObject(1, OffsetDateTime::class.java).toInstant() }
                }
            }
            assertFalse(retiredAt.isBefore(stillBlockedAt), "Retirement must not use the transaction-start timestamp")
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun storageReadinessVerificationDoesNotHoldTheOnlyDatabaseConnection() {
        val config = database.config.copy(accountResetEnabled = true, databaseMaxPoolSize = 1)
        DatabaseConnectionPool.create(config).use { pool ->
            val probing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val repository = AccountDataRepository(config, pool, verifyReadiness = {
                probing.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                true
            })
            try {
                val reset = executor.submit {
                    repository.reset(
                        identity.request.copy(protocol = AccountProtocolExpectation.V1(ACCOUNT_INITIAL_INCARNATION)),
                        AccountResetIdentity(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION),
                        VerifiedPasswordAccount(identity.userId, "test-only"),
                    )
                }
                assertTrue(probing.await(30, TimeUnit.SECONDS))
                // This would exhaust checkout if the probe held the one connection.
                pool.connection().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1").use { result -> assertTrue(result.next()); assertEquals(1, result.getInt(1)) }
                    }
                }
                release.countDown()
                reset.get(30, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
            }
        }
    }
}
