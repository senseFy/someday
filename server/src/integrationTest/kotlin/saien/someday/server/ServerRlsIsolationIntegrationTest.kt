package saien.someday.server

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.media.FileSystemMediaBlobStore
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.persistence.SyncV2CheckpointChunkInput
import saien.someday.server.persistence.SyncV2CheckpointChunkRefRecord
import saien.someday.server.persistence.SyncV2CheckpointManifestInput
import saien.someday.server.persistence.SyncV2ImmutablePutRepositoryResult
import saien.someday.server.persistence.SyncV2ObjectInput
import saien.someday.server.persistence.SyncV2PushRepositoryResult
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.persistence.SystemV3MediaObjectRecord
import saien.someday.server.persistence.SystemV3MediaPutResult
import saien.someday.server.persistence.SystemV3MediaReadResult
import saien.someday.server.persistence.SystemV3MediaRepository

class ServerRlsIsolationIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val databaseUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val databaseUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val databasePassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    private val administratorDatabaseUrl = System.getenv("SOMEDAY_DB_ADMIN_URL") ?: databaseUrl
    private val administratorDatabaseUser = System.getenv("SOMEDAY_DB_ADMIN_USER") ?: databaseUser
    private val administratorDatabasePassword = System.getenv("SOMEDAY_DB_ADMIN_PASSWORD") ?: databasePassword
    private val applicationConfig = productionTestServerConfig(databaseUrl, databaseUser, databasePassword)
    private val administratorDatabaseConnectionUrl = productionTestDatabaseConnectionUrl(administratorDatabaseUrl)

    @Test
    fun restrictedRoleInitializesWorkspaceAndRlsIsolatesEntityAndMediaByAccountAndWorkspace() {
        DatabaseMigrator.migrate(applicationConfig)
        assumeTrue(
            "PostgreSQL integration account lacks CREATEROLE; restricted-role RLS verification skipped.",
            administratorConnection().use(::canCreateRole),
        )

        val suffix = UUID.randomUUID().toString().replace("-", "")
        val roleName = "someday_rls_$suffix"
        val rolePassword = "rls-$suffix"
        val firstUserId = UUID.randomUUID()
        val secondUserId = UUID.randomUUID()
        val firstDeviceId = UUID.randomUUID()
        val secondDeviceId = UUID.randomUUID()
        var roleCreated = false

        try {
            administratorConnection().use { connection ->
                createRestrictedRole(connection, roleName, rolePassword)
                roleCreated = true
                grantRestrictedRoleAccess(connection, roleName)
                seedAccounts(connection, firstUserId, firstDeviceId, secondUserId, secondDeviceId, suffix)
            }

            val restrictedConfig = applicationConfig.copy(
                databaseUser = roleName,
                databasePassword = rolePassword,
                mediaQuotaBytes = 1024L * 1024L,
            )
            val entityRepository = SyncV2Repository(restrictedConfig)

            // Reads select an RLS scope without manufacturing an empty workspace.
            assertNull(entityRepository.loadEpoch(firstUserId, WORKSPACE_A))
            assertEquals(0L, registryRowCount(firstUserId, WORKSPACE_A))

            // The first real write must set both RLS settings before creating the
            // registry row. Regressing that order makes this restricted INSERT fail.
            initializeWorkspace(entityRepository, firstUserId, WORKSPACE_A, "first-a")
            assertEquals(1L, registryRowCount(firstUserId, WORKSPACE_A))
            assertNull(entityRepository.loadEpoch(firstUserId, WORKSPACE_B))
            assertNull(entityRepository.loadEpoch(secondUserId, WORKSPACE_A))
            initializeWorkspace(entityRepository, firstUserId, WORKSPACE_B, "first-b")
            initializeWorkspace(entityRepository, secondUserId, WORKSPACE_A, "second-a")

            administratorConnection().use { connection ->
                selectWildcardScope(connection)
                seedEpoch(connection, firstUserId, WORKSPACE_A, "first-a")
                seedEpoch(connection, firstUserId, WORKSPACE_B, "first-b")
                seedEpoch(connection, secondUserId, WORKSPACE_A, "second-a")
            }

            pushEntity(entityRepository, firstUserId, WORKSPACE_A, firstDeviceId, "first-a")
            pushEntity(entityRepository, firstUserId, WORKSPACE_B, firstDeviceId, "first-b")
            pushEntity(entityRepository, secondUserId, WORKSPACE_A, secondDeviceId, "second-a")

            assertEquals("pointer-first-a", entityRepository.loadEpoch(firstUserId, WORKSPACE_A)?.metadata?.pointerDigest)
            assertEquals("pointer-first-b", entityRepository.loadEpoch(firstUserId, WORKSPACE_B)?.metadata?.pointerDigest)
            assertEquals("pointer-second-a", entityRepository.loadEpoch(secondUserId, WORKSPACE_A)?.metadata?.pointerDigest)

            val mediaRepository = SystemV3MediaRepository(
                restrictedConfig,
                FileSystemMediaBlobStore(temporaryFolder.newFolder("rls-media").toPath()),
            )
            val firstABytes = ByteArray(64) { 1 }
            val firstBBytes = ByteArray(65) { 2 }
            val secondABytes = ByteArray(66) { 3 }
            putAndVerifyMedia(mediaRepository, firstUserId, WORKSPACE_A, firstDeviceId, firstABytes)
            putAndVerifyMedia(mediaRepository, firstUserId, WORKSPACE_B, firstDeviceId, firstBBytes)
            putAndVerifyMedia(mediaRepository, secondUserId, WORKSPACE_A, secondDeviceId, secondABytes)

            restrictedConnection(roleName, rolePassword).use { connection ->
                // Missing GUCs fail closed. Each unqualified query below relies
                // solely on PostgreSQL RLS rather than repository predicates.
                ENTITY_TABLES.forEach { table ->
                    assertEquals(emptyList(), visibleScopes(connection, table), table)
                }
                assertEquals(emptyList(), visibleScopes(connection, MEDIA_TABLE))

                selectScope(connection, firstUserId, WORKSPACE_A)
                ENTITY_TABLES.forEach { table ->
                    assertEquals(listOf(Scope(firstUserId, WORKSPACE_A)), visibleScopes(connection, table), table)
                }
                assertEquals(listOf(Scope(firstUserId, WORKSPACE_A)), visibleScopes(connection, MEDIA_TABLE))

                selectScope(connection, firstUserId, WORKSPACE_B)
                ENTITY_TABLES.forEach { table ->
                    assertEquals(listOf(Scope(firstUserId, WORKSPACE_B)), visibleScopes(connection, table), table)
                }
                assertEquals(listOf(Scope(firstUserId, WORKSPACE_B)), visibleScopes(connection, MEDIA_TABLE))

                selectScope(connection, secondUserId, WORKSPACE_A)
                ENTITY_TABLES.forEach { table ->
                    assertEquals(listOf(Scope(secondUserId, WORKSPACE_A)), visibleScopes(connection, table), table)
                }
                assertEquals(listOf(Scope(secondUserId, WORKSPACE_A)), visibleScopes(connection, MEDIA_TABLE))
            }
        } finally {
            try {
                cleanupAccounts(firstUserId, secondUserId)
            } finally {
                if (roleCreated) dropRestrictedRole(roleName)
            }
        }
    }

    private fun canCreateRole(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
    ).use { statement ->
        statement.executeQuery().use { result ->
            check(result.next())
            result.getBoolean(1)
        }
    }

    private fun createRestrictedRole(connection: Connection, roleName: String, rolePassword: String) {
        require(ROLE_NAME.matches(roleName))
        require(ROLE_PASSWORD.matches(rolePassword))
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE ROLE $roleName LOGIN PASSWORD '$rolePassword' " +
                    "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS",
            )
        }
    }

    private fun grantRestrictedRoleAccess(connection: Connection, roleName: String) {
        require(ROLE_NAME.matches(roleName))
        val databaseName = connection.catalog
        connection.createStatement().use { statement ->
            // DROP OWNED requires the cleanup identity to have the target role's
            // privileges when the fixture account is CREATEROLE but not superuser.
            statement.execute("GRANT $roleName TO CURRENT_USER WITH ADMIN OPTION")
            statement.execute("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO $roleName")
            statement.execute("GRANT USAGE ON SCHEMA public TO $roleName")
            statement.execute("GRANT SELECT, INSERT ON someday_entity_workspaces TO $roleName")
            statement.execute("GRANT SELECT, UPDATE ON someday_sync_v2_epochs TO $roleName")
            statement.execute("GRANT SELECT, INSERT, UPDATE ON someday_sync_v2_checkpoint_chunks TO $roleName")
            statement.execute("GRANT SELECT, INSERT, UPDATE ON someday_sync_v2_checkpoint_manifests TO $roleName")
            statement.execute("GRANT SELECT, INSERT, UPDATE ON someday_sync_v2_objects TO $roleName")
            statement.execute("GRANT SELECT, INSERT ON someday_sync_v2_changes TO $roleName")
            statement.execute("GRANT SELECT, INSERT, UPDATE ON someday_sync_v2_mutations TO $roleName")
            statement.execute("GRANT USAGE, SELECT ON SEQUENCE someday_sync_v2_global_cursor TO $roleName")
            statement.execute("GRANT SELECT, INSERT ON someday_media_v3_objects TO $roleName")
        }
    }

    private fun seedAccounts(
        connection: Connection,
        firstUserId: UUID,
        firstDeviceId: UUID,
        secondUserId: UUID,
        secondDeviceId: UUID,
        suffix: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO someday_users(id, email, password_hash) VALUES (?, ?, ?), (?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, firstUserId)
            statement.setString(2, "rls-first-$suffix@example.com")
            statement.setString(3, "test-only")
            statement.setObject(4, secondUserId)
            statement.setString(5, "rls-second-$suffix@example.com")
            statement.setString(6, "test-only")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO someday_devices(id, user_id, name, platform) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, firstDeviceId)
            statement.setObject(2, firstUserId)
            statement.setString(3, "RLS first device")
            statement.setString(4, "integration")
            statement.setObject(5, secondDeviceId)
            statement.setObject(6, secondUserId)
            statement.setString(7, "RLS second device")
            statement.setString(8, "integration")
            statement.executeUpdate()
        }
    }

    private fun seedEpoch(connection: Connection, userId: UUID, workspaceId: String, label: String) {
        connection.prepareStatement(
            """
            INSERT INTO someday_sync_v2_epochs(
                user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json,
                contract_id, schema_set_version, semantic_protocol_version,
                minimum_writer_protocol_version, key_set_version, remote_profile,
                metadata_privacy_mode, supported_offline_window_seconds,
                checkpoint_id, checkpoint_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, SHARED_EPOCH_ID)
            statement.setString(4, "pointer-$label")
            statement.setString(5, """{"scope":"$label"}""")
            statement.setString(6, "someday-system-v2")
            statement.setString(7, "workspace-entity-schema-set-v2")
            statement.setInt(8, 2)
            statement.setInt(9, 2)
            statement.setString(10, "sync-key-set-v2")
            statement.setString(11, "self-hosted-v2")
            statement.setString(12, "opaque")
            statement.setLong(13, 15_552_000L)
            statement.setString(14, SHARED_CHECKPOINT_ID)
            statement.setString(15, "checkpoint-$label")
            statement.executeUpdate()
        }
    }

    private fun initializeWorkspace(
        repository: SyncV2Repository,
        userId: UUID,
        workspaceId: String,
        label: String,
    ) {
        val ref = SyncV2CheckpointChunkRefRecord(
            chunkIndex = 0,
            chunkId = SHARED_CHUNK_ID,
            chunkDigest = "chunk-$label",
            objectCount = 1,
            plaintextBytes = 1,
        )
        assertIs<SyncV2ImmutablePutRepositoryResult.Stored>(
            repository.putCheckpointChunk(
                userId,
                workspaceId,
                SyncV2CheckpointChunkInput(
                    epochId = SHARED_EPOCH_ID,
                    checkpointId = SHARED_CHECKPOINT_ID,
                    ref = ref,
                    encryptedObjectJson = """{"scope":"$label"}""",
                ),
            ),
        )
        assertIs<SyncV2ImmutablePutRepositoryResult.Stored>(
            repository.putCheckpointManifest(
                userId,
                workspaceId,
                SyncV2CheckpointManifestInput(
                    epochId = SHARED_EPOCH_ID,
                    checkpointId = SHARED_CHECKPOINT_ID,
                    checkpointDigest = "checkpoint-$label",
                    chunks = listOf(ref),
                    totalObjectCount = 1,
                    encryptedObjectJson = """{"manifestScope":"$label"}""",
                ),
            ),
        )
    }

    private fun pushEntity(
        repository: SyncV2Repository,
        userId: UUID,
        workspaceId: String,
        deviceId: UUID,
        label: String,
    ) {
        assertIs<SyncV2PushRepositoryResult.Accepted>(
            repository.push(
                userId,
                workspaceId,
                deviceId,
                SHARED_EPOCH_ID,
                writerProtocolVersion = 2,
                objects = listOf(
                    SyncV2ObjectInput(
                        epochId = SHARED_EPOCH_ID,
                        objectId = SHARED_OBJECT_ID,
                        objectType = "workspace_entity_version_v2",
                        objectDigest = "od2:hmac-sha256:${digestHex("object-$label")}",
                        mutationId = SHARED_MUTATION_ID,
                        writerDeviceId = deviceId,
                        ciphertextDigest = "ct2:sha256:${digestHex("ciphertext-$label")}",
                        encodedObjectJson = """{"entityScope":"$label"}""",
                    ),
                ),
            ),
        )
    }

    private fun putAndVerifyMedia(
        repository: SystemV3MediaRepository,
        userId: UUID,
        workspaceId: String,
        deviceId: UUID,
        bytes: ByteArray,
    ) {
        val digest = sha256(bytes)
        assertIs<SystemV3MediaPutResult.Stored>(
            repository.putObject(userId, workspaceId, deviceId, MEDIA_ID, digest, bytes),
        )
        val found = assertIs<SystemV3MediaReadResult.Found<*>>(
            repository.headObject(userId, workspaceId, MEDIA_ID),
        )
        assertEquals(digest, assertIs<SystemV3MediaObjectRecord>(found.value).ciphertextSha256)
    }

    private fun registryRowCount(userId: UUID, workspaceId: String): Long = applicationConnection().use { connection ->
        selectWildcardScope(connection)
        connection.prepareStatement(
            "SELECT COUNT(*) FROM someday_entity_workspaces WHERE user_id = ? AND workspace_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    private fun visibleScopes(connection: Connection, table: String): List<Scope> {
        require(table in ENTITY_TABLES || table == MEDIA_TABLE)
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT user_id, workspace_id FROM $table ORDER BY user_id, workspace_id").use { result ->
                buildList {
                    while (result.next()) {
                        add(Scope(result.getObject(1, UUID::class.java), result.getString(2)))
                    }
                }
            }
        }
    }

    private fun selectScope(connection: Connection, userId: UUID, workspaceId: String) {
        setConfig(connection, "someday.user_id", userId.toString())
        setConfig(connection, "someday.workspace_id", workspaceId)
    }

    private fun selectWildcardScope(connection: Connection) {
        setConfig(connection, "someday.user_id", "*")
        setConfig(connection, "someday.workspace_id", "*")
    }

    private fun setConfig(connection: Connection, name: String, value: String) {
        require(name == "someday.user_id" || name == "someday.workspace_id")
        connection.prepareStatement("SELECT set_config(?, ?, false)").use { statement ->
            statement.setString(1, name)
            statement.setString(2, value)
            statement.executeQuery().close()
        }
    }

    private fun cleanupAccounts(firstUserId: UUID, secondUserId: UUID) {
        administratorConnection().use { connection ->
            selectWildcardScope(connection)
            // Changes and mutations deliberately RESTRICT deletion of their
            // immutable object. Media and entity objects in turn retain the
            // first-writer device, so remove those rows before the account's
            // cascading device cleanup.
            listOf(
                "someday_sync_v2_changes",
                "someday_sync_v2_mutations",
                "someday_sync_v2_objects",
                "someday_media_v3_objects",
            ).forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE user_id IN (?, ?)").use { statement ->
                    statement.setObject(1, firstUserId)
                    statement.setObject(2, secondUserId)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement("DELETE FROM someday_users WHERE id IN (?, ?)").use { statement ->
                statement.setObject(1, firstUserId)
                statement.setObject(2, secondUserId)
                statement.executeUpdate()
            }
        }
    }

    private fun dropRestrictedRole(roleName: String) {
        require(ROLE_NAME.matches(roleName))
        administratorConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP OWNED BY $roleName")
                statement.execute("DROP ROLE $roleName")
            }
        }
    }

    private fun administratorConnection(): Connection =
        DriverManager.getConnection(
            administratorDatabaseConnectionUrl,
            administratorDatabaseUser,
            administratorDatabasePassword,
        )

    private fun applicationConnection(): Connection =
        DriverManager.getConnection(applicationConfig.databaseConnectionUrl, databaseUser, databasePassword)

    private fun restrictedConnection(roleName: String, rolePassword: String): Connection =
        DriverManager.getConnection(applicationConfig.databaseConnectionUrl, roleName, rolePassword)

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private fun digestHex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private data class Scope(val userId: UUID, val workspaceId: String)

    private companion object {
        const val WORKSPACE_A = "workspace-11111111111111111111111111111111"
        const val WORKSPACE_B = "workspace-22222222222222222222222222222222"
        const val SHARED_EPOCH_ID = "11111111-1111-4111-8111-111111111111"
        const val SHARED_CHECKPOINT_ID = "22222222-2222-4222-8222-222222222222"
        const val SHARED_CHUNK_ID = "33333333-3333-4333-8333-333333333333"
        const val SHARED_OBJECT_ID = "44444444-4444-4444-8444-444444444444"
        const val SHARED_MUTATION_ID = "55555555-5555-4555-8555-555555555555"
        val MEDIA_ID = "a1".repeat(32)
        val ENTITY_TABLES = listOf(
            "someday_entity_workspaces",
            "someday_sync_v2_epochs",
            "someday_sync_v2_checkpoint_chunks",
            "someday_sync_v2_checkpoint_manifests",
            "someday_sync_v2_objects",
            "someday_sync_v2_changes",
            "someday_sync_v2_mutations",
        )
        const val MEDIA_TABLE = "someday_media_v3_objects"
        val ROLE_NAME = Regex("^[a-z0-9_]+$")
        val ROLE_PASSWORD = Regex("^[a-z0-9-]+$")
    }
}
