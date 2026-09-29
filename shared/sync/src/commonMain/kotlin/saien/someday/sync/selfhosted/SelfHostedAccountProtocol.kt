package saien.someday.sync.selfhosted

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.isCanonicalAccountIncarnation
import saien.someday.sync.StrictJsonV2

const val SELF_HOSTED_ACCOUNT_PROTOCOL_HEADER = "X-Someday-Account-Protocol"
const val SELF_HOSTED_ACCOUNT_INCARNATION_HEADER = "X-Someday-Account-Incarnation"
const val SELF_HOSTED_ERROR_CODE_HEADER = "X-Someday-Error-Code"
internal const val SELF_HOSTED_ACCOUNT_BODY_LIMIT = 4 * 1024

internal data class SelfHostedWireResponse(
    val status: Int,
    val body: String,
    val errorHeaders: List<String>?,
    val issuanceHeaders: List<String>?,
    val contentType: String?,
)

/** Captured credential/binding authority; discovery must never rewrite it. */
data class SelfHostedAccountRequestContext(
    val accountIncarnation: String = INITIAL_ACCOUNT_INCARNATION,
    val protocol1Known: Boolean = false,
) {
    init {
        require(isCanonicalAccountIncarnation(accountIncarnation))
        require(protocol1Known || accountIncarnation == INITIAL_ACCOUNT_INCARNATION)
    }
}

fun SelfHostedSessionCredentials.accountRequestContext() =
    SelfHostedAccountRequestContext(accountIncarnation, accountProtocolVersion == 1)

fun SelfHostedSyncSession.accountRequestContext() =
    SelfHostedAccountRequestContext(accountIncarnation, accountProtocolVersion == 1)

enum class SelfHostedErrorCode(val wireCode: String, vararg val statuses: Int) {
    UNAUTHORIZED("unauthorized", 401), FORBIDDEN("forbidden", 403),
    INVALID_CREDENTIALS("invalid_credentials", 401, 403),
    DEVICE_REQUIRED("device_required", 403), DEVICE_REVOKED("device_revoked", 401, 409),
    DEVICE_ID_ALREADY_CLAIMED("device_id_already_claimed", 409),
    INVALID_REQUEST("invalid_request", 400), REQUEST_BODY_TOO_LARGE("request_body_too_large", 413),
    REGISTRATION_DISABLED("registration_disabled", 403), ACCOUNT_EXISTS("account_exists", 409),
    AUTHENTICATION_BUSY("authentication_busy", 503), RATE_LIMITED("rate_limited", 429),
    ACCOUNT_SESSION_STALE("account_session_stale", 401),
    ACCOUNT_INCARNATION_MISMATCH("account_incarnation_mismatch", 409),
    WORKSPACE_INCARNATION_RETIRED("workspace_incarnation_retired", 409),
    ACCOUNT_PROTOCOL_UPGRADE_REQUIRED("account_protocol_upgrade_required", 426),
    ACCOUNT_BUSY("account_busy", 503), RESET_REQUEST_CONFLICT("reset_request_conflict", 409),
    RETIRED_MEDIA_PENDING("retired_media_pending", 409), ACCOUNT_RESET_UNAVAILABLE("account_reset_unavailable", 503),
    RESET_RATE_LIMITED("reset_rate_limited", 429),
    NOT_FOUND("not_found", 404), PAIRING_CONFLICT("pairing_conflict", 409),
    EXPIRED("expired", 410), PAIRING_LIMIT("pairing_limit", 429),
    RECOVERY_ENVELOPE_CONFLICT("recovery_envelope_conflict", 409), WORKSPACE_NOT_INITIALIZED("workspace_not_initialized", 409),
    INVALID_CHECKPOINT_CHUNK("invalid_checkpoint_chunk", 400), INVALID_CHECKPOINT_CLEANUP("invalid_checkpoint_cleanup", 400),
    INVALID_CHECKPOINT_IDENTITY("invalid_checkpoint_identity", 400), INVALID_CHECKPOINT_MANIFEST("invalid_checkpoint_manifest", 400),
    INVALID_CURSOR("invalid_cursor", 400), INVALID_EPOCH_POINTER("invalid_epoch_pointer", 400), INVALID_EPOCH("invalid_epoch", 400),
    INVALID_WORKSPACE_SCOPE("invalid_workspace_scope", 400), CHECKPOINT_CHUNK_NOT_FOUND("checkpoint_chunk_not_found", 404),
    CHECKPOINT_NOT_FOUND("checkpoint_not_found", 404), EPOCH_NOT_FOUND("epoch_not_found", 404),
    INVALID_MEDIA_DIGEST("invalid_media_digest", 400), INVALID_MEDIA_ID("invalid_media_id", 400),
    INVALID_MEDIA_OBJECT("invalid_media_object", 400), INVALID_WORKSPACE_ID("invalid_workspace_id", 400),
    INVALID_MEDIA_CONTENT_TYPE("invalid_media_content_type", 415), MEDIA_OBJECT_TOO_LARGE("media_object_too_large", 413),
    MEDIA_OBJECT_NOT_FOUND("media_object_not_found", 404), MEDIA_OBJECT_UNAVAILABLE("media_object_unavailable", 404),
    INTERNAL_ERROR("internal_error", 500),
}

