package saien.someday.server.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.api.MigrationVersion

class DatabaseMigrationPolicyTest {
    @Test
    fun releasedMigrationsRemainByteForByteFrozen() {
        val actual = migrationSqlFiles().associate { script -> script.name to sha256(Files.readAllBytes(script)) }

        FROZEN_MIGRATION_SHA256.forEach { (name, expectedSha256) ->
            assertEquals(expectedSha256, actual[name], "$name is a released immutable migration")
        }
    }

    @Test
    fun migrationDirectoryContainsOnlyForwardVersionedSql() {
        validateMigrationNames(migrationSqlFiles().map(Path::getFileName).map(Path::toString))

        validateMigrationNames(listOf("V9__next.sql", "V8.1__next.sql"))
        assertFailsWith<IllegalArgumentException> { validateMigrationNames(listOf("V8__late.sql")) }
        assertFailsWith<IllegalArgumentException> { validateMigrationNames(listOf("R__repeatable.sql")) }
        assertFailsWith<IllegalArgumentException> { validateMigrationNames(listOf("U9__undo.sql")) }
        assertFailsWith<IllegalArgumentException> { validateMigrationNames(listOf("unknown.sql")) }
    }

    private fun validateMigrationNames(names: List<String>) {
        names.forEach { name ->
            val match = VERSIONED_MIGRATION.matchEntire(name)
            requireNotNull(match) { "$name is not a supported versioned Flyway SQL migration." }
            if (name !in FROZEN_MIGRATION_SHA256) {
                val version = MigrationVersion.fromVersion(match.groupValues[1])
                require(version > FROZEN_HIGHEST_VERSION) {
                    "$name must use a version newer than $FROZEN_HIGHEST_VERSION."
                }
            }
        }
    }

    private fun migrationSqlFiles(): List<Path> {
        val resource = checkNotNull(javaClass.classLoader.getResource("db/migration"))
        val directory = Path.of(resource.toURI())
        return Files.list(directory).use { paths ->
            paths.filter { path -> path.extension == "sql" }
                .sorted()
                .toList()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val VERSIONED_MIGRATION = Regex("^V([0-9][0-9_.]*)__.+\\.sql$")
        val FROZEN_HIGHEST_VERSION: MigrationVersion = MigrationVersion.fromVersion("8")
        val FROZEN_MIGRATION_SHA256 = mapOf(
            "V1__server_auth_devices.sql" to "fb9cb95a840997206bb4c561c57267cd4427475cbd04e16f8646cc3d9bf15afa",
            "V4__sync_v2_epochs_immutable_objects.sql" to
                "7ca11c2044eab09f7df26e211c5aba57303b23817cf9f17107f162f89176c186",
            "V7__workspace_pairing_invites.sql" to
                "c99e2d3149ded3f0999e4b9e14c90a97c1b4290b7e42a6660426d34d5a44c7c6",
            "V8__system_v3_media_metadata.sql" to
                "0d4eee373fad371e5e67701c6f2d400722f825300d3e94765c1a02ec0c2ff626",
        )
    }
}
