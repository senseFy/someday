package saien.someday.server.routes

import saien.someday.server.ServerContext
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.LogoutRequest
import saien.someday.server.api.MeResponse
import saien.someday.server.api.RefreshRequest
import saien.someday.server.api.StatusResponse
import saien.someday.server.api.UserResponse
import saien.someday.server.auth.CredentialWorkUnavailableException
import saien.someday.server.auth.isValidAccountEmail
import saien.someday.server.auth.isValidAccountPassword
import saien.someday.server.auth.normalizeAccountEmail
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.UserRecord
import saien.someday.server.persistence.VerifiedPasswordAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

fun Route.authRoutes(context: ServerContext) {
    route("/auth") {
        post("/register") {
            if (!context.config.registrationEnabled) {
                call.respondError(HttpStatusCode.Forbidden, "registration_disabled")
                return@post
            }
            val request = call.receiveJsonOrNull<AuthRequest>() ?: return@post
            val email = normalizeAccountEmail(request.email)
            if (!call.requireAuthenticationRateLimit(context, "register", email.takeIf(::isValidAccountEmail))) {
                return@post
            }
            if (!isValidAccountEmail(email) || !isValidAccountPassword(request.password)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }

            val passwordHash = try {
                context.credentialHasher.hash(request.password)
            } catch (_: CredentialWorkUnavailableException) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "authentication_busy")
                return@post
            }
            val user = context.repository.createUser(email, passwordHash)
            if (user == null) {
                call.respondError(HttpStatusCode.Conflict, "account_exists")
                return@post
            }
            val response = call.issueSessionResponse(context, user)
            call.respond(response)
        }

        post("/login") {
            val request = call.receiveJsonOrNull<AuthRequest>() ?: return@post
            val email = normalizeAccountEmail(request.email)
            if (!call.requireAuthenticationRateLimit(context, "login", email.takeIf(::isValidAccountEmail))) {
                return@post
            }
            if (!isValidAccountEmail(email) || !isValidAccountPassword(request.password)) {
                call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials")
                return@post
            }
            val user = context.repository.findUserByEmail(email)
            val passwordMatches = try {
                context.credentialHasher.verify(user?.passwordHash ?: context.dummyPasswordHash, request.password)
            } catch (_: CredentialWorkUnavailableException) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "authentication_busy")
                return@post
            }
            if (user == null || user.disabledAt != null || !passwordMatches) {
                call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials")
                return@post
            }
            val response = call.issueSessionResponse(context, user)
            call.respond(response)
        }

        post("/refresh") {
            val request = call.receiveJsonOrNull<RefreshRequest>() ?: return@post
            if (!call.requireRateLimit(context, "auth:refresh:client:${call.clientRateLimitKey(context)}")) return@post
            val newRefreshToken = context.tokenService.issueRefreshToken()
            val refreshSnapshot = context.repository.rotateRefreshToken(
                oldRefreshTokenHash = context.tokenService.hashRefreshToken(request.refreshToken),
                newRefreshTokenHash = newRefreshToken.refreshTokenHash,
                refreshExpiresAt = Instant.now().plus(context.config.refreshTokenTtl),
            )
            if (refreshSnapshot == null) {
                call.respondError(HttpStatusCode.Unauthorized, "unauthorized")
                return@post
            }
            val accessTokens = context.tokenService.issueTokens(
                userId = refreshSnapshot.userId,
                sessionId = refreshSnapshot.sessionId,
                deviceId = refreshSnapshot.deviceId,
                isAdmin = refreshSnapshot.isAdmin,
                scopes = scopesForDevice(refreshSnapshot.deviceId),
            )
            call.respondAccountIncarnation(refreshSnapshot.incarnation)
            call.respond(
                AuthTokensResponse(
                    accessToken = accessTokens.accessToken,
                    refreshToken = newRefreshToken.refreshToken,
                    expiresInSeconds = accessTokens.expiresInSeconds,
                    user = UserResponse(
                        id = refreshSnapshot.userId.toString(),
                        email = refreshSnapshot.email,
                    ),
                ),
            )
        }

        post("/logout") {
            call.receiveJsonOrNull<LogoutRequest>() ?: return@post
            val auth = call.requireAuthenticated(context, requiredScope = "auth") ?: return@post
            context.repository.logout(auth.requestContext)
            call.respond(StatusResponse(status = "ok"))
        }
    }

    get("/me") {
        val auth = call.requireAuthenticated(context, requiredScope = "auth") ?: return@get
        val admitted = context.repository.admitRequest(auth.requestContext)
        call.respond(
            MeResponse(
                id = admitted.userId.toString(),
                email = admitted.email,
                deviceId = admitted.deviceId?.toString(),
                scopes = auth.scopes.sorted(),
            ),
        )
    }
}

private fun ApplicationCall.issueSessionResponse(
    context: ServerContext,
    user: UserRecord,
): AuthTokensResponse {
    val sessionId = UUID.randomUUID()
    val refresh = context.tokenService.issueRefreshToken()
    val issued = context.repository.issuePasswordSession(
        verified = VerifiedPasswordAccount(user.id, user.passwordHash),
        sessionId = sessionId,
        refreshTokenHash = refresh.refreshTokenHash,
        sessionExpiresAt = Instant.now().plus(context.config.refreshTokenTtl),
        refreshExpiresAt = Instant.now().plus(context.config.refreshTokenTtl),
    )
    val tokens = context.tokenService.issueTokens(
        userId = issued.userId,
        sessionId = issued.sessionId,
        deviceId = issued.deviceId,
        isAdmin = issued.isAdmin,
        scopes = scopesForDevice(issued.deviceId),
    )
    respondAccountIncarnation(issued.incarnation)
    return AuthTokensResponse(
        accessToken = tokens.accessToken,
        refreshToken = refresh.refreshToken,
        expiresInSeconds = tokens.expiresInSeconds,
        user = UserResponse(
            id = issued.userId.toString(),
            email = issued.email,
        ),
    )
}
