package saien.someday.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import saien.someday.server.ServerContext
import saien.someday.server.api.SystemV3CapabilitiesResponse
import saien.someday.server.api.SystemV3MediaPutResponse
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES
import saien.someday.server.persistence.SystemV3MediaObjectRecord
import saien.someday.server.persistence.SystemV3MediaPutResult
import saien.someday.server.persistence.SystemV3MediaReadResult
import java.security.MessageDigest

fun Route.systemV3Routes(context: ServerContext) {
    route("/sync/v3") {
        get("/capabilities") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@get
            val deviceId = auth.tokenDeviceId ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireSystemV3RateLimit(context, deviceId)) return@get
            call.respond(SystemV3CapabilitiesResponse())
        }
        route("/workspaces/{workspaceId}") {
            route("/entities") {
                systemV3EntityDagRouteBody(context)
            }
            route("/media/{mediaId}") {
                put {
                    val auth = call.requireAuthenticated(context, "sync", requireDevice = true) ?: return@put
                    val deviceId = auth.tokenDeviceId
                        ?: return@put call.respondError(HttpStatusCode.Forbidden, "device_required")
                    if (!call.requireSystemV3RateLimit(context, deviceId)) return@put
                    val workspaceId = call.workspaceIdOrNull()
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_workspace_id")
                    val mediaId = call.mediaIdOrNull()
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_media_id")
                    if (!call.hasExactContentType(MEDIA_OBJECT_CONTENT_TYPE)) {
                        return@put call.respondError(HttpStatusCode.UnsupportedMediaType, "invalid_media_content_type")
                    }
                    val digest = call.request.headers[MEDIA_CIPHERTEXT_SHA256_HEADER]
                        ?.takeIf(MEDIA_SHA256::matches)
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_media_digest")
                    val bytes = try {
                        call.receiveBoundedBody(MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES)
                    } catch (_: RequestBodyTooLarge) {
                        return@put call.respondError(HttpStatusCode.PayloadTooLarge, "media_object_too_large")
                    }
                    if (bytes.size !in MIN_MEDIA_CIPHERTEXT_BYTES..MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES ||
                        sha256(bytes) != digest
                    ) return@put call.respondError(HttpStatusCode.BadRequest, "invalid_media_object")
                    call.respondMediaPut(
                        context.systemV3MediaRepository.putObject(
                            auth.userId,
                            workspaceId,
                            deviceId,
                            mediaId,
                            digest,
                            bytes,
                        ),
                    )
                }

                head {
                    val auth = call.requireAuthenticated(context, "sync", requireDevice = true) ?: return@head
                    val deviceId = auth.tokenDeviceId
                        ?: return@head call.respondError(HttpStatusCode.Forbidden, "device_required")
                    if (!call.requireSystemV3RateLimit(context, deviceId)) return@head
                    val workspaceId = call.workspaceIdOrNull()
                        ?: return@head call.respondError(HttpStatusCode.BadRequest, "invalid_workspace_id")
                    val mediaId = call.mediaIdOrNull()
                        ?: return@head call.respondError(HttpStatusCode.BadRequest, "invalid_media_id")
                    when (val result = context.systemV3MediaRepository.headObject(auth.userId, workspaceId, mediaId)) {
                        is SystemV3MediaReadResult.Found -> call.respondMediaHead(result.value)
                        SystemV3MediaReadResult.Missing -> call.respondError(HttpStatusCode.NotFound, "media_object_not_found")
                        SystemV3MediaReadResult.Corrupt -> call.respondError(HttpStatusCode.NotFound, "media_object_unavailable")
                    }
                }

                get {
                    val auth = call.requireAuthenticated(context, "sync", requireDevice = true) ?: return@get
                    val deviceId = auth.tokenDeviceId
                        ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
                    if (!call.requireSystemV3RateLimit(context, deviceId)) return@get
                    val workspaceId = call.workspaceIdOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_workspace_id")
                    val mediaId = call.mediaIdOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_media_id")
                    when (val result = context.systemV3MediaRepository.readObject(auth.userId, workspaceId, mediaId)) {
                        is SystemV3MediaReadResult.Found -> {
                            call.mediaHeaders(result.value.record)
                            call.respondBytes(
                                result.value.bytes,
                                ContentType.parse(MEDIA_OBJECT_CONTENT_TYPE),
                                HttpStatusCode.OK,
                            )
                        }
                        SystemV3MediaReadResult.Missing -> call.respondError(HttpStatusCode.NotFound, "media_object_not_found")
                        SystemV3MediaReadResult.Corrupt -> call.respondError(HttpStatusCode.NotFound, "media_object_unavailable")
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondMediaPut(result: SystemV3MediaPutResult) {
    when (result) {
        is SystemV3MediaPutResult.Stored -> respond(
            if (result.idempotentReplay) HttpStatusCode.OK else HttpStatusCode.Created,
            SystemV3MediaPutResponse(true, result.idempotentReplay),
        )
        is SystemV3MediaPutResult.Rejected -> respond(
            HttpStatusCode.Conflict,
            SystemV3MediaPutResponse(false, error = result.error),
        )
    }
}

private suspend fun ApplicationCall.respondMediaHead(record: SystemV3MediaObjectRecord) {
    mediaHeaders(record)
    respond(HttpStatusCode.OK)
}

private fun ApplicationCall.mediaHeaders(record: SystemV3MediaObjectRecord) {
    response.header(MEDIA_CIPHERTEXT_SHA256_HEADER, record.ciphertextSha256)
    response.header(MEDIA_CIPHERTEXT_BYTES_HEADER, record.ciphertextBytes.toString())
    response.header(HttpHeaders.ETag, "\"${record.ciphertextSha256}\"")
}

private fun ApplicationCall.workspaceIdOrNull(): String? =
    parameters["workspaceId"]?.takeIf(WORKSPACE_ID::matches)

private fun ApplicationCall.mediaIdOrNull(): String? = parameters["mediaId"]?.takeIf(MEDIA_ID::matches)

private fun ApplicationCall.hasExactContentType(expected: String): Boolean =
    request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.equals(expected, true) == true

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

private val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
private val MEDIA_ID = Regex("^[0-9a-f]{64}$")
private val MEDIA_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
private const val MEDIA_OBJECT_CONTENT_TYPE = "application/vnd.someday.media-object.v1"
private const val MEDIA_CIPHERTEXT_SHA256_HEADER = "X-Someday-Media-Ciphertext-Sha256"
private const val MEDIA_CIPHERTEXT_BYTES_HEADER = "X-Someday-Media-Ciphertext-Bytes"
private const val MIN_MEDIA_CIPHERTEXT_BYTES = 45
