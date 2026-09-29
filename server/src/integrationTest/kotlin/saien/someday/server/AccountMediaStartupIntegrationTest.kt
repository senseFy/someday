package saien.someday.server

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.support.IsolatedPostgresDatabase

class AccountMediaStartupIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun configuredStorageRequiresNestedProbeOnlyAfterAnActiveNonzeroIncarnationExists() {
        val root = temporaryFolder.newFolder("account-media-startup").toPath()
        val blocker = root.resolve(".incarnations")
        val legacyMarker = root.resolve(".someday-system/startup-probe-v1.bin")
        val nestedMarker = root.resolve(".incarnations/v1/.someday-system/startup-probe-v1.bin")
        Files.writeString(blocker, "Synthetic blocker: the nested namespace is not a directory.")

        IsolatedPostgresDatabase(root).use { database ->
            DatabaseMigrator.migrate(database.config)
            val userId = UUID.randomUUID()
            database.connection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO someday_users(id, email, password_hash) VALUES (?, 'startup@example.com', 'synthetic')",
                ).use { statement ->
                    statement.setObject(1, userId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            // Exercise the real composition: injecting a blob store bypasses startup probes.
            ServerContext.create(database.config).close()
            assertTrue(legacyMarker.isRegularFile())
            assertFalse(nestedMarker.isRegularFile())

            // Opt-in must verify the nested layout before the first reset, too.
            assertFailsWith<IllegalStateException> {
                ServerContext.create(database.config.copy(accountResetEnabled = true)).close()
            }

            // This fixture advances authority independently of the HTTP reset route.
            AccountAdmission.transaction(
                DatabaseConnectionProvider(database::connection),
                listOf(userId),
                AccountLockMode.EXCLUSIVE,
            ) { connection ->
                connection.prepareStatement(
                    "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'",
                ).use { statement ->
                    statement.setObject(1, userId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, UUID.randomUUID())
                    assertEquals(1, statement.executeUpdate())
                }
            }

            val failure = assertFailsWith<IllegalStateException> {
                ServerContext.create(database.config).close()
            }
            assertEquals(
                "Media storage metadata lookup did not prove the reserved missing key is absent.",
                failure.message,
            )
            assertTrue(legacyMarker.isRegularFile())

            Files.delete(blocker)
            ServerContext.create(database.config).close()
            assertTrue(nestedMarker.isRegularFile())
        }
    }
}
