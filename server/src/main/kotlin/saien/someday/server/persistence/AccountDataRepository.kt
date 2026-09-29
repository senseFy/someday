package saien.someday.server.persistence

import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import saien.someday.server.ServerConfig
import saien.someday.server.api.AccountDataResetReceiptResponse
import saien.someday.server.api.AccountDataStateResponse
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext

data class AccountResetIdentity(
    val operationId: UUID,
    val expectedIncarnation: UUID,
    val protocolVersion: Int = 1,
)

sealed interface AccountResetPrecheck {
    data class Replay(val receipt: AccountDataResetReceiptResponse) : AccountResetPrecheck
    class PasswordRequired(val passwordHashSnapshot: String) : AccountResetPrecheck {
        override fun toString(): String = "PasswordRequired([REDACTED])"
    }
}

class AccountDataRepository(
    private val config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
    private val clock: Clock = Clock.systemUTC(),
    private val readiness: () -> Boolean = { config.accountResetEnabled },
    private val verifyReadiness: () -> Boolean = readiness,
) {
    fun discover(request: AccountRequestContext): AccountDataStateResponse {
        val state = discoverCurrent(request)
        // Recover a failed readiness probe on discovery too: clients may correctly
        // avoid POST while unavailable. Verify outside the admission transaction.
        return if (config.accountResetEnabled && state.resetUnavailableReason == "deployment_not_ready" && verifyReadiness()) {
            discoverCurrent(request)
        } else state
    }

    private fun discoverCurrent(request: AccountRequestContext): AccountDataStateResponse =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val account = AccountAdmission.admit(connection, request, AccountAccess.ACCOUNT)
            val reason = when {
                !config.accountResetEnabled || !readiness() -> "deployment_not_ready"
                hasUnreclaimedMedia(connection, request.userId) -> "retired_media_pending"
                else -> null
            }
            AccountDataStateResponse(
                accountIncarnation = account.incarnation.toString(),
                resetAvailable = reason == null,
                resetUnavailableReason = reason,
            )
        }

    fun receipt(request: AccountRequestContext, operationId: UUID): AccountDataResetReceiptResponse? =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            AccountAdmission.admit(connection, request, AccountAccess.ACCOUNT)
            selectReceipt(connection, request.userId, operationId)
        }

    fun prepareReset(request: AccountRequestContext, identity: AccountResetIdentity): AccountResetPrecheck =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            admitReset(connection, request, identity)
            exactReceipt(connection, request.userId, identity)?.let { return@transaction AccountResetPrecheck.Replay(it) }
            val passwordHash = connection.prepareStatement(
                "SELECT password_hash FROM someday_users WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, request.userId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getString(1)
                }
            }
            AccountResetPrecheck.PasswordRequired(passwordHash)
        }

    fun reset(
        request: AccountRequestContext,
        identity: AccountResetIdentity,
        verified: VerifiedPasswordAccount,
    ): AccountDataResetReceiptResponse {
        // Storage verification may perform network IO. Never retain a connection or
        // account lock while it runs. Exact replay normally returned in prepareReset.
        val storageReady = config.accountResetEnabled && verifyReadiness()
        return AccountAdmission.transaction(
            connections, listOf(request.userId), AccountLockMode.EXCLUSIVE,
            scope = { connection ->
                connection.prepareStatement("SELECT set_config('someday.user_id', ?, true), set_config('someday.workspace_id', '*', true)").use { statement ->
                    statement.setString(1, request.userId.toString())
                    statement.executeQuery().close()
                }
            },
        ) { connection ->
            val account = admitReset(connection, request, identity)
            exactReceipt(connection, request.userId, identity)?.let { return@transaction it }
            if (verified.userId != request.userId) fail(AccountError.UNAUTHORIZED)
            connection.prepareStatement("SELECT password_hash FROM someday_users WHERE id = ? AND disabled_at IS NULL").use { statement ->
                statement.setObject(1, request.userId)
                statement.executeQuery().use { result ->
                    if (!result.next() || result.getString(1) != verified.passwordHashSnapshot) fail(AccountError.UNAUTHORIZED)
                }
            }
            if (identity.expectedIncarnation != account.incarnation) fail(AccountError.ACCOUNT_INCARNATION_MISMATCH)
            if (!storageReady || !readiness()) fail(AccountError.ACCOUNT_RESET_UNAVAILABLE)
            if (hasUnreclaimedMedia(connection, request.userId)) fail(AccountError.RETIRED_MEDIA_PENDING)
            connection.prepareStatement(
                "SELECT COUNT(*) FROM someday_account_data_resets WHERE user_id = ? AND committed_at > ?",
            ).use { statement ->
                statement.setObject(1, request.userId)
                statement.setObject(2, OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofHours(24)), java.time.ZoneOffset.UTC))
                statement.executeQuery().use { result ->
                    check(result.next())
                    if (result.getLong(1) >= 3) fail(AccountError.RESET_RATE_LIMITED)
                }
            }
            // clock_timestamp is sampled only after exclusive admission has drained
            // publishers. Transaction-start NOW() would shorten S3's settling period.
            val retiredAt = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT clock_timestamp()").use { result ->
                    check(result.next())
                    result.getObject(1, OffsetDateTime::class.java)
                }
            }
            val successor = UUID.randomUUID()
            connection.prepareStatement(
                "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = ?, media_reclaimed_at = NULL WHERE user_id = ? AND incarnation = ? AND state = 'active'",
            ).use { statement ->
                statement.setObject(1, retiredAt)
                statement.setObject(2, request.userId)
                statement.setObject(3, account.incarnation)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', ?)",
            ).use { statement ->
                statement.setObject(1, request.userId)
                statement.setObject(2, successor)
                statement.setObject(3, retiredAt)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement("DELETE FROM workspace_recovery_envelopes WHERE user_id = ?").use { statement ->
                statement.setObject(1, request.userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO someday_account_data_resets(user_id, operation_id, protocol_version, expected_incarnation, new_incarnation, committed_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, request.userId)
                statement.setObject(2, identity.operationId)
                statement.setInt(3, identity.protocolVersion)
                statement.setObject(4, account.incarnation)
                statement.setObject(5, successor)
                statement.setObject(6, retiredAt)
                check(statement.executeUpdate() == 1)
            }
            checkNotNull(selectReceipt(connection, request.userId, identity.operationId))
        }
    }

    private fun admitReset(connection: Connection, request: AccountRequestContext, identity: AccountResetIdentity): AdmittedAccount {
        // A current session can replay an older operation. Only the expected-header
        // equality with CURRENT authority is exempted, never current authentication.
        val account = AccountAdmission.admit(connection, request.copy(protocol = AccountProtocolExpectation.Missing), AccountAccess.ACCOUNT)
        if (identity.protocolVersion != 1) fail(AccountError.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED)
        if (identity.operationId.version() != 4 || identity.operationId.variant() != 2) {
            fail(AccountError.INVALID_REQUEST)
        }
        when (val protocol = request.protocol) {
            is AccountProtocolExpectation.V1 -> if (protocol.incarnation != identity.expectedIncarnation) fail(AccountError.INVALID_REQUEST)
            AccountProtocolExpectation.Invalid -> fail(AccountError.INVALID_REQUEST)
            AccountProtocolExpectation.Missing, AccountProtocolExpectation.Unsupported -> fail(AccountError.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED)
        }
        return account
    }

    private fun exactReceipt(connection: Connection, userId: UUID, identity: AccountResetIdentity): AccountDataResetReceiptResponse? =
        selectReceipt(connection, userId, identity.operationId)?.also { receipt ->
            if (receipt.protocolVersion != identity.protocolVersion || receipt.previousIncarnation != identity.expectedIncarnation.toString()) {
                fail(AccountError.RESET_REQUEST_CONFLICT)
            }
        }

    private fun selectReceipt(connection: Connection, userId: UUID, operationId: UUID): AccountDataResetReceiptResponse? =
        connection.prepareStatement(
            "SELECT protocol_version, expected_incarnation, new_incarnation, committed_at FROM someday_account_data_resets WHERE user_id = ? AND operation_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, operationId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else AccountDataResetReceiptResponse(
                    protocolVersion = result.getInt("protocol_version"),
                    operationId = operationId.toString(),
                    previousIncarnation = result.getObject("expected_incarnation", UUID::class.java).toString(),
                    newIncarnation = result.getObject("new_incarnation", UUID::class.java).toString(),
                    committedAtEpochMillis = result.getObject("committed_at", OffsetDateTime::class.java).toInstant().toEpochMilli(),
                )
            }
        }

    private fun hasUnreclaimedMedia(connection: Connection, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM someday_account_data_incarnations WHERE user_id = ? AND state = 'retired' AND media_reclaimed_at IS NULL)",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { result -> check(result.next()); result.getBoolean(1) }
    }

    /** Startup inventory only; this is not exposed as an account API. */
    internal fun hasNonInitialIncarnations(): Boolean = connections.connection().use { connection ->
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM someday_account_data_incarnations WHERE state = 'active' AND incarnation <> ?)",
        ).use { statement ->
            statement.setObject(1, ACCOUNT_INITIAL_INCARNATION)
            statement.executeQuery().use { result -> check(result.next()); result.getBoolean(1) }
        }
    }

    private fun fail(error: AccountError): Nothing = throw AccountProtocolFailure(error)
}
