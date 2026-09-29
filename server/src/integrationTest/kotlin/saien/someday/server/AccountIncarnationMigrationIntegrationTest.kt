package saien.someday.server

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.api.FlywayException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.support.IsolatedPostgresDatabase

class AccountIncarnationMigrationIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nonemptyV9UpgradePreservesEveryLegacyRowAndScopesTheBackfill() {
        database().use { database ->
            database.migrateToV9()
            database.scopedTransaction(::seedV9)
            val before = database.scopedTransaction(::legacyRows)

            DatabaseMigrator.migrate(database.config)

            database.scopedTransaction { connection ->
                assertEquals(before, legacyRows(connection))
                assertEquals(2L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations"))
                assertEquals(2L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE incarnation = '$G0' AND state = 'active' AND storage_layout = 'legacy' AND retired_at IS NULL AND media_reclaimed_at IS NULL"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_account_data_resets"))
                INCARNATION_TABLES.forEach { table ->
                    assertEquals(0L, connection.number("SELECT COUNT(*) FROM $table WHERE data_incarnation <> '$G0' OR data_incarnation IS NULL"))
                    assertEquals("NO", connection.string("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$table' AND column_name = 'data_incarnation'"))
                    assertNull(connection.string("SELECT column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$table' AND column_name = 'data_incarnation'"))
                }
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM pg_class WHERE relname IN ('someday_account_data_incarnations', 'someday_account_data_resets') AND (relrowsecurity OR relforcerowsecurity)"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid WHERE c.relname IN ('someday_account_data_incarnations', 'someday_account_data_resets')"))
                DatabaseMigrator.verifyRlsCatalog(connection)
                assertEquals("10", connection.string("SELECT version FROM flyway_schema_history WHERE version = '10' AND success"))
            }
            // Opening the already upgraded schema cannot duplicate G0 or reapply a backfill.
            DatabaseMigrator.migrate(database.config)
            assertEquals(before, database.scopedTransaction(::legacyRows))
        }
    }

    @Test
    fun accountCreationTriggerCoversAuthAdminMultirowAndRollbackWithoutApplicationInserts() {
        database().use { database ->
            DatabaseMigrator.migrate(database.config)
            val repository = AuthRepository(database.config)
            val normal = assertNotNull(repository.createUser("normal@example.com", "synthetic-hash"))
            val admin = assertNotNull(repository.createAdminUser("admin@example.com", "synthetic-hash"))
            assertTrue(admin.isAdmin)
            assertFalse(normal.isAdmin)
            database.scopedTransaction { connection ->
                connection.execute("INSERT INTO someday_users(id, email, password_hash) VALUES (?, 'first@example.com', 'synthetic'), (?, 'second@example.com', 'synthetic')", USER_A, USER_B)
                assertEquals(4L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE incarnation = '$G0' AND state = 'active'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_users u LEFT JOIN someday_account_data_incarnations i ON i.user_id = u.id AND i.incarnation = '$G0' WHERE i.user_id IS NULL OR i.created_at <> u.created_at"))
            }
            val rolledBack = UUID.randomUUID()
            assertFailsWith<IntentionalRollback> {
                database.scopedTransaction { connection ->
                    connection.execute("INSERT INTO someday_users(id, email, password_hash) VALUES (?, 'rollback@example.com', 'synthetic')", rolledBack)
                    assertEquals(1L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE user_id = '$rolledBack'"))
                    throw IntentionalRollback()
                }
            }
            database.connection().use { connection ->
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_users WHERE id = '$rolledBack'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM someday_account_data_incarnations WHERE user_id = '$rolledBack'"))
            }
        }
    }

    @Test
    fun incarnationOwnershipAndUniqueActiveConstraintsPreserveHistoricalSessionAuthority() {
        upgradedDatabase().use { database ->
            val successor = UUID.randomUUID()
            assertSqlState("23505") {
                database.scopedTransaction { connection ->
                    connection.execute("INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())", USER_A, successor)
                }
            }
            database.scopedTransaction { connection ->
                connection.execute("UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'", USER_A)
                connection.execute("INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())", USER_A, successor)
                connection.execute("UPDATE someday_devices SET data_incarnation = ? WHERE id = ? AND user_id = ?", successor, DEVICE_A, USER_A)
                assertEquals(G0.toString(), connection.string("SELECT data_incarnation::text FROM someday_sessions WHERE id = '$SESSION_A'"))
                assertEquals(G0.toString(), connection.string("SELECT data_incarnation::text FROM workspace_pairing_invites WHERE user_id = '$USER_A' LIMIT 1"))
                assertEquals(G0.toString(), connection.string("SELECT data_incarnation::text FROM someday_entity_workspaces WHERE user_id = '$USER_A' LIMIT 1"))
                connection.execute("INSERT INTO someday_account_data_resets(user_id, operation_id, protocol_version, expected_incarnation, new_incarnation, committed_at) VALUES (?, ?, 1, ?, ?, clock_timestamp())", USER_A, UUID.randomUUID(), G0, successor)
            }
            assertSqlState("23503") {
                database.scopedTransaction { connection ->
                    connection.execute("UPDATE someday_devices SET data_incarnation = ? WHERE id = ?", successor, DEVICE_B)
                }
            }
            assertSqlState("23503") {
                database.scopedTransaction { connection ->
                    connection.execute("INSERT INTO someday_account_data_resets(user_id, operation_id, protocol_version, expected_incarnation, new_incarnation, committed_at) VALUES (?, ?, 1, ?, ?, clock_timestamp())", USER_B, UUID.randomUUID(), G0, successor)
                }
            }
            assertSqlState("23505") {
                database.scopedTransaction { connection ->
                    connection.execute("INSERT INTO someday_account_data_resets(user_id, operation_id, protocol_version, expected_incarnation, new_incarnation, committed_at) VALUES (?, ?, 1, ?, ?, clock_timestamp())", USER_A, UUID.randomUUID(), G0, successor)
                }
            }
            assertSqlState("23503") {
                database.scopedTransaction { connection ->
                    connection.execute("DELETE FROM someday_account_data_incarnations WHERE user_id = ? AND incarnation = ?", USER_A, G0)
                }
            }
            assertSqlState("23502") {
                database.scopedTransaction { connection ->
                    connection.execute("INSERT INTO someday_devices(id, user_id, name, platform) VALUES (?, ?, 'missing incarnation', 'test')", UUID.randomUUID(), USER_A)
                }
            }
        }
    }

    @Test
    fun everyDeviceReferenceRejectsAnotherAccountsDevice() {
        upgradedDatabase().use { database ->
            listOf(
                "someday_sessions" to "device_id",
                "workspace_pairing_invites" to "creator_device_id",
                "workspace_pairing_invites" to "claim_device_id",
                "someday_sync_v2_objects" to "first_writer_device_id",
                "someday_media_v3_objects" to "uploaded_by_device_id",
                "workspace_recovery_envelopes" to "created_by_device_id",
                "workspace_recovery_envelopes" to "updated_by_device_id",
            ).forEach { (table, column) ->
                assertSqlState("23503") {
                    database.scopedTransaction { connection ->
                        val present = if (column == "claim_device_id") " AND claim_device_id IS NOT NULL" else ""
                        connection.execute("UPDATE $table SET $column = ? WHERE user_id = ?$present", DEVICE_B, USER_A)
                    }
                }
            }
            database.scopedTransaction { connection ->
                connection.execute("UPDATE someday_sessions SET device_id = NULL WHERE id = ?", SESSION_A)
                assertNull(connection.string("SELECT device_id::text FROM someday_sessions WHERE id = '$SESSION_A'"))
                assertEquals(USER_A.toString(), connection.string("SELECT user_id::text FROM someday_sessions WHERE id = '$SESSION_A'"))
            }
        }
    }

    @Test
    fun compositeDeviceReferencesPreserveNullCascadeAndRestrictDeletionSemantics() {
        upgradedDatabase().use { database ->
            val nullableDevice = UUID.randomUUID()
            val invitationCreator = UUID.randomUUID()
            database.scopedTransaction { connection ->
                connection.insertDevice(nullableDevice)
                connection.insertDevice(invitationCreator)
                connection.execute("UPDATE someday_sessions SET device_id = ? WHERE id = ?", nullableDevice, SESSION_A)
                connection.execute("UPDATE workspace_recovery_envelopes SET created_by_device_id = ?, updated_by_device_id = ? WHERE user_id = ?", nullableDevice, nullableDevice, USER_A)
                connection.execute("DELETE FROM someday_devices WHERE id = ? AND user_id = ?", nullableDevice, USER_A)
                assertNull(connection.string("SELECT device_id::text FROM someday_sessions WHERE id = '$SESSION_A'"))
                assertEquals(USER_A.toString(), connection.string("SELECT user_id::text FROM someday_sessions WHERE id = '$SESSION_A'"))
                assertNull(connection.string("SELECT created_by_device_id::text FROM workspace_recovery_envelopes WHERE user_id = '$USER_A'"))
                assertNull(connection.string("SELECT updated_by_device_id::text FROM workspace_recovery_envelopes WHERE user_id = '$USER_A'"))
                assertEquals(1L, connection.number("SELECT COUNT(*) FROM workspace_recovery_envelopes WHERE user_id = '$USER_A'"))
                connection.execute("UPDATE workspace_pairing_invites SET creator_device_id = ? WHERE user_id = ? AND invite_id = 'available'", invitationCreator, USER_A)
                connection.execute("DELETE FROM someday_devices WHERE id = ? AND user_id = ?", invitationCreator, USER_A)
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM workspace_pairing_invites WHERE user_id = '$USER_A' AND invite_id = 'available'"))
            }
            listOf(
                Triple("workspace_pairing_invites", "claim_device_id", " AND claim_device_id IS NOT NULL"),
                Triple("someday_sync_v2_objects", "first_writer_device_id", ""),
                Triple("someday_media_v3_objects", "uploaded_by_device_id", ""),
            ).forEach { (table, column, suffix) ->
                val referenced = UUID.randomUUID()
                database.scopedTransaction { connection ->
                    connection.insertDevice(referenced)
                    connection.execute("UPDATE $table SET $column = ? WHERE user_id = ?$suffix", referenced, USER_A)
                }
                assertSqlState("23503") {
                    database.scopedTransaction { connection ->
                        connection.execute("DELETE FROM someday_devices WHERE id = ? AND user_id = ?", referenced, USER_A)
                    }
                }
            }
        }
    }

    @Test
    fun invalidLegacyDeviceOwnershipFailsTheWholeUpgradeWithoutRepairingData() {
        database().use { database ->
            database.migrateToV9()
            database.scopedTransaction { connection ->
                seedV9(connection)
                // V9 only references device_id and therefore accepts this invalid ownership.
                connection.execute("UPDATE someday_sessions SET device_id = ? WHERE id = ?", DEVICE_B, SESSION_A)
            }
            val before = database.scopedTransaction(::legacyRows)
            assertFailsWith<FlywayException> { DatabaseMigrator.migrate(database.config) }
            database.scopedTransaction { connection ->
                assertEquals(before, legacyRows(connection))
                assertEquals("9", connection.string("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"))
                assertNull(connection.string("SELECT to_regclass('public.someday_account_data_incarnations')::text"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'data_incarnation'"))
                assertEquals(0L, connection.number("SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'someday_users_initialize_incarnation'"))
                DatabaseMigrator.verifyRlsCatalog(connection)
            }
        }
    }

    private fun database() = IsolatedPostgresDatabase(temporaryFolder.newFolder().toPath())

    private fun upgradedDatabase(): IsolatedPostgresDatabase = database().also { database ->
        try {
            database.migrateToV9()
            database.scopedTransaction(::seedV9)
            DatabaseMigrator.migrate(database.config)
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    private fun seedV9(connection: Connection) {
        connection.execute("INSERT INTO someday_users(id, email, password_hash, is_admin, disabled_at) VALUES (?, 'legacy-a@example.com', 'synthetic-a', true, NULL), (?, 'legacy-b@example.com', 'synthetic-b', false, NULL)", USER_A, USER_B)
        connection.execute("INSERT INTO someday_devices(id, user_id, name, platform, revoked_at) VALUES (?, ?, 'legacy writer', 'test', NULL), (?, ?, 'revoked control', 'test', TIMESTAMPTZ '2020-01-01 00:00:00Z')", DEVICE_A, USER_A, DEVICE_B, USER_B)
        connection.execute("INSERT INTO someday_sessions(id, user_id, device_id, expires_at) VALUES (?, ?, ?, TIMESTAMPTZ '2099-01-01 00:00:00Z'), (?, ?, NULL, TIMESTAMPTZ '2099-01-01 00:00:00Z')", SESSION_A, USER_A, DEVICE_A, SESSION_B, USER_B)
        connection.execute("INSERT INTO someday_refresh_tokens(id, session_id, token_hash, expires_at) VALUES (?, ?, 'synthetic-refresh-a', TIMESTAMPTZ '2099-01-01 00:00:00Z')", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa03"), SESSION_A)
        connection.execute("INSERT INTO someday_entity_workspaces(user_id, workspace_id) VALUES (?, ?), (?, ?), (?, ?)", USER_A, WORKSPACE_A, USER_A, WORKSPACE_A2, USER_B, WORKSPACE_B)
        connection.execute(
            """
            INSERT INTO someday_sync_v2_epochs(
                user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json,
                contract_id, schema_set_version, semantic_protocol_version, minimum_writer_protocol_version,
                key_set_version, remote_profile, metadata_privacy_mode, supported_offline_window_seconds,
                checkpoint_id, checkpoint_digest
            ) VALUES (?, ?, 'epoch-a', 'pointer-a', '{"legacy":"pointer"}', 'someday-system-v2',
                'workspace-entity-schema-set-v2', 2, 2, 'sync-key-set-v2', 'self-hosted-v2', 'opaque',
                15552000, 'checkpoint-a', 'checkpoint-digest-a')
            """.trimIndent(), USER_A, WORKSPACE_A,
        )
        connection.execute("INSERT INTO someday_sync_v2_checkpoint_chunks(user_id, workspace_id, epoch_id, checkpoint_id, chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes, encrypted_object_json) VALUES (?, ?, 'epoch-a', 'checkpoint-a', 0, 'chunk-a', 'chunk-digest-a', 1, 64, '{\"legacy\":\"chunk\"}')", USER_A, WORKSPACE_A)
        connection.execute("INSERT INTO someday_sync_v2_checkpoint_manifests(user_id, workspace_id, epoch_id, checkpoint_id, checkpoint_digest, chunk_count, total_object_count, chunk_refs_fingerprint, encrypted_object_json) VALUES (?, ?, 'epoch-a', 'checkpoint-a', 'checkpoint-digest-a', 1, 1, 'refs-a', '{\"legacy\":\"manifest\"}')", USER_A, WORKSPACE_A)
        connection.execute("INSERT INTO someday_sync_v2_objects(user_id, workspace_id, epoch_id, object_id, object_type, object_digest, mutation_id, first_writer_device_id, ciphertext_digest, encrypted_object_json, cursor) VALUES (?, ?, 'epoch-a', 'object-a', 'workspace_entity_version_v2', 'object-digest-a', 'mutation-a', ?, 'ciphertext-a', '{\"legacy\":\"object\"}', 1)", USER_A, WORKSPACE_A, DEVICE_A)
        connection.execute("INSERT INTO someday_sync_v2_changes(cursor, user_id, workspace_id, epoch_id, object_id, object_digest, mutation_id) VALUES (1, ?, ?, 'epoch-a', 'object-a', 'object-digest-a', 'mutation-a')", USER_A, WORKSPACE_A)
        connection.execute("INSERT INTO someday_sync_v2_mutations(user_id, workspace_id, epoch_id, mutation_id, object_id, object_digest, cursor) VALUES (?, ?, 'epoch-a', 'mutation-a', 'object-a', 'object-digest-a', 1)", USER_A, WORKSPACE_A)
        connection.execute("INSERT INTO someday_media_v3_objects(user_id, workspace_id, media_id, ciphertext_bytes, ciphertext_sha256, uploaded_by_device_id) VALUES (?, ?, ?, 64, ?, ?)", USER_A, WORKSPACE_A, "a".repeat(64), "sha256:${"b".repeat(64)}", DEVICE_A)
        connection.execute("INSERT INTO workspace_recovery_envelopes(user_id, workspace_id, key_fingerprint, envelope_json, envelope_digest, revision, created_by_device_id, updated_by_device_id) VALUES (?, ?, ?, '{\"legacy\":\"recovery\"}', ?, 4, ?, ?)", USER_A, WORKSPACE_A, "c".repeat(32), "d".repeat(43), DEVICE_A, DEVICE_A)
        listOf("available", "claimed", "completed", "cancelled").forEach { state ->
            val claimed = state == "claimed" || state == "completed"
            connection.execute(
                """
                INSERT INTO workspace_pairing_invites(user_id, invite_id, creator_device_id, envelope_json,
                    envelope_digest, state, expires_at, claim_id, claim_device_id, claimed_at)
                VALUES (?, ?, ?, ?, 'synthetic-digest', ?, TIMESTAMPTZ '2099-01-01 00:00:00Z', ?, ?, ?::timestamptz)
                """.trimIndent(),
                USER_A, state, DEVICE_A, if (state == "available" || state == "claimed") "synthetic-envelope" else null,
                state, if (claimed) "claim-$state" else null, if (claimed) DEVICE_A else null,
                if (claimed) "2020-01-01T00:00:00Z" else null,
            )
        }
    }

    private fun legacyRows(connection: Connection): Map<String, List<String>> = LEGACY_TABLES.associateWith { table ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT (to_jsonb(t) - 'data_incarnation')::text AS value FROM $table t ORDER BY value").use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
    }

    private fun Connection.insertDevice(id: UUID) = execute("INSERT INTO someday_devices(id, user_id, name, platform, data_incarnation) VALUES (?, ?, 'reference test', 'test', ?)", id, USER_A, G0)

    private fun Connection.execute(sql: String, vararg values: Any?) {
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun Connection.number(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }
    }

    private fun Connection.string(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }
    }

    private fun assertSqlState(expected: String, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<SQLException>(block = block).sqlState)
    }

    private class IntentionalRollback : RuntimeException()

    private companion object {
        val G0: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val USER_A: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val USER_B: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val DEVICE_A: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01")
        val DEVICE_B: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb01")
        val SESSION_A: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa02")
        val SESSION_B: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb02")
        const val WORKSPACE_A = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val WORKSPACE_A2 = "workspace-cccccccccccccccccccccccccccccccc"
        const val WORKSPACE_B = "workspace-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val INCARNATION_TABLES = listOf("someday_sessions", "someday_devices", "someday_entity_workspaces", "workspace_pairing_invites")
        val LEGACY_TABLES = listOf(
            "someday_users", "someday_devices", "someday_sessions", "someday_refresh_tokens",
            "someday_entity_workspaces", "someday_sync_v2_epochs", "someday_sync_v2_checkpoint_chunks",
            "someday_sync_v2_checkpoint_manifests", "someday_sync_v2_objects", "someday_sync_v2_changes",
            "someday_sync_v2_mutations", "someday_media_v3_objects", "workspace_recovery_envelopes",
            "workspace_pairing_invites",
        )
    }
}
