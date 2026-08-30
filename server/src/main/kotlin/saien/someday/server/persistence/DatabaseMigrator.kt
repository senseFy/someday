package saien.someday.server.persistence

import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState
import saien.someday.server.ServerConfig

object DatabaseMigrator {
    fun migrate(config: ServerConfig) {
        ProductionDatabasePreflight.verify(config)
        val flyway = Flyway.configure()
            .dataSource(config.databaseConnectionUrl, config.databaseUser, config.databasePassword)
            .locations(MIGRATION_LOCATION)
            .load()
        rejectFutureMigrations(flyway.info().all().asIterable())
        flyway.migrate()
        DriverManager.getConnection(
            config.databaseConnectionUrl,
            config.databaseUser,
            config.databasePassword,
        ).use(::verifyRlsCatalog)
    }

    internal fun rejectFutureMigrations(migrations: Iterable<MigrationInfo>) {
        val futureMigrations = migrations
            .filter { it.state == MigrationState.FUTURE_SUCCESS || it.state == MigrationState.FUTURE_FAILED }
            .map { migration -> migration.version?.toString() ?: migration.script }
            .sorted()
        check(futureMigrations.isEmpty()) {
            "Database schema is newer than this server image (future Flyway migrations: " +
                "${futureMigrations.joinToString()}). Start a matching or newer server image."
        }
    }

    fun verifyRlsCatalog(connection: Connection) {
        val observedTables = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p')
                  AND (c.relrowsecurity OR c.relforcerowsecurity)
                ORDER BY c.relname
                """.trimIndent(),
            ).use { result ->
                buildSet {
                    while (result.next()) {
                        check(result.getBoolean(2) && result.getBoolean(3)) {
                            "RLS table ${result.getString(1)} must enable and force row-level security."
                        }
                        add(result.getString(1))
                    }
                }
            }
        }
        check(observedTables == EXPECTED_RLS_POLICIES.keys) {
            "Unexpected PostgreSQL RLS table set. Expected ${EXPECTED_RLS_POLICIES.keys.sorted()}, " +
                "observed ${observedTables.sorted()}."
        }

        val observedPolicies = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    c.relname,
                    p.polname,
                    p.polpermissive,
                    p.polcmd,
                    p.polroles = ARRAY[0]::oid[],
                    pg_get_expr(p.polqual, p.polrelid),
                    pg_get_expr(p.polwithcheck, p.polrelid)
                FROM pg_policy p
                JOIN pg_class c ON c.oid = p.polrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                ORDER BY c.relname, p.polname
                """.trimIndent(),
            ).use { result ->
                buildSet {
                    while (result.next()) {
                        add(
                            RlsPolicyShape(
                                table = result.getString(1),
                                policy = result.getString(2),
                                permissive = result.getBoolean(3),
                                command = result.getString(4),
                                appliesToPublic = result.getBoolean(5),
                                usingExpression = result.getString(6),
                                checkExpression = result.getString(7),
                            ),
                        )
                    }
                }
            }
        }
        check(observedPolicies == EXPECTED_POLICY_SHAPES) {
            "Unexpected PostgreSQL RLS policy set. Expected ${EXPECTED_POLICY_SHAPES.sortedBy { it.table }}, " +
                "observed ${observedPolicies.sortedBy { it.table }}."
        }
    }

    private const val MIGRATION_LOCATION = "classpath:db/migration"

    private val EXPECTED_RLS_POLICIES = mapOf(
        "someday_entity_workspaces" to "entity_account_workspace_scope",
        "someday_sync_v2_epochs" to "entity_account_workspace_scope",
        "someday_sync_v2_checkpoint_chunks" to "entity_account_workspace_scope",
        "someday_sync_v2_checkpoint_manifests" to "entity_account_workspace_scope",
        "someday_sync_v2_objects" to "entity_account_workspace_scope",
        "someday_sync_v2_changes" to "entity_account_workspace_scope",
        "someday_sync_v2_mutations" to "entity_account_workspace_scope",
        "someday_media_v3_objects" to "media_account_workspace_scope",
        "workspace_recovery_envelopes" to "recovery_account_workspace_scope",
    )
    private val EXPECTED_POLICY_SHAPES = EXPECTED_RLS_POLICIES.mapTo(mutableSetOf()) { (table, policy) ->
        RlsPolicyShape(
            table = table,
            policy = policy,
            permissive = true,
            command = "*",
            appliesToPublic = true,
            usingExpression = EXPECTED_SCOPE_EXPRESSION,
            checkExpression = EXPECTED_SCOPE_EXPRESSION,
        )
    }

    private const val EXPECTED_SCOPE_EXPRESSION =
        "(((current_setting('someday.user_id'::text, true) = '*'::text) OR " +
            "((user_id)::text = current_setting('someday.user_id'::text, true))) AND " +
            "((current_setting('someday.workspace_id'::text, true) = '*'::text) OR " +
            "(workspace_id = current_setting('someday.workspace_id'::text, true))))"
}

private data class RlsPolicyShape(
    val table: String,
    val policy: String,
    val permissive: Boolean,
    val command: String,
    val appliesToPublic: Boolean,
    val usingExpression: String?,
    val checkExpression: String?,
)
