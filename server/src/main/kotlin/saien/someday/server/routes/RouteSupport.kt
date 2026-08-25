package saien.someday.server.routes

import saien.someday.server.ServerContext
import saien.someday.server.api.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class AuthenticatedCall(
    val userId: UUID,
    val email: String,
    val isAdmin: Boolean,
    val sessionId: UUID,
    val deviceId: UUID?,
    val tokenDeviceId: UUID?,
    val scopes: Set<String>,
)

suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String) {
    respond(status, ErrorResponse(error))
}

suspend inline fun <reified T : Any> ApplicationCall.receiveJsonOrNull(
    maxEncodedBytes: Int = DEFAULT_JSON_REQUEST_BYTES,
): T? {
    require(maxEncodedBytes > 0)
    val body = try {
        receiveBoundedBody(maxEncodedBytes)
    } catch (_: RequestBodyTooLarge) {
        respondError(HttpStatusCode.PayloadTooLarge, "request_body_too_large")
        return null
    } catch (_: Exception) {
        respondError(HttpStatusCode.BadRequest, "invalid_request")
        return null
    }
    return try {
        val text = body.decodeToString(throwOnInvalidSequence = true)
        require(!StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey(text))
        STRICT_REQUEST_JSON.decodeFromString<T>(text)
    } catch (_: Exception) {
        respondError(HttpStatusCode.BadRequest, "invalid_request")
        null
    }
}

@PublishedApi
internal suspend fun ApplicationCall.receiveBoundedBody(maxEncodedBytes: Int): ByteArray {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength !in 0..maxEncodedBytes.toLong()) throw RequestBodyTooLarge()
    val channel = receiveChannel()
    val output = ByteArrayOutputStream(minOf(maxEncodedBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        if (output.size().toLong() + read > maxEncodedBytes) {
            channel.cancel(RequestBodyTooLarge())
            throw RequestBodyTooLarge()
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

@PublishedApi
internal class RequestBodyTooLarge : RuntimeException()

@PublishedApi
internal const val DEFAULT_JSON_REQUEST_BYTES: Int = 1024 * 1024

@PublishedApi
internal val STRICT_REQUEST_JSON = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
    coerceInputValues = false
}

/** kotlinx.serialization currently accepts duplicate names, so reject them before decoding. */
@PublishedApi
internal object StrictJsonDuplicateKeyDetector {
    fun hasDuplicateObjectKey(input: String): Boolean = try {
        Parser(input).parse()
        false
    } catch (_: DuplicateKey) {
        true
    }

    private class DuplicateKey : RuntimeException()

    private class Parser(private val input: String) {
        private var index = 0

        fun parse() {
            skipWhitespace()
            value()
            skipWhitespace()
            require(index == input.length)
        }

        private fun value() {
            skipWhitespace()
            require(index < input.length)
            when (input[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringToken()
                else -> primitive()
            }
        }

        private fun objectValue() {
            index++
            skipWhitespace()
            if (consume('}')) return
            val names = mutableSetOf<String>()
            while (true) {
                skipWhitespace()
                val encodedName = stringToken()
                val name = STRICT_REQUEST_JSON.decodeFromString<String>(encodedName)
                if (!names.add(name)) throw DuplicateKey()
                skipWhitespace()
                require(consume(':'))
                value()
                skipWhitespace()
                if (consume('}')) return
                require(consume(','))
            }
        }

        private fun arrayValue() {
            index++
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                value()
                skipWhitespace()
                if (consume(']')) return
                require(consume(','))
            }
        }

        private fun stringToken(): String {
            val start = index
            require(consume('"'))
            while (index < input.length) {
                when (input[index++]) {
                    '"' -> return input.substring(start, index)
                    '\\' -> {
                        require(index < input.length)
                        if (input[index++] == 'u') {
                            require(index + 4 <= input.length)
                            repeat(4) { require(input[index++].digitToIntOrNull(16) != null) }
                        }
                    }
                    else -> Unit
                }
            }
            error("Unterminated JSON string")
        }

        private fun primitive() {
            val start = index
            while (index < input.length && input[index] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) index++
            require(index > start)
        }

        private fun consume(character: Char): Boolean =
            if (index < input.length && input[index] == character) {
                index++
                true
            } else {
                false
            }

        private fun skipWhitespace() {
            while (index < input.length && input[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
        }
    }
}

suspend fun ApplicationCall.requireAuthenticated(
    context: ServerContext,
    requiredScope: String? = null,
    requireDevice: Boolean = false,
    tokenOverride: String? = null,
): AuthenticatedCall? {
    val token = tokenOverride
        ?: request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotBlank() }
        ?: return unauthorized()
    val claims = context.tokenService.verifyAccessToken(token) ?: return unauthorized()
    val snapshot = context.repository.findAuthSession(claims.userId, claims.sessionId) ?: return unauthorized()
    val now = Instant.now()
    if (
        snapshot.userDisabledAt != null ||
        snapshot.sessionRevokedAt != null ||
        snapshot.deviceRevokedAt != null ||
        !snapshot.sessionExpiresAt.isAfter(now)
    ) {
        return unauthorized()
    }
    if (claims.deviceId != null && snapshot.sessionDeviceId != claims.deviceId) {
        return unauthorized()
    }
    if (requiredScope != null && requiredScope !in claims.scopes) {
        respondError(HttpStatusCode.Forbidden, "forbidden")
        return null
    }
    if (requireDevice && claims.deviceId == null) {
        respondError(HttpStatusCode.Forbidden, "device_required")
        return null
    }
    return AuthenticatedCall(
        userId = snapshot.userId,
        email = snapshot.email,
        isAdmin = snapshot.isAdmin,
        sessionId = snapshot.sessionId,
        deviceId = claims.deviceId ?: snapshot.sessionDeviceId,
        tokenDeviceId = claims.deviceId,
        scopes = claims.scopes,
    )
}

fun ApplicationCall.clientRateLimitKey(context: ServerContext): String {
    val forwarded = if (context.config.trustProxyHeaders) {
        request.headers["X-Forwarded-For"]
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_CLIENT_ADDRESS_LENGTH }
    } else {
        null
    }
    val direct = request.origin.remoteHost
        .trim()
        .takeIf { it.isNotBlank() && it.length <= MAX_CLIENT_ADDRESS_LENGTH }
    return forwarded ?: direct ?: "unknown-client"
}

suspend fun ApplicationCall.requireRateLimit(context: ServerContext, key: String): Boolean {
    if (context.rateLimiter.allow(key)) {
        return true
    }
    respondError(HttpStatusCode.TooManyRequests, "rate_limited")
    return false
}

suspend fun ApplicationCall.requireAuthenticationRateLimit(
    context: ServerContext,
    operation: String,
    accountKey: String?,
): Boolean {
    val clientKey = clientRateLimitKey(context)
    if (!requireRateLimit(context, "auth:$operation:client:$clientKey")) return false
    if (accountKey != null && !requireRateLimit(context, "auth:$operation:account:$accountKey")) return false
    return true
}

private const val MAX_CLIENT_ADDRESS_LENGTH = 128

suspend fun ApplicationCall.requireSystemV3RateLimit(context: ServerContext, deviceId: UUID): Boolean {
    if (context.systemV3RateLimiter.allow("system-v3:$deviceId")) {
        return true
    }
    respondError(HttpStatusCode.TooManyRequests, "rate_limited")
    return false
}

private suspend fun ApplicationCall.unauthorized(): AuthenticatedCall? {
    respondError(HttpStatusCode.Unauthorized, "unauthorized")
    return null
}
