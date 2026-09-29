package saien.someday.server.persistence

import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext

enum class AccountLockMode(val waitMillis: Long) {
    SHARED(250), EXCLUSIVE(45_000),
}

data class AdmittedAccount(
    val userId: UUID,
    val sessionId: UUID,
    val deviceId: UUID?,
    val email: String,
    val isAdmin: Boolean,
    val incarnation: UUID,
)

/** Account barrier only; repositories continue to own their scoped business SQL. */
object AccountAdmission {
    const val LOCK_NAMESPACE: Int = 1396982098

    fun lockKey(userId: UUID): Int {
        val bytes = MessageDigest.getInstance("SHA-256").digest(userId.toString().toByteArray(Charsets.US_ASCII))
        return ((bytes[0].toInt() and 255) shl 24) or
            ((bytes[1].toInt() and 255) shl 16) or
            ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
    }

    fun <T> transaction(
        connections: DatabaseConnectionProvider,
        userIds: Collection<UUID>,
        mode: AccountLockMode = AccountLockMode.SHARED,
        scope: (Connection) -> Unit = {},
        block: (Connection) -> T,
    ): T = connections.connection().use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            scope(connection)
            lockAccounts(connection, userIds, mode)
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                // A failed rollback cannot establish a definitive, retryable lock rejection.
                throw IllegalStateException("Account transaction outcome is unknown", failure)
            }
            if (failure is AccountLockWaitExceeded) throw AccountProtocolFailure(AccountError.ACCOUNT_BUSY)
            throw failure
        }
    }

    fun lockAccounts(connection: Connection, userIds: Collection<UUID>, mode: AccountLockMode) {
        check(!connection.autoCommit) { "Account admission requires a transaction" }
        check(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        val previousTimeout = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_setting('lock_timeout')").use { result ->
                check(result.next())
                result.getString(1)
            }
        }
        val deadline = System.nanoTime() + mode.waitMillis * 1_000_000
        val function = if (mode == AccountLockMode.SHARED) "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
        for (key in userIds.map(::lockKey).distinct().sorted()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AccountLockWaitExceeded()
            setLockTimeout(connection, "${(remaining + 999_999) / 1_000_000}ms")
            try {
                connection.prepareStatement("SELECT $function(?, ?)").use { statement ->
                    statement.setInt(1, LOCK_NAMESPACE)
                    statement.setInt(2, key)
                    statement.executeQuery().close()
                }
            } catch (failure: SQLException) {
                if (failure.sqlState == "55P03") throw AccountLockWaitExceeded()
                throw failure
            }
        }
        // Do not apply the admission budget to narrower quota/workspace/row locks.
        setLockTimeout(connection, previousTimeout)
    }

    fun currentIncarnation(connection: Connection, userId: UUID): UUID = connection.prepareStatement(
        "SELECT incarnation FROM someday_account_data_incarnations WHERE user_id = ? AND state = 'active'",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { result ->
            check(result.next()) { "Account has no active incarnation" }
            val incarnation = result.getObject(1, UUID::class.java)
            check(!result.next()) { "Account has multiple active incarnations" }
            incarnation
        }
    }

    fun admit(
        connection: Connection,
        request: AccountRequestContext,
        access: AccountAccess,
        workspaceId: String? = null,
    ): AdmittedAccount {
        val account = connection.prepareStatement(
            """
            SELECT u.email, u.is_admin, u.disabled_at,
                   s.device_id, s.data_incarnation, s.expires_at, s.revoked_at,
                   d.data_incarnation AS device_incarnation, d.revoked_at AS device_revoked_at
            FROM someday_sessions s
            JOIN someday_users u ON u.id = s.user_id
            LEFT JOIN someday_devices d ON d.id = s.device_id AND d.user_id = s.user_id
            WHERE s.user_id = ? AND s.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, request.userId)
            statement.setObject(2, request.sessionId)
            statement.executeQuery().use { result ->
                if (!result.next()) fail(AccountError.UNAUTHORIZED)
                if (result.getObject("disabled_at") != null || result.getObject("revoked_at") != null ||
                    !result.getObject("expires_at", OffsetDateTime::class.java).toInstant().isAfter(Instant.now())
                ) fail(AccountError.UNAUTHORIZED)
                val deviceId = result.getObject("device_id", UUID::class.java)
                if (request.tokenDeviceId != deviceId) fail(AccountError.UNAUTHORIZED)
                if (access.scope !in request.tokenScopes) fail(AccountError.FORBIDDEN)
                if (access == AccountAccess.SYNC && deviceId == null) fail(AccountError.DEVICE_REQUIRED)
                if (access == AccountAccess.ADMIN && !result.getBoolean("is_admin")) fail(AccountError.FORBIDDEN)
                val current = currentIncarnation(connection, request.userId)
                if (result.getObject("data_incarnation", UUID::class.java) != current) {
                    fail(AccountError.ACCOUNT_SESSION_STALE)
                }
                if (deviceId != null) {
                    if (result.getObject("device_revoked_at") != null) fail(AccountError.DEVICE_REVOKED)
                    if (result.getObject("device_incarnation", UUID::class.java) != current) {
                        fail(AccountError.ACCOUNT_INCARNATION_MISMATCH)
                    }
                }
                if (access != AccountAccess.ADMIN) {
                    when (val protocol = request.protocol) {
                        AccountProtocolExpectation.Invalid -> fail(AccountError.INVALID_REQUEST)
                        AccountProtocolExpectation.Unsupported -> fail(AccountError.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED)
                        AccountProtocolExpectation.Missing -> {
                            if (current != ACCOUNT_INITIAL_INCARNATION && access in setOf(AccountAccess.DEVICES, AccountAccess.SYNC)) {
                                fail(AccountError.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED)
                            }
                        }
                        is AccountProtocolExpectation.V1 -> {
                            if (protocol.incarnation != current) fail(AccountError.ACCOUNT_INCARNATION_MISMATCH)
                        }
                    }
                }
                AdmittedAccount(request.userId, request.sessionId, deviceId, result.getString("email"), result.getBoolean("is_admin"), current)
            }
        }
        if (workspaceId != null) {
            connection.prepareStatement(
                "SELECT data_incarnation FROM someday_entity_workspaces WHERE user_id = ? AND workspace_id = ?",
            ).use { statement ->
                statement.setObject(1, request.userId)
                statement.setString(2, workspaceId)
                statement.executeQuery().use { result ->
                    if (result.next() && result.getObject(1, UUID::class.java) != account.incarnation) {
                        fail(AccountError.WORKSPACE_INCARNATION_RETIRED)
                    }
                }
            }
        }
        return account
    }

    private fun setLockTimeout(connection: Connection, timeout: String) {
        connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)").use { statement ->
            statement.setString(1, timeout)
            statement.executeQuery().close()
        }
    }

    private fun fail(error: AccountError): Nothing = throw AccountProtocolFailure(error)
    private class AccountLockWaitExceeded : RuntimeException()
}
