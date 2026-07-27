package saien.someday.server.routes

import saien.someday.server.ServerContext
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.DeviceResponse
import saien.someday.server.api.DevicesResponse
import saien.someday.server.api.StatusResponse
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.DeviceRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

fun Route.deviceRoutes(context: ServerContext) {
    route("/devices") {
        post("/register") {
            val auth = call.requireAuthenticated(context, requiredScope = "devices") ?: return@post
            if (!call.requireRateLimit(context, "device-register:${auth.userId}")) return@post
            val request = call.receiveJsonOrNull<DeviceRegistrationRequest>() ?: return@post
            val deviceName = request.name.trim()
            val platform = request.platform.trim().lowercase()
            if (deviceName.isBlank() || platform.isBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }

            val refreshToken = context.tokenService.issueRefreshToken()
            val deviceSession = context.repository.registerDevice(
                userId = auth.userId,
                currentSessionId = auth.sessionId,
                currentDeviceId = auth.deviceId,
                name = deviceName,
                platform = platform,
                refreshTokenHash = refreshToken.refreshTokenHash,
                sessionExpiresAt = Instant.now().plus(context.config.refreshTokenTtl),
                refreshExpiresAt = Instant.now().plus(context.config.refreshTokenTtl),
            )
            val tokens = context.tokenService.issueTokens(
                userId = auth.userId,
                sessionId = deviceSession.sessionId,
                deviceId = deviceSession.device.id,
                isAdmin = auth.isAdmin,
                scopes = scopesForDevice(deviceSession.device.id),
            )
            call.respond(
                DeviceRegistrationResponse(
                    device = deviceSession.device.toResponse(),
                    accessToken = tokens.accessToken,
                    refreshToken = refreshToken.refreshToken,
                    expiresInSeconds = tokens.expiresInSeconds,
                ),
            )
        }

        get {
            val auth = call.requireAuthenticated(context, requiredScope = "devices") ?: return@get
            call.respond(
                DevicesResponse(
                    devices = context.repository.listDevices(auth.userId).map { it.toResponse() },
                ),
            )
        }

        delete("/{id}") {
            val auth = call.requireAuthenticated(context, requiredScope = "devices") ?: return@delete
            val deviceId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (deviceId == null) {
                call.respondError(HttpStatusCode.NotFound, "not_found")
                return@delete
            }
            val revoked = context.repository.revokeDevice(auth.userId, deviceId)
            if (!revoked) {
                call.respondError(HttpStatusCode.NotFound, "not_found")
                return@delete
            }
            call.respond(StatusResponse(status = "ok"))
        }
    }
}

private fun DeviceRecord.toResponse(): DeviceResponse =
    DeviceResponse(
        id = id.toString(),
        name = name,
        platform = platform,
        revoked = revokedAt != null,
    )
