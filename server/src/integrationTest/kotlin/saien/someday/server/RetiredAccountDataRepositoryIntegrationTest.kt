package saien.someday.server

import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.persistence.MediaReclamationEvidence
import saien.someday.server.persistence.MediaReclamationRevalidationRequired
import saien.someday.server.persistence.ReclamationBackend
import saien.someday.server.persistence.RetiredAccountDataRepository
import saien.someday.server.persistence.RetiredAccountVerificationChanged
import saien.someday.server.support.IsolatedPostgresDatabase

class RetiredAccountDataRepositoryIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun cleanupIsBoundedFkSafeAndPreservesCurrentContentOtherAccountsAndPermanentClaims() {
        database().use { database ->
            val account = seedAccount(database)
            val control = seedAccount(database)
            val oldWorkspace = seedWorkspace(database, account, ACCOUNT_INITIAL_INCARNATION)
            val controlWorkspace = seedWorkspace(database, control, ACCOUNT_INITIAL_INCARNATION)
            val next = retire(database, account.userId)
            val currentWorkspace = seedWorkspace(database, account, next, recovery = false)
            val oldClaim = UUID.randomUUID()
            val currentSession = UUID.randomUUID()
            database.scopedTransaction { connection ->
                connection.execute("INSERT INTO someday_devices(id, user_id, name, platform, data_incarnation) VALUES (?, ?, 'Old private label', 'old-platform', ?)", oldClaim, account.userId, ACCOUNT_INITIAL_INCARNATION)
                connection.execute("UPDATE someday_devices SET data_incarnation = ?, name = 'Current laptop' WHERE id = ?", next, account.deviceId)
                connection.execute("INSERT INTO someday_sessions(id, user_id, expires_at, data_incarnation) VALUES (?, ?, NOW() + INTERVAL '1 hour', ?)", currentSession, account.userId, next)
                repeat(13) { index ->
                    connection.execute("INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, ?, NOW() + INTERVAL '1 hour')", UUID.randomUUID(), account.sessionId, "obsolete-$index")
                    connection.execute("INSERT INTO workspace_pairing_invites(user_id, invite_id, creator_device_id, envelope_json, envelope_digest, state, expires_at, data_incarnation) VALUES (?, ?, ?, 'old ciphertext', 'digest', 'available', NOW() + INTERVAL '1 hour', ?)", account.userId, "obsolete-$index", account.deviceId, ACCOUNT_INITIAL_INCARNATION)
                }
                connection.execute("INSERT INTO workspace_pairing_invites(user_id, invite_id, creator_device_id, envelope_json, envelope_digest, state, expires_at, data_incarnation) VALUES (?, 'current', ?, 'current ciphertext', 'digest', 'available', NOW() + INTERVAL '1 hour', ?)", account.userId, account.deviceId, next)
                connection.execute("INSERT INTO someday_account_data_resets(user_id, operation_id, protocol_version, expected_incarnation, new_incarnation, committed_at) VALUES (?, ?, 1, ?, ?, ?)", account.userId, UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, next, RETIRED_AT.atOffset(ZoneOffset.UTC))
            }
            val repository = RetiredAccountDataRepository(database.config, clock = fixedClock())
            val audit = repository.beginAudit(assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION)))
            var total = 0
            var iterations = 0
            while (true) {
                val rowsBefore = database.scopedTransaction(::contentAndCredentialRows)
                val changed = repository.purgeSqlBatch(audit, 3)
                assertTrue(changed in 0..3)
                val rowsAfter = database.scopedTransaction(::contentAndCredentialRows)
                // Count actual removed rows as well: a session CASCADE could
                // otherwise hide an unbounded refresh-token deletion behind 1.
                assertTrue(rowsBefore - rowsAfter in 0L..3L)
                total += changed
                if (changed == 0) break
                check(++iterations < 100)
            }
            assertTrue(total > 30)
            database.scopedTransaction { connection ->
                CONTENT_TABLES.forEach { table ->
                    assertEquals(0L, connection.count(table, account.userId, oldWorkspace), table)
                    assertEquals(1L, connection.count(table, account.userId, currentWorkspace), table)
                    assertEquals(1L, connection.count(table, control.userId, controlWorkspace), table)
                }
                assertEquals(1L, connection.count("someday_entity_workspaces", account.userId, oldWorkspace))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM workspace_pairing_invites WHERE user_id = '${account.userId}'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_refresh_tokens WHERE session_id = '${account.sessionId}'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_sessions WHERE id = '${account.sessionId}'"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_sessions WHERE id = '$currentSession'"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_account_data_resets WHERE user_id = '${account.userId}'"))
                assertEquals(2L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE user_id = '${account.userId}'"))
                assertEquals("Current laptop", connection.string("SELECT name FROM someday_devices WHERE id = '${account.deviceId}'"))
                assertEquals("Retired installation", connection.string("SELECT name FROM someday_devices WHERE id = '$oldClaim'"))
                assertEquals(2L, connection.number("SELECT COUNT(*) FROM someday_devices WHERE user_id = '${account.userId}'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM workspace_recovery_envelopes WHERE user_id = '${account.userId}'"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM workspace_recovery_envelopes WHERE user_id = '${control.userId}'"))
                assertNull(connection.string("SELECT media_reclaimed_at::text FROM someday_account_data_incarnations WHERE user_id = '${account.userId}' AND state = 'retired'"))
            }
        }
    }

    @Test
    fun auditSupersessionInterruptionAndLateObjectViolationRequireExplicitRevalidation() {
        database().use { database ->
            val account = seedAccount(database)
            val next = retire(database, account.userId)
            val repository = RetiredAccountDataRepository(database.config, clock = fixedClock())
            assertNull(repository.inspectTarget(account.userId, next))
            val target = assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))
            val first = repository.beginAudit(target)
            val replacement = repository.beginAudit(target)
            assertFailsWith<RetiredAccountVerificationChanged> { repository.validateAudit(first) }
            assertFailsWith<RetiredAccountVerificationChanged> { repository.certifyMediaReclaimed(first, filesystemEvidence()) }
            val certifiedAt = repository.certifyMediaReclaimed(replacement, filesystemEvidence())
            val previouslyCertified = assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))
            assertEquals(certifiedAt, previouslyCertified.previouslyReclaimedAt)
            assertEquals(previouslyCertified, repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))

            val interrupted = repository.beginAudit(previouslyCertified)
            assertEquals(certifiedAt, interrupted.previouslyReclaimedAt)
            // An interrupted audit has cleared the gate marker, but must retain
            // the evidence needed to recognize late objects on the next run.
            val resumed = repository.beginAudit(assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION)))
            assertEquals(certifiedAt, resumed.previouslyReclaimedAt)
            repository.recordAssumptionViolation(resumed)
            assertFailsWith<MediaReclamationRevalidationRequired> { repository.certifyMediaReclaimed(resumed, filesystemEvidence()) }
            val violated = assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))
            assertTrue(violated.revalidationRequired)
            assertFailsWith<MediaReclamationRevalidationRequired> { repository.beginAudit(violated) }
            val revalidated = repository.beginAudit(violated, operatorRevalidated = true)
            assertNull(revalidated.previouslyReclaimedAt)
            repository.certifyMediaReclaimed(revalidated, filesystemEvidence())
        }
    }

    @Test
    fun s3CertificationRequiresACompleteEmptyScanStartedAfterSettlementWithTrustedClock() {
        database().use { database ->
            val account = seedAccount(database)
            retire(database, account.userId)
            val clock = MutableClock(RETIRED_AT.plusSeconds(24 * 3600))
            val repository = RetiredAccountDataRepository(
                database.config.copy(mediaStorage = ServerMediaStorage.S3("test-only", "us-east-1", null, false)), clock = clock,
            )
            val audit = repository.beginAudit(assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION)))
            val barrier = clock.instant()
            val empty = MediaReclamationEvidence(ReclamationBackend.S3, barrier, barrier, true)
            assertFailsWith<IllegalArgumentException> { repository.certifyMediaReclaimed(audit, empty.copy(scanStartedAt = barrier.minusSeconds(1))) }
            assertFailsWith<IllegalArgumentException> { repository.certifyMediaReclaimed(audit, empty.copy(completeEmpty = false)) }
            assertFailsWith<IllegalArgumentException> { repository.certifyMediaReclaimed(audit, empty.copy(backend = ReclamationBackend.FILESYSTEM)) }
            assertFailsWith<IllegalArgumentException> { repository.certifyMediaReclaimed(audit, empty.copy(scanCompletedAt = barrier.plusSeconds(1))) }
            clock.now = barrier.plusSeconds(2)
            assertEquals(clock.instant(), repository.certifyMediaReclaimed(audit, empty.copy(scanCompletedAt = barrier.plusSeconds(1))))
        }
    }

    @Test
    fun restoreInvalidatesAllMarkersAndPlansBeforeMaintenanceCanResume() {
        database().use { database ->
            val account = seedAccount(database)
            val other = seedAccount(database)
            retire(database, account.userId)
            retire(database, other.userId)
            val repository = RetiredAccountDataRepository(database.config, clock = fixedClock())
            val audit = repository.beginAudit(assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION)))
            val otherAudit = repository.beginAudit(assertNotNull(repository.inspectTarget(other.userId, ACCOUNT_INITIAL_INCARNATION)))
            repository.certifyMediaReclaimed(audit, filesystemEvidence())
            repository.certifyMediaReclaimed(otherAudit, filesystemEvidence())
            assertFailsWith<RetiredAccountVerificationChanged> { repository.invalidateAfterRestore("another-database") }
            repository.invalidateAfterRestore(repository.databaseIdentity())
            assertFailsWith<RetiredAccountVerificationChanged> { repository.validateAudit(audit) }
            assertFailsWith<RetiredAccountVerificationChanged> { repository.purgeSqlBatch(otherAudit) }
            assertFailsWith<RetiredAccountVerificationChanged> { repository.beginAudit(audit.target, operatorRevalidated = true) }
            val restored = assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))
            assertNotEquals(audit.target.verificationContext, restored.verificationContext)
            assertNull(restored.previouslyReclaimedAt)
            assertTrue(restored.revalidationRequired)
            assertFailsWith<MediaReclamationRevalidationRequired> { repository.beginAudit(restored) }
            database.connection().use { connection ->
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE media_reclaimed_at IS NOT NULL OR media_audit_id IS NOT NULL"))
            }
        }
    }

    @Test
    fun contentBatchesUseSharedAdmissionAndMarkerChangesWaitForExistingPublishers() {
        database().use { database ->
            val account = seedAccount(database)
            retire(database, account.userId)
            val name = "retired_marker_${UUID.randomUUID().toString().replace("-", "")}"
            val connections = DatabaseConnectionProvider {
                database.connection().also { connection ->
                    connection.prepareStatement("SELECT set_config('application_name', ?, false)").use { statement ->
                        statement.setString(1, name)
                        statement.executeQuery().close()
                    }
                }
            }
            val repository = RetiredAccountDataRepository(database.config, connections, fixedClock())
            val target = assertNotNull(repository.inspectTarget(account.userId, ACCOUNT_INITIAL_INCARNATION))
            val audit = repository.beginAudit(target)
            val executor = Executors.newSingleThreadExecutor()
            try {
                database.connection().use { publisher ->
                    publisher.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                    publisher.autoCommit = false
                    AccountAdmission.lockAccounts(publisher, listOf(account.userId), AccountLockMode.SHARED)
                    assertTrue(repository.purgeSqlBatch(audit, 1) in 0..1)
                    val pending = executor.submit { repository.beginAudit(target) }
                    awaitLockWait(database, name, pending::isDone)
                    assertFalse(pending.isDone)
                    publisher.rollback()
                    pending.get(10, TimeUnit.SECONDS)
                }
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun database() = IsolatedPostgresDatabase(temporaryFolder.newFolder().toPath()).also {
        try { DatabaseMigrator.migrate(it.config) } catch (failure: Throwable) { it.close(); throw failure }
    }

    private fun seedAccount(database: IsolatedPostgresDatabase): Identity {
        val identity = Identity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        database.scopedTransaction { connection ->
            connection.execute("INSERT INTO someday_users(id, email, password_hash) VALUES (?, ?, 'test-only')", identity.userId, "${identity.userId}@example.com")
            connection.execute("INSERT INTO someday_devices(id, user_id, name, platform, data_incarnation) VALUES (?, ?, 'Private device label', 'test', ?)", identity.deviceId, identity.userId, ACCOUNT_INITIAL_INCARNATION)
            connection.execute("INSERT INTO someday_sessions(id, user_id, device_id, expires_at, data_incarnation) VALUES (?, ?, ?, NOW() + INTERVAL '1 hour', ?)", identity.sessionId, identity.userId, identity.deviceId, ACCOUNT_INITIAL_INCARNATION)
        }
        return identity
    }

    private fun retire(database: IsolatedPostgresDatabase, userId: UUID): UUID {
        val next = UUID.randomUUID()
        AccountAdmission.transaction(DatabaseConnectionProvider(database::connection), listOf(userId), AccountLockMode.EXCLUSIVE) { connection ->
            connection.execute("UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = ? WHERE user_id = ? AND state = 'active'", RETIRED_AT.atOffset(ZoneOffset.UTC), userId)
            connection.execute("INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', ?)", userId, next, RETIRED_AT.atOffset(ZoneOffset.UTC))
        }
        return next
    }

    private fun seedWorkspace(database: IsolatedPostgresDatabase, identity: Identity, incarnation: UUID, recovery: Boolean = true): String {
        val workspace = "workspace-${UUID.randomUUID().toString().replace("-", "")}"
        database.scopedTransaction { connection ->
            connection.execute("INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) VALUES (?, ?, ?)", identity.userId, workspace, incarnation)
            connection.execute("INSERT INTO someday_sync_v2_epochs(user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json, contract_id, schema_set_version, semantic_protocol_version, minimum_writer_protocol_version, key_set_version, remote_profile, metadata_privacy_mode, supported_offline_window_seconds, checkpoint_id, checkpoint_digest) VALUES (?, ?, 'epoch', 'pointer', 'ciphertext', 'someday-system-v2', 'workspace-entity-schema-set-v2', 2, 2, 'sync-key-set-v2', 'self-hosted-v2', 'opaque', 15552000, 'checkpoint', 'digest')", identity.userId, workspace)
            connection.execute("INSERT INTO someday_sync_v2_checkpoint_chunks(user_id, workspace_id, epoch_id, checkpoint_id, chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes, encrypted_object_json) VALUES (?, ?, 'epoch', 'checkpoint', 0, 'chunk', 'digest', 1, 64, 'ciphertext')", identity.userId, workspace)
            connection.execute("INSERT INTO someday_sync_v2_checkpoint_manifests(user_id, workspace_id, epoch_id, checkpoint_id, checkpoint_digest, chunk_count, total_object_count, chunk_refs_fingerprint, encrypted_object_json) VALUES (?, ?, 'epoch', 'checkpoint', 'digest', 1, 1, 'fingerprint', 'ciphertext')", identity.userId, workspace)
            connection.execute("INSERT INTO someday_sync_v2_objects(user_id, workspace_id, epoch_id, object_id, object_type, object_digest, mutation_id, first_writer_device_id, ciphertext_digest, encrypted_object_json) VALUES (?, ?, 'epoch', 'object', 'workspace_entity_version_v2', 'digest', 'mutation', ?, 'ciphertext-digest', 'ciphertext')", identity.userId, workspace, identity.deviceId)
            connection.execute("INSERT INTO someday_sync_v2_changes(user_id, workspace_id, epoch_id, object_id, object_digest, mutation_id) VALUES (?, ?, 'epoch', 'object', 'digest', 'mutation')", identity.userId, workspace)
            connection.execute("INSERT INTO someday_sync_v2_mutations(user_id, workspace_id, epoch_id, mutation_id, object_id, object_digest, cursor) VALUES (?, ?, 'epoch', 'mutation', 'object', 'digest', 1)", identity.userId, workspace)
            connection.execute("INSERT INTO someday_media_v3_objects(user_id, workspace_id, media_id, ciphertext_bytes, ciphertext_sha256, uploaded_by_device_id) VALUES (?, ?, ?, 64, ?, ?)", identity.userId, workspace, "a".repeat(64), "sha256:${"b".repeat(64)}", identity.deviceId)
            if (recovery) connection.execute("INSERT INTO workspace_recovery_envelopes(user_id, workspace_id, key_fingerprint, envelope_json, envelope_digest, revision, created_by_device_id, updated_by_device_id) VALUES (?, ?, ?, 'ciphertext', ?, 1, ?, ?)", identity.userId, workspace, "c".repeat(32), "d".repeat(43), identity.deviceId, identity.deviceId)
        }
        return workspace
    }

    private fun awaitLockWait(database: IsolatedPostgresDatabase, name: String, completed: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        database.connection().use { connection ->
            connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { statement ->
                statement.setString(1, name)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { result -> check(result.next()); if (result.getBoolean(1)) return }
                    check(!completed()) { "Operation completed before its expected PostgreSQL lock wait." }
                    Thread.yield()
                }
            }
        }
        error("Timed out observing PostgreSQL lock wait.")
    }

    private fun fixedClock(): Clock = Clock.fixed(RETIRED_AT.plusSeconds(100), ZoneOffset.UTC)
    private fun filesystemEvidence() = MediaReclamationEvidence(ReclamationBackend.FILESYSTEM, RETIRED_AT.plusSeconds(1), RETIRED_AT.plusSeconds(2), true)
    private fun Connection.execute(sql: String, vararg values: Any?) = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
    private fun Connection.number(sql: String): Long = createStatement().use { statement -> statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) } }
    private fun Connection.string(sql: String): String? = createStatement().use { statement -> statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) } }
    private fun Connection.count(table: String, user: UUID, workspace: String): Long = number("SELECT COUNT(*) FROM $table WHERE user_id = '$user' AND workspace_id = '$workspace'")
    private fun contentAndCredentialRows(connection: Connection): Long =
        (CONTENT_TABLES + listOf("workspace_recovery_envelopes", "workspace_pairing_invites", "someday_sessions", "someday_refresh_tokens"))
            .sumOf { table -> connection.number("SELECT COUNT(*) FROM $table") }
    private data class Identity(val userId: UUID, val deviceId: UUID, val sessionId: UUID)
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    companion object {
        private val RETIRED_AT = Instant.parse("2026-01-01T00:00:00Z")
        private val CONTENT_TABLES = listOf("someday_sync_v2_changes", "someday_sync_v2_mutations", "someday_sync_v2_objects", "someday_sync_v2_checkpoint_chunks", "someday_sync_v2_checkpoint_manifests", "someday_sync_v2_epochs", "someday_media_v3_objects")
    }
}
