package saien.someday.server.auth

import java.util.UUID

val ACCOUNT_INITIAL_INCARNATION: UUID = UUID(0, 0)

/** Verified token identity and request preconditions, never a cached authorization decision. */
data class AccountRequestContext(
    val userId: UUID,
    val sessionId: UUID,
    val tokenDeviceId: UUID?,
    val tokenScopes: Set<String>,
    val protocol: AccountProtocolExpectation = AccountProtocolExpectation.Missing,
)

sealed interface AccountProtocolExpectation {
    data object Missing : AccountProtocolExpectation
    data class V1(val incarnation: UUID) : AccountProtocolExpectation
    data object Invalid : AccountProtocolExpectation
    data object Unsupported : AccountProtocolExpectation
}

enum class AccountAccess(val scope: String) {
    ACCOUNT("auth"), DEVICES("devices"), SYNC("sync"), ADMIN("auth"),
}

enum class AccountError(val status: Int, val code: String) {
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    DEVICE_REQUIRED(403, "device_required"),
    DEVICE_REVOKED(401, "device_revoked"),
    INVALID_REQUEST(400, "invalid_request"),
    ACCOUNT_SESSION_STALE(401, "account_session_stale"),
    ACCOUNT_INCARNATION_MISMATCH(409, "account_incarnation_mismatch"),
    WORKSPACE_INCARNATION_RETIRED(409, "workspace_incarnation_retired"),
    ACCOUNT_PROTOCOL_UPGRADE_REQUIRED(426, "account_protocol_upgrade_required"),
    ACCOUNT_BUSY(503, "account_busy"),
    RESET_REQUEST_CONFLICT(409, "reset_request_conflict"),
    RETIRED_MEDIA_PENDING(409, "retired_media_pending"),
    ACCOUNT_RESET_UNAVAILABLE(503, "account_reset_unavailable"),
    RESET_RATE_LIMITED(429, "reset_rate_limited"),
}

/** Fixed public reason only: no credentials, arbitrary response bodies or SQL details. */
class AccountProtocolFailure(val error: AccountError) : RuntimeException(error.code)