enum class SelfHostedProtocolFailureReason {
    INVALID_ERROR_HEADER, UNKNOWN_ERROR_CODE, ERROR_STATUS_MISMATCH, ERROR_BODY_MISMATCH,
    MISSING_ERROR_HEADER, MALFORMED_BODY, BODY_TOO_LARGE, INVALID_ISSUANCE_HEADER,
    MISSING_ISSUANCE_HEADER, INVALID_CONTROL_RESPONSE, REDIRECTED_RESPONSE, UNVERIFIED_LEGACY,
}

class SelfHostedProtocolException(val reason: SelfHostedProtocolFailureReason) :
    RuntimeException("Self-hosted protocol failure: ${reason.name.lowercase()}; response redacted.")

@Serializable
data class SelfHostedAccountDataStateResponse(
    val protocolVersion: Int,
    val accountIncarnation: String,
    val resetAvailable: Boolean,
    val resetUnavailableReason: String?,
)

@Serializable
data class SelfHostedAccountMeResponse(val id: String, val email: String, val deviceId: String?, val scopes: List<String>)

@Serializable
class SelfHostedAccountResetRequest(
    val protocolVersion: Int = 1,
    val operationId: String,
    val expectedIncarnation: String,
    val confirmation: String = "reset-all-account-data-v1",
    val password: String,
) {
    override fun toString(): String = "SelfHostedAccountResetRequest(protocolVersion=$protocolVersion, password=<redacted>)"
}

@Serializable
data class SelfHostedAccountResetReceiptResponse(
    val protocolVersion: Int,
    val operationId: String,
    val previousIncarnation: String,
    val newIncarnation: String,
    val committedAtEpochMillis: Long,
)

sealed interface SelfHostedAccountDiscoveryResult {
    data class Protocol1(val state: SelfHostedAccountDataStateResponse) : SelfHostedAccountDiscoveryResult
    /** Only a candidate: callers must validate same-credential /me and durable capability history. */
    data object LegacyCandidate404 : SelfHostedAccountDiscoveryResult
}

interface SelfHostedAccountControlTransport {
    fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedAccountDiscoveryResult
    fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedAccountMeResponse
    fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedAccountResetReceiptResponse?
    fun resetAccountData(endpoint: String, accessToken: String, request: SelfHostedAccountResetRequest, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedAccountResetReceiptResponse
}

@Serializable
private data class AccountErrorBody(val error: String)

internal object SelfHostedAccountWire {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun errorBodyLimit(status: Int, errorHeaders: List<String>?, acceptedStatuses: Set<Int>): Boolean =
        errorHeaders != null || (status !in 200..299 && status !in acceptedStatuses)

    fun classify(
        status: Int,
        errorHeaders: List<String>?,
        body: String,
        accountContext: SelfHostedAccountRequestContext,
        acceptedStatuses: Set<Int> = emptySet(),
        head: Boolean = false,
    ) {
        if (errorHeaders != null) {
            if (errorHeaders.size != 1 || errorHeaders.single().isEmpty()) fail(SelfHostedProtocolFailureReason.INVALID_ERROR_HEADER)
            val code = SelfHostedErrorCode.entries.singleOrNull { it.wireCode == errorHeaders.single() }
                ?: fail(SelfHostedProtocolFailureReason.UNKNOWN_ERROR_CODE)
            if (status !in code.statuses) fail(SelfHostedProtocolFailureReason.ERROR_STATUS_MISMATCH)
            if (!head) {
                val decoded = decode(AccountErrorBody.serializer(), body, SELF_HOSTED_ACCOUNT_BODY_LIMIT)
                if (decoded.error != code.wireCode) fail(SelfHostedProtocolFailureReason.ERROR_BODY_MISMATCH)
            }
            throw SelfHostedSyncHttpException(status, "Self-hosted request failed with HTTP $status (${code.wireCode}); credentials redacted.", code, protocol1 = true)
        }
        if (status !in 200..299 && status !in acceptedStatuses) {
            if (accountContext.protocol1Known) fail(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER)
            throw SelfHostedSyncHttpException(status, "Self-hosted request failed with HTTP $status; credentials redacted.")
        }
        if (status in acceptedStatuses && accountContext.protocol1Known && !head) {
            val objectBody = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = (objectBody?.get("error") as? JsonPrimitive)?.content
            if (objectBody?.keys == setOf("error") || error in setOf(
                    "account_session_stale", "account_incarnation_mismatch", "workspace_incarnation_retired",
                    "account_protocol_upgrade_required", "account_busy",
                )
            ) fail(SelfHostedProtocolFailureReason.MISSING_ERROR_HEADER)
        }
    }

