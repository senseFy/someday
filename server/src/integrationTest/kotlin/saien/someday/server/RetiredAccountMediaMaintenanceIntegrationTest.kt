package saien.someday.server

import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.maintenance.FileSystemRetiredMediaStore
import saien.someday.server.maintenance.MaintenanceMediaFailure
import saien.someday.server.maintenance.MaintenanceMediaFailureReason
import saien.someday.server.maintenance.PurgeRetiredAccountData
import saien.someday.server.maintenance.RetiredAccountPurgeRequest
import saien.someday.server.maintenance.RetiredAccountPurgeStatus
import saien.someday.server.maintenance.RetiredMediaNamespace
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.persistence.MediaReclamationRevalidationRequired
import saien.someday.server.persistence.RetiredAccountDataRepository
import saien.someday.server.support.IsolatedPostgresDatabase

class RetiredAccountMediaMaintenanceIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun realStorageAuditBindsTheRootAndPersistsLateObjectInvalidationAcrossRuns() {
        val root = temporaryFolder.newFolder("retired-media").toPath().toRealPath()
        IsolatedPostgresDatabase(root).use { database ->
            DatabaseMigrator.migrate(database.config)
            val userId = UUID.randomUUID()
            val current = UUID.randomUUID()
            database.connection().use { connection ->
                connection.prepareStatement("INSERT INTO someday_users(id, email, password_hash) VALUES (?, 'maintenance@example.com', 'synthetic')").use {
                    it.setObject(1, userId)
                    it.executeUpdate()
                }
            }
            AccountAdmission.transaction(DatabaseConnectionProvider(database::connection), listOf(userId), AccountLockMode.EXCLUSIVE) { connection ->
                connection.prepareStatement("UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'").use {
                    it.setObject(1, userId)
                    assertEquals(1, it.executeUpdate())
                }
                connection.prepareStatement("INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())").use {
                    it.setObject(1, userId)
                    it.setObject(2, current)
                    it.executeUpdate()
                }
            }
            val namespace = RetiredMediaNamespace(userId, ACCOUNT_INITIAL_INCARNATION, "legacy")
            val orphan = root.resolve(namespace.relativeRoot).resolve("unindexed/.media-upload-abandoned.tmp")
            val currentFile = root.resolve(RetiredMediaNamespace(userId, current, "incarnation-v1").relativeRoot).resolve("active.bin")
            val otherFile = root.resolve(UUID.randomUUID().toString()).resolve("other.bin")
            listOf(orphan, currentFile, otherFile).forEach {
                Files.createDirectories(it.parent)
                Files.writeString(it, "synthetic")
            }
            val repository = RetiredAccountDataRepository(database.config)
            val media = FileSystemRetiredMediaStore(root, batchSize = 1)
            val service = PurgeRetiredAccountData(repository, media)
            val secureTraversal = Files.newDirectoryStream(root.root).use { it is SecureDirectoryStream<*> }
            if (System.getenv("SOMEDAY_REQUIRE_SECURE_DIRECTORY_STREAM") == "true") {
                assertTrue(secureTraversal, "This gate requires actual secure directory traversal support.")
            }
            if (!secureTraversal) {
                assertEquals(MaintenanceMediaFailureReason.UNSAFE_PATH, assertFailsWith<MaintenanceMediaFailure> {
                    service.run(RetiredAccountPurgeRequest(userId, ACCOUNT_INITIAL_INCARNATION))
                }.reason)
                assertTrue(Files.isRegularFile(orphan))
                assertTrue(Files.isRegularFile(currentFile))
                database.connection().use { connection ->
                    connection.prepareStatement("SELECT media_audit_id, media_reclaimed_at FROM someday_account_data_incarnations WHERE user_id = ? AND state = 'retired'").use {
                        it.setObject(1, userId)
                        it.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertNull(row.getObject(1))
                            assertNull(row.getObject(2))
                        }
                    }
                }
                return@use
            }
            val dryRun = service.run(RetiredAccountPurgeRequest(userId, ACCOUNT_INITIAL_INCARNATION))
            assertEquals(RetiredAccountPurgeStatus.DRY_RUN, dryRun.status)
            assertTrue(Files.exists(orphan))
            val request = RetiredAccountPurgeRequest(
                userId, ACCOUNT_INITIAL_INCARNATION,
                expectedDatabaseIdentity = dryRun.target.databaseIdentity,
                expectedVerificationContext = dryRun.target.verificationContext,
                expectedStorageIdentity = dryRun.storageIdentity,
                execute = true, publishersDrained = true, profileAttested = true,
            )
            val wrongRoot = temporaryFolder.newFolder("wrong-empty-media").toPath().toRealPath()
            assertFailsWith<IllegalArgumentException> {
                PurgeRetiredAccountData(repository, FileSystemRetiredMediaStore(wrongRoot)).run(request)
            }
            database.connection().use { connection ->
                connection.prepareStatement("SELECT media_audit_id FROM someday_account_data_incarnations WHERE user_id = ? AND state = 'retired'").use {
                    it.setObject(1, userId)
                    it.executeQuery().use { row -> assertTrue(row.next()); assertNull(row.getObject(1)) }
                }
            }
            assertEquals(RetiredAccountPurgeStatus.RECLAIMED, service.run(request).status)
            assertFalse(Files.exists(orphan))
            assertTrue(Files.exists(currentFile))
            assertTrue(Files.exists(otherFile))
            assertNotNull(assertNotNull(repository.inspectTarget(userId, ACCOUNT_INITIAL_INCARNATION)).previouslyReclaimedAt)

            // A new physical orphan contradicts the prior certificate, even though SQL remains empty.
            Files.writeString(orphan, "synthetic-late-publication")
            assertEquals(RetiredAccountPurgeStatus.ASSUMPTION_VIOLATION, service.run(request).status)
            assertTrue(assertNotNull(repository.inspectTarget(userId, ACCOUNT_INITIAL_INCARNATION)).revalidationRequired)
            assertFailsWith<MediaReclamationRevalidationRequired> { service.run(request) }
            assertTrue(Files.exists(orphan))
        }
    }
}
