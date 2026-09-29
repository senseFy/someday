package saien.someday.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import saien.someday.server.ServerContext
import saien.someday.server.api.AccountDataResetRequest
import saien.someday.server.auth.CredentialWorkUnavailableException
import saien.someday.server.auth.isValidAccountPassword
import saien.someday.server.persistence.AccountResetIdentity
import saien.someday.server.persistence.AccountResetPrecheck
import saien.someday.server.persistence.VerifiedPasswordAccount

fun Route.accountDataRoutes(context: ServerContext) {
    get("/account/data-state") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val auth = call.requireAuthenticated(context, requiredScope = "auth") ?: return@get
        if (!call.requireAuthenticationRateLimit(context, "account-control", auth.userId.toString())) return@get
        call.respond(context.accountDataRepository.discover(auth.requestContext))
    }
    get("/account/data-resets/{operationId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val auth = call.requireAuthenticated(context, requiredScope = "auth") ?: return@get
        if (!call.requireAuthenticationRateLimit(context, "account-control", auth.userId.toString())) return@get
        val operationId = call.parameters["operationId"]?.let(::canonicalAccountUuid)
        if (operationId == null || operationId.version() != 4 || operationId.variant() != 2) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request")
            return@get
        }
        val receipt = context.accountDataRepository.receipt(auth.requestContext, operationId)
        if (receipt == null) call.respondError(HttpStatusCode.NotFound, "not_found") else call.respond(receipt)
    }
    post("/account/data-resets") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val auth = call.requireAuthenticated(context, requiredScope = "auth") ?: return@post
        if (!call.requireAuthenticationRateLimit(context, "account-reset", auth.userId.toString())) return@post
        val body = call.receiveJsonOrNull<AccountDataResetRequest>(ACCOUNT_RESET_REQUEST_BYTES) ?: return@post
        val operationId = canonicalAccountUuid(body.operationId)
        val expectedIncarnation = canonicalAccountUuid(body.expectedIncarnation)
        if (operationId == null || operationId.version() != 4 || operationId.variant() != 2 ||
            expectedIncarnation == null || body.confirmation != ACCOUNT_RESET_CONFIRMATION
        ) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request")
            return@post
        }
        val identity = AccountResetIdentity(operationId, expectedIncarnation, body.protocolVersion)
        val preparation = context.accountDataRepository.prepareReset(auth.requestContext, identity)
        if (preparation is AccountResetPrecheck.Replay) {
            call.respond(preparation.receipt)
            return@post
        }
        preparation as AccountResetPrecheck.PasswordRequired
        // Exact replay precedes password work; new requests never retain this
        // transient password outside the call or inside repository diagnostics.
        if (!isValidAccountPassword(body.password)) {
            call.respondError(HttpStatusCode.Forbidden, "invalid_credentials")
            return@post
        }
        val passwordMatches = try {
            context.credentialHasher.verify(preparation.passwordHashSnapshot, body.password)
        } catch (_: CredentialWorkUnavailableException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "authentication_busy")
            return@post
        }
        if (!passwordMatches) {
            call.respondError(HttpStatusCode.Forbidden, "invalid_credentials")
            return@post
        }
        call.respond(
            context.accountDataRepository.reset(
                auth.requestContext,
                identity,
                VerifiedPasswordAccount(auth.userId, preparation.passwordHashSnapshot),
            ),
        )
    }
}

private const val ACCOUNT_RESET_REQUEST_BYTES = 4 * 1024
private const val ACCOUNT_RESET_CONFIRMATION = "reset-all-account-data-v1"
