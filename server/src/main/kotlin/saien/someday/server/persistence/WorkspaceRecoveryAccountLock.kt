package saien.someday.server.persistence

import java.sql.Connection
import java.util.UUID

/**
 * Serializes the account-current recovery pointer with first-epoch publication.
 * Every caller acquires this before any workspace advisory lock or recovery-row
 * lock so an absent recovery row is just as race-safe as an existing one.
 */
internal fun lockWorkspaceRecoveryAccount(connection: Connection, userId: UUID) {
    connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
        statement.setString(1, workspaceRecoveryAccountLockKey(userId))
        statement.executeQuery().close()
    }
}

internal fun workspaceRecoveryAccountLockKey(userId: UUID): String =
    "workspace-recovery-account\u001f$userId"
