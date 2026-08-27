package saien.someday.server.persistence

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion

class DatabaseMigratorTest {
    @Test
    fun rejectsSuccessfulOrFailedMigrationsFromANewerServerImage() {
        DatabaseMigrator.rejectFutureMigrations(
            listOf(migration("8", MigrationState.SUCCESS)),
        )

        assertFailsWith<IllegalStateException> {
            DatabaseMigrator.rejectFutureMigrations(
                listOf(
                    migration("8", MigrationState.SUCCESS),
                    migration("9", MigrationState.FUTURE_SUCCESS),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            DatabaseMigrator.rejectFutureMigrations(
                listOf(migration("10", MigrationState.FUTURE_FAILED)),
            )
        }
        assertFailsWith<IllegalStateException> {
            DatabaseMigrator.rejectFutureMigrations(
                listOf(migration(null, MigrationState.FUTURE_SUCCESS, "R__future_repeatable.sql")),
            )
        }
    }

    private fun migration(
        version: String?,
        state: MigrationState,
        script: String = "V${version}__test.sql",
    ): MigrationInfo =
        Proxy.newProxyInstance(
            MigrationInfo::class.java.classLoader,
            arrayOf(MigrationInfo::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getState" -> state
                "getVersion" -> version?.let(MigrationVersion::fromVersion)
                "getScript" -> script
                "toString" -> "migration-$version-$state"
                else -> error("Unexpected MigrationInfo method in test: ${method.name}")
            }
        } as MigrationInfo
}
