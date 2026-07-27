package saien.someday.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import saien.someday.server.ServerContext
import saien.someday.server.api.PairingInviteClaimRequest
import saien.someday.server.api.PairingInviteClaimResponse
import saien.someday.server.api.PairingInviteCompleteRequest
import saien.someday.server.api.PairingInviteCreateRequest
import saien.someday.server.api.PairingInviteCreateResponse
import saien.someday.server.persistence.PairingInviteClaimResult
import saien.someday.server.persistence.PairingInviteCreateResult
import saien.someday.server.persistence.PairingInviteMutationResult

fun Route.pairingRoutes(context: ServerContext) {
    route("/pairing/invites") {
        put("/{inviteId}") {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@put
            val deviceId = auth.tokenDeviceId ?: return@put call.respondError(
                HttpStatusCode.Forbidden,
                "device_required",
            )
            if (!call.requireRateLimit(context, "pairing-create:${auth.userId}:$deviceId")) return@put
            val inviteId = call.parameters["inviteId"]?.trim().orEmpty()
            if (!inviteId.isPairingIdentifier()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@put
            }
            val request = call.receiveJsonOrNull<PairingInviteCreateRequest>(
                MAX_PAIRING_CREATE_REQUEST_BYTES,
            ) ?: return@put
            if (request.envelopeJson.isBlank() ||
                request.envelopeJson.encodeToByteArray().size > MAX_PAIRING_ENVELOPE_BYTES ||
                request.envelopeDigest != digest(request.envelopeJson.encodeToByteArray())
            ) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@put
            }
            val now = Instant.now()
            val requestedExpiry = runCatching { Instant.ofEpochMilli(request.expiresAtEpochMillis) }.getOrNull()
            if (requestedExpiry == null || !requestedExpiry.isAfter(now)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@put
            }
            val maximumExpiry = now.plusSeconds(PAIRING_TTL_SECONDS)
            val effectiveExpiry = if (requestedExpiry.isAfter(maximumExpiry)) maximumExpiry else requestedExpiry
            when (
                val result = context.repository.createWorkspacePairingInvite(
                    userId = auth.userId,
                    inviteId = inviteId,
                    creatorDeviceId = deviceId,
                    envelopeJson = request.envelopeJson,
                    envelopeDigest = request.envelopeDigest,
                    expiresAt = effectiveExpiry,
                    activeLimit = ACTIVE_INVITE_LIMIT,
                )
            ) {
                is PairingInviteCreateResult.Created ->
                    call.respond(
                        HttpStatusCode.Created,
                        PairingInviteCreateResponse("created", result.expiresAt.toEpochMilli()),
                    )
                is PairingInviteCreateResult.Replay ->
                    call.respond(
                        HttpStatusCode.OK,
                        PairingInviteCreateResponse("replay", result.expiresAt.toEpochMilli()),
                    )
                PairingInviteCreateResult.Conflict ->
                    call.respondError(HttpStatusCode.Conflict, "pairing_conflict")
                PairingInviteCreateResult.LimitReached ->
                    call.respondError(HttpStatusCode.TooManyRequests, "pairing_limit")
            }
        }

        post("/{inviteId}/claim") {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(
                HttpStatusCode.Forbidden,
                "device_required",
            )
            if (!call.requireRateLimit(context, "pairing-claim:${auth.userId}:$deviceId")) return@post
            val inviteId = call.parameters["inviteId"]?.trim().orEmpty()
            val request = call.receiveJsonOrNull<PairingInviteClaimRequest>(
                MAX_PAIRING_STATE_REQUEST_BYTES,
            ) ?: return@post
            if (!inviteId.isPairingIdentifier() || !request.claimId.isPairingIdentifier()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            when (
                val result = context.repository.claimWorkspacePairingInvite(
                    userId = auth.userId,
                    inviteId = inviteId,
                    claimId = request.claimId,
                    claimDeviceId = deviceId,
                    now = Instant.now(),
                )
            ) {
                is PairingInviteClaimResult.Claimed -> {
                    val envelope = result.record.envelopeJson
                        ?: return@post call.respondError(HttpStatusCode.Conflict, "pairing_conflict")
                    call.respond(
                        PairingInviteClaimResponse(
                            envelopeJson = envelope,
                            envelopeDigest = result.record.envelopeDigest,
                            expiresAtEpochMillis = result.record.expiresAt.toEpochMilli(),
                        ),
                    )
                }
                PairingInviteClaimResult.NotFound ->
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                PairingInviteClaimResult.Expired ->
                    call.respondError(HttpStatusCode.Gone, "expired")
                PairingInviteClaimResult.Conflict ->
                    call.respondError(HttpStatusCode.Conflict, "pairing_conflict")
            }
        }

        post("/{inviteId}/complete") {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(
                HttpStatusCode.Forbidden,
                "device_required",
            )
            if (!call.requireRateLimit(context, "pairing-complete:${auth.userId}:$deviceId")) return@post
            val inviteId = call.parameters["inviteId"]?.trim().orEmpty()
            val request = call.receiveJsonOrNull<PairingInviteCompleteRequest>(
                MAX_PAIRING_STATE_REQUEST_BYTES,
            ) ?: return@post
            if (!inviteId.isPairingIdentifier() || !request.claimId.isPairingIdentifier()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            when (
                context.repository.completeWorkspacePairingInvite(
                    userId = auth.userId,
                    inviteId = inviteId,
                    claimId = request.claimId,
                    claimDeviceId = deviceId,
                    now = Instant.now(),
                )
            ) {
                PairingInviteMutationResult.Completed -> call.respond(HttpStatusCode.NoContent)
                PairingInviteMutationResult.NotFound ->
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                PairingInviteMutationResult.Expired ->
                    call.respondError(HttpStatusCode.Gone, "expired")
                PairingInviteMutationResult.Conflict ->
                    call.respondError(HttpStatusCode.Conflict, "pairing_conflict")
            }
        }

        post("/{inviteId}/cancel") {
            val auth = call.requireAuthenticated(
                context,
                requiredScope = "sync",
                requireDevice = true,
            ) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(
                HttpStatusCode.Forbidden,
                "device_required",
            )
            if (!call.requireRateLimit(context, "pairing-cancel:${auth.userId}:$deviceId")) return@post
            val inviteId = call.parameters["inviteId"]?.trim().orEmpty()
            if (!inviteId.isPairingIdentifier()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            when (
                context.repository.cancelWorkspacePairingInvite(
                    userId = auth.userId,
                    inviteId = inviteId,
                    creatorDeviceId = deviceId,
                    now = Instant.now(),
                )
            ) {
                PairingInviteMutationResult.Completed -> call.respond(HttpStatusCode.NoContent)
                PairingInviteMutationResult.NotFound ->
                    call.respondError(HttpStatusCode.NotFound, "not_found")
                PairingInviteMutationResult.Expired ->
                    call.respondError(HttpStatusCode.Gone, "expired")
                PairingInviteMutationResult.Conflict ->
                    call.respondError(HttpStatusCode.Conflict, "pairing_conflict")
            }
        }
    }
}

private fun String.isPairingIdentifier(): Boolean =
    length == PAIRING_IDENTIFIER_CHARS &&
        all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

private fun digest(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

private const val PAIRING_IDENTIFIER_CHARS = 22
private const val MAX_PAIRING_ENVELOPE_BYTES = 64 * 1_024
private const val MAX_PAIRING_CREATE_REQUEST_BYTES = MAX_PAIRING_ENVELOPE_BYTES + 4 * 1_024
private const val MAX_PAIRING_STATE_REQUEST_BYTES = 1_024
private const val PAIRING_TTL_SECONDS = 10 * 60L
private const val ACTIVE_INVITE_LIMIT = 8
