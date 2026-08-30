package saien.someday.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.util.Base64
import saien.someday.server.ServerContext
import saien.someday.server.api.WorkspaceRecoveryEnvelopePutRequest
import saien.someday.server.api.WorkspaceRecoveryEnvelopeResponse
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeInput
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopePutResult
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeRecord

fun Route.workspaceRecoveryEnvelopeRoutes(context: ServerContext) {
    route("/workspace/recovery-envelope") {
        get {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@get
            val deviceId = auth.tokenDeviceId
                ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireRateLimit(context, "recovery-envelope-read:${auth.userId}:$deviceId")) return@get

            call.response.header(HttpHeaders.CacheControl, "no-store")
            val record = context.workspaceRecoveryEnvelopeRepository.load(auth.userId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found")
            call.respond(record.toResponse())
        }

        put {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@put
            val deviceId = auth.tokenDeviceId
                ?: return@put call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireRateLimit(context, "recovery-envelope-write:${auth.userId}:$deviceId")) return@put

            val request = call.receiveJsonOrNull<WorkspaceRecoveryEnvelopePutRequest>(
                MAX_RECOVERY_ENVELOPE_REQUEST_BYTES,
            ) ?: return@put
            if (!request.isValid()) {
                return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request")
            }
            when (
                val result = context.workspaceRecoveryEnvelopeRepository.put(
                    userId = auth.userId,
                    deviceId = deviceId,
                    input = WorkspaceRecoveryEnvelopeInput(
                        workspaceId = request.workspaceId,
                        keyFingerprint = request.keyFingerprint,
                        envelopeJson = request.envelopeJson,
                        envelopeDigest = request.envelopeDigest,
                        expectedRevision = request.expectedRevision,
                    ),
                )
            ) {
                is WorkspaceRecoveryEnvelopePutResult.Stored -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(
                        if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
                        result.record.toResponse(),
                    )
                }
                WorkspaceRecoveryEnvelopePutResult.Conflict ->
                    call.respondError(HttpStatusCode.Conflict, "recovery_envelope_conflict")
                WorkspaceRecoveryEnvelopePutResult.WorkspaceNotInitialized ->
                    call.respondError(HttpStatusCode.Conflict, "workspace_not_initialized")
            }
        }
    }
}

private fun WorkspaceRecoveryEnvelopePutRequest.isValid(): Boolean {
    val envelopeBytes = envelopeJson.encodeToByteArray()
    return WORKSPACE_ID.matches(workspaceId) &&
        KEY_FINGERPRINT.matches(keyFingerprint) &&
        envelopeBytes.size in 1..MAX_RECOVERY_ENVELOPE_BYTES &&
        envelopeJson.isNotBlank() &&
        '\u0000' !in envelopeJson &&
        RECOVERY_ENVELOPE_DIGEST.matches(envelopeDigest) &&
        digest(envelopeBytes) == envelopeDigest &&
        expectedRevision?.let { it >= 1 } != false
}

private fun WorkspaceRecoveryEnvelopeRecord.toResponse(): WorkspaceRecoveryEnvelopeResponse =
    WorkspaceRecoveryEnvelopeResponse(
        workspaceId = workspaceId,
        keyFingerprint = keyFingerprint,
        envelopeJson = envelopeJson,
        envelopeDigest = envelopeDigest,
        revision = revision,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun digest(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

private val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
private val KEY_FINGERPRINT = Regex("^[0-9a-f]{32}$")
private val RECOVERY_ENVELOPE_DIGEST = Regex("^[A-Za-z0-9_-]{43}$")
private const val MAX_RECOVERY_ENVELOPE_BYTES = 64 * 1_024
// envelopeJson is itself JSON-encoded inside the request. Six times the opaque
// UTF-8 limit covers the worst JSON string escaping expansion while keeping the
// request body bounded before deserialization.
private const val MAX_RECOVERY_ENVELOPE_REQUEST_BYTES = MAX_RECOVERY_ENVELOPE_BYTES * 6 + 4 * 1_024
