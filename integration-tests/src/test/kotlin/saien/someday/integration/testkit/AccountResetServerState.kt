package saien.someday.integration.testkit

import java.sql.DriverManager
import java.util.UUID

/** Only counters and public opaque identities leave PostgreSQL; never credentials. */
internal data class AccountResetServerState(
    val activeIncarnation: String,
    val retiredIncarnations: Int,
    val receipts: Int,
    val currentDevices: Int,
    val activeWorkspaces: Int,
    val retiredWorkspaces: Int,
    val mediaObjects: Int,
    val recoveryEnvelopes: Int,
    val entityRows: Int,
    val entityFingerprint: String,
)

internal fun accountResetServerState(userId: String): AccountResetServerState = DriverManager.getConnection(
    requiredEnvironment("SOMEDAY_DB_URL"),
    requiredEnvironment("SOMEDAY_DB_USER"),
    requiredEnvironment("SOMEDAY_DB_PASSWORD"),
).use { connection ->
    connection.autoCommit = false
    connection.prepareStatement(
        "SELECT set_config('someday.user_id', ?, true), set_config('someday.workspace_id', '*', true)",
    ).use {
        it.setString(1, userId)
        it.executeQuery().close()
    }
    connection.prepareStatement(
        """
        WITH account AS (SELECT ?::uuid AS user_id), current AS (
            SELECT incarnation FROM someday_account_data_incarnations JOIN account USING (user_id) WHERE state = 'active'
        ), entity_rows AS (
            SELECT 'epoch' AS kind, to_jsonb(entity)::text AS payload FROM someday_sync_v2_epochs entity JOIN account USING (user_id)
            UNION ALL SELECT 'chunk', to_jsonb(entity)::text FROM someday_sync_v2_checkpoint_chunks entity JOIN account USING (user_id)
            UNION ALL SELECT 'manifest', to_jsonb(entity)::text FROM someday_sync_v2_checkpoint_manifests entity JOIN account USING (user_id)
            UNION ALL SELECT 'object', to_jsonb(entity)::text FROM someday_sync_v2_objects entity JOIN account USING (user_id)
            UNION ALL SELECT 'change', to_jsonb(entity)::text FROM someday_sync_v2_changes entity JOIN account USING (user_id)
            UNION ALL SELECT 'mutation', to_jsonb(entity)::text FROM someday_sync_v2_mutations entity JOIN account USING (user_id)
        )
        SELECT (SELECT incarnation::text FROM current),
          (SELECT count(*) FROM someday_account_data_incarnations JOIN account USING (user_id) WHERE state = 'retired'),
          (SELECT count(*) FROM someday_account_data_resets JOIN account USING (user_id)),
          (SELECT count(*) FROM someday_devices JOIN account USING (user_id) WHERE data_incarnation = (SELECT incarnation FROM current)),
          (SELECT count(*) FROM someday_entity_workspaces JOIN account USING (user_id) WHERE data_incarnation = (SELECT incarnation FROM current)),
          (SELECT count(*) FROM someday_entity_workspaces JOIN account USING (user_id) WHERE data_incarnation <> (SELECT incarnation FROM current)),
          (SELECT count(*) FROM someday_media_v3_objects JOIN account USING (user_id)),
          (SELECT count(*) FROM workspace_recovery_envelopes JOIN account USING (user_id)),
          (SELECT count(*) FROM entity_rows),
          (SELECT encode(sha256(convert_to(COALESCE(string_agg(kind || ':' || payload, chr(10) ORDER BY kind, payload), ''), 'UTF8')), 'hex') FROM entity_rows)
        """.trimIndent(),
    ).use {
        it.setObject(1, UUID.fromString(userId))
        it.executeQuery().use { row ->
            check(row.next())
            AccountResetServerState(
                activeIncarnation = row.getString(1),
                retiredIncarnations = row.getInt(2),
                receipts = row.getInt(3),
                currentDevices = row.getInt(4),
                activeWorkspaces = row.getInt(5),
                retiredWorkspaces = row.getInt(6),
                mediaObjects = row.getInt(7),
                recoveryEnvelopes = row.getInt(8),
                entityRows = row.getInt(9),
                entityFingerprint = row.getString(10),
            )
        }
    }.also { connection.rollback() }
}