    fun issuance(headers: List<String>?, context: SelfHostedAccountRequestContext): String? {
        if (headers == null) {
            if (context.protocol1Known) fail(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER)
            return null
        }
        if (headers.size != 1 || !isCanonicalAccountIncarnation(headers.single())) fail(SelfHostedProtocolFailureReason.INVALID_ISSUANCE_HEADER)
        return headers.single()
    }

    fun <T> captureIssuance(value: T, headers: List<String>?, context: SelfHostedAccountRequestContext): T {
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is SelfHostedAuthTokensResponse -> issuance(headers, context).let { value.copy(accountIncarnation = it, accountProtocolVersion = it?.let { 1 }) }
            is SelfHostedDeviceRegistrationResponse -> issuance(headers, context).let { value.copy(accountIncarnation = it, accountProtocolVersion = it?.let { 1 }) }
            else -> value
        } as T
    }

    fun <T> decode(serializer: KSerializer<T>, body: String, limit: Int): T {
        if (body.encodeToByteArray().size > limit) fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
        return try {
            StrictJsonV2.requireValidObjectKeys(body, limit)
            json.decodeFromString(serializer, body)
        } catch (_: Exception) {
            fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
        }
    }

    fun legacyCandidate(status: Int, errorHeaders: List<String>?, issuanceHeaders: List<String>?, body: String, contentType: String?, context: SelfHostedAccountRequestContext): Boolean {
        if (status != 404 || errorHeaders != null || issuanceHeaders != null || context.protocol1Known) return false
        if (body.isEmpty()) return true
        if (contentType?.substringBefore(';')?.trim() != "application/json") fail(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
        if (decode(AccountErrorBody.serializer(), body, SELF_HOSTED_ACCOUNT_BODY_LIMIT).error != "not_found") fail(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
        return true
    }

    fun validateState(state: SelfHostedAccountDataStateResponse): SelfHostedAccountDataStateResponse = state.also {
        if (it.protocolVersion != 1 || !isCanonicalAccountIncarnation(it.accountIncarnation) ||
            (it.resetAvailable && it.resetUnavailableReason != null) ||
            (!it.resetAvailable && it.resetUnavailableReason !in setOf("deployment_not_ready", "retired_media_pending"))
        ) fail(SelfHostedProtocolFailureReason.INVALID_CONTROL_RESPONSE)
    }

    fun validateReceipt(receipt: SelfHostedAccountResetReceiptResponse, operationId: String): SelfHostedAccountResetReceiptResponse = receipt.also {
        if (it.protocolVersion != 1 || it.operationId != operationId || !isOperationId(it.operationId) ||
            !isCanonicalAccountIncarnation(it.previousIncarnation) || !isOperationId(it.newIncarnation) ||
            it.previousIncarnation == it.newIncarnation || it.committedAtEpochMillis < 0
        ) fail(SelfHostedProtocolFailureReason.INVALID_CONTROL_RESPONSE)
    }

    fun validateMe(me: SelfHostedAccountMeResponse): SelfHostedAccountMeResponse = me.also {
        if (!isCanonicalAccountIncarnation(it.id) || it.email.isBlank() || "auth" !in it.scopes ||
            (it.deviceId != null && !isCanonicalAccountIncarnation(it.deviceId)) || it.scopes.distinct() != it.scopes
        ) fail(SelfHostedProtocolFailureReason.INVALID_CONTROL_RESPONSE)
    }

    fun validateResetRequest(request: SelfHostedAccountResetRequest) {
        require(request.protocolVersion == 1 && isOperationId(request.operationId) &&
            isCanonicalAccountIncarnation(request.expectedIncarnation) && request.confirmation == "reset-all-account-data-v1") {
            "Invalid account reset request identity; password redacted."
        }
    }

    fun isOperationId(value: String): Boolean = isCanonicalAccountIncarnation(value) && value[14] == '4' && value[19] in "89ab"

    fun fail(reason: SelfHostedProtocolFailureReason): Nothing = throw SelfHostedProtocolException(reason)
}
