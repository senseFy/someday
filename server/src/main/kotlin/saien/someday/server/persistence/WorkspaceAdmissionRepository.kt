package saien.someday.server.persistence

import java.sql.Connection
import saien.someday.server.ServerConfig
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountRequestContext

data class WorkspaceAdmissionSnapshot(
    val initializedWorkspaceCount: Int,
    val localWorkspaceInitialized: Boolean,
    val recoveryAvailable: Boolean,
)

class WorkspaceAdmissionRepository(
    config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun discover(request: AccountRequestContext, workspaceId: String): WorkspaceAdmissionSnapshot =
        AccountAdmission.transaction(
            connections,
            listOf(request.userId),
            scope = { connection ->
                connection.prepareStatement("SELECT set_config('someday.user_id', ?, true)").use { statement ->
                    statement.setString(1, request.userId.toString())
                    statement.executeQuery().close()
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT set_config('someday.workspace_id', '*', true)").close()
                }
            },
        ) { connection ->
            val account = AccountAdmission.admit(connection, request, AccountAccess.SYNC, workspaceId)
            loadWorkspaceAdmission(connection, account, workspaceId)
        }
}

/** One account-scoped snapshot; staged checkpoints and retired workspaces are not initialized authorities. */
internal fun loadWorkspaceAdmission(
    connection: Connection,
    account: AdmittedAccount,
    workspaceId: String,
): WorkspaceAdmissionSnapshot = connection.prepareStatement(
    """
    WITH initialized_workspaces AS (
        SELECT e.workspace_id
        FROM someday_sync_v2_epochs e
        JOIN someday_entity_workspaces w ON w.user_id = e.user_id AND w.workspace_id = e.workspace_id
        WHERE e.user_id = ? AND w.user_id = ? AND w.data_incarnation = ?
    )
    SELECT COUNT(*) AS initialized_count,
           COALESCE(BOOL_OR(workspace_id = ?), FALSE) AS local_initialized,
           EXISTS (
               SELECT 1 FROM workspace_recovery_envelopes r
               JOIN initialized_workspaces w ON w.workspace_id = r.workspace_id
               WHERE r.user_id = ?
           ) AS recovery_available
    FROM initialized_workspaces
    """.trimIndent(),
).use { statement ->
    statement.setObject(1, account.userId)
    statement.setObject(2, account.userId)
    statement.setObject(3, account.incarnation)
    statement.setString(4, workspaceId)
    statement.setObject(5, account.userId)
    statement.executeQuery().use { result ->
        check(result.next())
        WorkspaceAdmissionSnapshot(
            initializedWorkspaceCount = result.getInt("initialized_count"),
            localWorkspaceInitialized = result.getBoolean("local_initialized"),
            recoveryAvailable = result.getBoolean("recovery_available"),
        )
    }
}
