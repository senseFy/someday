package saien.someday.server.routes

import saien.someday.server.ServerContext
import saien.someday.server.api.SyncV2CapabilitiesResponse
import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointChunkRequest
import saien.someday.server.api.SyncV2CheckpointFetchRequest
import saien.someday.server.api.SyncV2CheckpointFetchResponse
import saien.someday.server.api.SyncV2CheckpointManifestRequest
import saien.someday.server.api.SyncV2CursorUnitResponse
import saien.someday.server.api.SyncV2EpochCompareAndSetRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetResponse
import saien.someday.server.api.SyncV2EpochHistoryRequest
import saien.someday.server.api.SyncV2EpochMetadata
import saien.someday.server.api.SyncV2EpochResponse
import saien.someday.server.api.SyncV2FrontierRequest
import saien.someday.server.api.SyncV2FrontierResponse
import saien.someday.server.api.SyncV2ImmutablePutResponse
import saien.someday.server.api.SyncV2MutationAckResponse
import saien.someday.server.api.SyncV2ObjectPayload
import saien.someday.server.api.SyncV2PullRequest
import saien.someday.server.api.SyncV2PullResponse
import saien.someday.server.api.SyncV2PushRequest
import saien.someday.server.api.SyncV2PushResponse
import saien.someday.server.api.SyncV2RepairObjectRequest
import saien.someday.server.api.SyncV2RepairObjectResponse
import saien.someday.server.api.SyncV2RepairReplicaRequest
import saien.someday.server.api.SyncV2StatusResponse
import saien.someday.server.api.SyncV2StreamFrontier
import saien.someday.server.persistence.SyncV2CheckpointChunkInput
import saien.someday.server.persistence.SyncV2CheckpointChunkRefRecord
import saien.someday.server.persistence.SyncV2CheckpointManifestInput
import saien.someday.server.persistence.SyncV2EpochMetadataRecord
import saien.someday.server.persistence.SyncV2EpochRecord
import saien.someday.server.persistence.SyncV2ImmutablePutRepositoryResult
import saien.someday.server.persistence.SyncV2ObjectInput
import saien.someday.server.persistence.SyncV2PointerPublishRepositoryResult
import saien.someday.server.persistence.SyncV2PushRepositoryResult
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.syncV2Routes(context: ServerContext) {
    route("/sync/v2") {
        get("/capabilities") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@get
            val deviceId = auth.tokenDeviceId ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireSyncV2RateLimit(context, deviceId)) return@get
            call.respond(SyncV2CapabilitiesResponse())
        }

        get("/epoch") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@get
            val deviceId = auth.tokenDeviceId ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireSyncV2RateLimit(context, deviceId)) return@get
            call.respond(context.syncV2Repository.loadEpoch(auth.userId).toResponse())
        }

        post("/epoch/history") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2EpochHistoryRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.epochId.isUuidV4()) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_epoch")
            val retained = context.syncV2Repository.loadRetainedEpoch(auth.userId, request.epochId)
                ?: return@post call.respondError(HttpStatusCode.NotFound, "epoch_not_retained")
            call.respond(retained.toResponse())
        }

        post("/checkpoint/chunk") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2CheckpointChunkRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.validFor(deviceId)) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_checkpoint_chunk")
            val result = context.syncV2Repository.putCheckpointChunk(
                auth.userId,
                SyncV2CheckpointChunkInput(
                    request.epochId,
                    request.checkpointId,
                    request.ref.toRecord(),
                    JSON.encodeToString(request.objectValue),
                ),
            )
            call.respond(result.toResponse())
        }

        post("/checkpoint/manifest") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2CheckpointManifestRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.validFor(deviceId)) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_checkpoint_manifest")
            val result = context.syncV2Repository.putCheckpointManifest(
                auth.userId,
                SyncV2CheckpointManifestInput(
                    request.epochId,
                    request.checkpointId,
                    request.checkpointDigest,
                    request.chunks.map(SyncV2CheckpointChunkRef::toRecord),
                    request.totalObjectCount,
                    JSON.encodeToString(request.objectValue),
                ),
            )
            call.respond(result.toResponse())
        }

        post("/checkpoint/fetch") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2CheckpointFetchRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.epochId.isUuidV4() || !request.checkpointId.isUuidV4() ||
                request.chunkIndex?.let { it !in 0 until MAX_CHECKPOINT_CHUNKS } == true
            ) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_checkpoint_identity")
            }
            val response = if (request.chunkIndex == null) {
                val manifest = context.syncV2Repository.loadCheckpointManifest(
                    auth.userId, request.epochId, request.checkpointId,
                ) ?: return@post call.respondError(HttpStatusCode.NotFound, "checkpoint_not_found")
                SyncV2CheckpointFetchResponse(manifest = JSON.decodeFromString(manifest))
            } else {
                val chunk = context.syncV2Repository.loadCheckpointChunk(
                    auth.userId, request.epochId, request.checkpointId, request.chunkIndex,
                ) ?: return@post call.respondError(HttpStatusCode.NotFound, "checkpoint_chunk_not_found")
                SyncV2CheckpointFetchResponse(chunk = JSON.decodeFromString(chunk))
            }
            call.respondV2JsonBounded(response)
        }

        post("/epoch/compare-and-set") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2EpochCompareAndSetRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.validFor(deviceId)) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_epoch_pointer")
            val result = context.syncV2Repository.compareAndSetEpoch(
                auth.userId,
                request.expectedCurrentDigest,
                request.metadata.toRecord(),
                JSON.encodeToString(request.pointer),
            )
            when (result) {
                is SyncV2PointerPublishRepositoryResult.Published -> call.respond(result.toResponse())
                is SyncV2PointerPublishRepositoryResult.CompareAndSetFailed,
                is SyncV2PointerPublishRepositoryResult.Rejected,
                -> call.respond(HttpStatusCode.Conflict, result.toResponse())
            }
        }

        post("/push") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2PushRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            val valid = request.epochId.isUuidV4() && request.writerProtocolVersion >= 2 &&
                request.objects.size in 1..MAX_V2_PUSH_OBJECTS &&
                request.objects.mapNotNull { it.mutationId }.distinct().size == request.objects.size &&
                request.objects.map { it.objectId }.distinct().size == request.objects.size &&
                request.objects.all { it.validForEntityPush(request.epochId, deviceId) }
            if (!valid) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_v2_push")
            val result = context.syncV2Repository.push(
                auth.userId,
                deviceId,
                request.epochId,
                request.writerProtocolVersion,
                request.objects.map { it.toInput() },
            )
            when (result) {
                is SyncV2PushRepositoryResult.Accepted -> call.respond(
                    SyncV2PushResponse(
                        true,
                        result.acknowledgements.map {
                            SyncV2MutationAckResponse(it.mutationId, it.objectId, it.objectDigest, it.idempotentReplay)
                        },
                    ),
                )
                is SyncV2PushRepositoryResult.Rejected -> call.respond(
                    HttpStatusCode.Conflict,
                    SyncV2PushResponse(false, error = result.error),
                )
            }
        }

        post("/pull") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2PullRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            val after = request.afterCursor ?: 0L
            if (!request.epochId.isUuidV4() || after < 0 || request.limit !in 1..MAX_V2_PULL_UNITS) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_cursor")
            }
            val result = context.syncV2Repository.pull(auth.userId, request.epochId, after, request.limit)
            var previous = request.afterCursor?.toString()
            val units = result.changes.map { change ->
                val outer = JSON.decodeFromString<SyncV2ObjectPayload>(change.encodedObjectJson)
                SyncV2CursorUnitResponse(
                    request.epochId,
                    "global",
                    previous,
                    change.cursor.toString(),
                    "change:${change.cursor}",
                    "self-hosted:${change.cursor}:${outer.objectDigest}",
                    listOf(outer),
                ).also { previous = change.cursor.toString() }
            }
            context.repository.touchDevice(deviceId)
            val response = largestBoundedPullResponse(
                units = units,
                repositoryComplete = result.complete,
                rebootstrapRequired = result.rebootstrapRequired,
                error = result.error,
            ) ?: return@post call.respondError(HttpStatusCode.InternalServerError, "v2_object_exceeds_body_limit")
            call.respondV2JsonBounded(response)
        }

        post("/frontiers") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2FrontierRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.epochId.isUuidV4()) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_epoch")
            val frontier = context.syncV2Repository.frontier(auth.userId, request.epochId)
                ?: return@post call.respondError(HttpStatusCode.NotFound, "epoch_not_retained")
            call.respond(
                SyncV2FrontierResponse(
                    listOf(SyncV2StreamFrontier("global", frontier.cursor.takeIf { it > 0 }?.toString(), frontier.streamDigest)),
                ),
            )
        }

        post("/repair/object") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2RepairObjectRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.epochId.isUuidV4() || !request.objectId.isUuidV4() || !OBJECT_DIGEST.matches(request.expectedObjectDigest)) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_repair_identity")
            }
            val replicas = context.syncV2Repository.fetchReplicas(
                auth.userId, request.epochId, request.objectId, request.expectedObjectDigest,
            ).map { JSON.decodeFromString<SyncV2ObjectPayload>(it) }
            call.respond(SyncV2RepairObjectResponse(replicas))
        }

        post("/repair/replica") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@post
            val deviceId = auth.tokenDeviceId ?: return@post call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requestWithinV2Bound() || !call.requireSyncV2RateLimit(context, deviceId)) return@post
            val request = call.receiveJsonOrNull<SyncV2RepairReplicaRequest>(MAX_ENCODED_BODY_BYTES.toInt()) ?: return@post
            if (!request.objectValue.validForEntityPush(request.objectValue.syncEpochId, deviceId)) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_repair_replica")
            }
            call.respond(
                context.syncV2Repository.publishRepairReplica(
                    auth.userId, deviceId, request.objectValue.toInput(),
                ).toResponse(),
            )
        }

        get("/status") {
            val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@get
            val deviceId = auth.tokenDeviceId ?: return@get call.respondError(HttpStatusCode.Forbidden, "device_required")
            if (!call.requireSyncV2RateLimit(context, deviceId)) return@get
            val status = context.syncV2Repository.status(auth.userId)
            call.respond(
                SyncV2StatusResponse(
                    if (status.activeEpochId == null) "uninitialized" else "ready",
                    status.activeEpochId,
                    status.cursor,
                    status.immutableObjects,
                    deviceId.toString(),
                ),
            )
        }
    }
}

private fun SyncV2CheckpointChunkRequest.validFor(deviceId: UUID): Boolean =
    epochId.isUuidV4() && checkpointId.isUuidV4() && ref.valid() &&
        objectValue.validOuter() && objectValue.syncEpochId == epochId &&
        objectValue.objectType == "sync_checkpoint_chunk_v2" &&
        objectValue.objectId == ref.chunkId && objectValue.objectDigest == ref.chunkDigest &&
        objectValue.mutationId == null && objectValue.writerDeviceId == deviceId.toString()

private fun SyncV2CheckpointManifestRequest.validFor(deviceId: UUID): Boolean =
    epochId.isUuidV4() && checkpointId.isUuidV4() && chunks.isNotEmpty() &&
        chunks.size <= MAX_CHECKPOINT_CHUNKS && chunks.indices.all { chunks[it].chunkIndex == it && chunks[it].valid() } &&
        chunks.sumOf { it.objectCount } == totalObjectCount && totalObjectCount > 0 &&
        objectValue.validOuter() && objectValue.syncEpochId == epochId &&
        objectValue.objectType == "sync_checkpoint_manifest_v2" && objectValue.objectId == checkpointId &&
        objectValue.objectDigest == checkpointDigest && objectValue.mutationId == null &&
        objectValue.writerDeviceId == deviceId.toString()

private fun SyncV2EpochCompareAndSetRequest.validFor(deviceId: UUID): Boolean =
    metadata.contractId == CONTRACT && metadata.schemaSetVersion == SCHEMA_SET &&
        metadata.epochId.isUuidV4() && metadata.pointerDigest == pointer.objectDigest &&
        metadata.semanticProtocolVersion == 2 && metadata.minimumWriterProtocolVersion >= 2 &&
        metadata.keySetVersion == KEY_SET && metadata.remoteProfile == "self-hosted-v2" &&
        metadata.metadataPrivacyMode == "opaque" &&
        metadata.supportedOfflineWindowSeconds == OFFLINE_WINDOW_SECONDS &&
        metadata.checkpointId.isUuidV4() && CONTROL_DIGEST.matches(metadata.checkpointDigest) &&
        metadata.previousEpochId?.isUuidV4() != false &&
        metadata.previousEpochPointerDigest?.let(CONTROL_DIGEST::matches) != false &&
        ((metadata.previousEpochId == null) == (metadata.previousEpochPointerDigest == null)) &&
        expectedCurrentDigest?.let(CONTROL_DIGEST::matches) != false &&
        pointer.validOuter() && pointer.syncEpochId == metadata.epochId &&
        pointer.objectType == "sync_epoch_pointer_v2" && pointer.objectId == "epoch-pointer" &&
        pointer.mutationId == null && pointer.writerDeviceId == deviceId.toString()

private fun SyncV2ObjectPayload.validForEntityPush(epochId: String, deviceId: UUID): Boolean =
    validOuter() && syncEpochId == epochId && objectType == "workspace_entity_version_v2" &&
        objectId.isUuidV4() && mutationId?.isUuidV4() == true && OBJECT_DIGEST.matches(objectDigest) &&
        writerDeviceId == deviceId.toString() && decodedCiphertextSize() <= MAX_ENTITY_CIPHERTEXT_BYTES

private fun SyncV2ObjectPayload.validOuter(): Boolean {
    if (outerSchemaVersion != 1 || contractId != CONTRACT || schemaSetVersion != SCHEMA_SET ||
        keySetVersion != KEY_SET || cipherSuite != CIPHER_SUITE || !syncEpochId.isUuidV4() ||
        !writerDeviceId.isUuidV4() || objectId.length !in 1..128 ||
        objectType !in ALLOWED_OBJECT_TYPES || !CIPHERTEXT_DIGEST.matches(ciphertextDigest)
    ) return false
    val nonce = nonceBase64.decodeCanonicalBase64() ?: return false
    val ciphertext = ciphertextBase64.decodeCanonicalBase64() ?: return false
    if (nonce.size != 24 || ciphertext.isEmpty()) return false
    val digest = ciphertextDigest(nonce, ciphertext)
    if (digest != ciphertextDigest) return false
    val expectedDigest = if (objectType == "workspace_entity_version_v2") OBJECT_DIGEST else CONTROL_DIGEST
    if (!expectedDigest.matches(objectDigest)) return false
    return if (objectType == "workspace_entity_version_v2") mutationId?.isUuidV4() == true else mutationId == null
}

private fun SyncV2ObjectPayload.decodedCiphertextSize(): Int =
    ciphertextBase64.decodeCanonicalBase64()?.size ?: Int.MAX_VALUE

private fun SyncV2CheckpointChunkRef.valid(): Boolean =
    chunkIndex >= 0 && chunkId.isUuidV4() && CONTROL_DIGEST.matches(chunkDigest) &&
        objectCount in 1..64 && plaintextBytes in 1..MAX_CHECKPOINT_PLAINTEXT_BYTES

private fun SyncV2ObjectPayload.toInput(): SyncV2ObjectInput = SyncV2ObjectInput(
    syncEpochId,
    objectId,
    objectType,
    objectDigest,
    requireNotNull(mutationId),
    UUID.fromString(writerDeviceId),
    ciphertextDigest,
    JSON.encodeToString(this),
)

private fun SyncV2CheckpointChunkRef.toRecord() = SyncV2CheckpointChunkRefRecord(
    chunkIndex, chunkId, chunkDigest, objectCount, plaintextBytes,
)

private fun SyncV2EpochMetadata.toRecord() = SyncV2EpochMetadataRecord(
    contractId,
    schemaSetVersion,
    epochId,
    pointerDigest,
    semanticProtocolVersion,
    minimumWriterProtocolVersion,
    keySetVersion,
    remoteProfile,
    metadataPrivacyMode,
    supportedOfflineWindowSeconds,
    checkpointId,
    checkpointDigest,
    previousEpochId,
    previousEpochPointerDigest,
)

private fun SyncV2EpochMetadataRecord.toResponse() = SyncV2EpochMetadata(
    contractId,
    schemaSetVersion,
    epochId,
    pointerDigest,
    semanticProtocolVersion,
    minimumWriterProtocolVersion,
    keySetVersion,
    remoteProfile,
    metadataPrivacyMode,
    supportedOfflineWindowSeconds,
    checkpointId,
    checkpointDigest,
    previousEpochId,
    previousEpochPointerDigest,
)

private fun SyncV2EpochRecord?.toResponse(): SyncV2EpochResponse = when (this) {
    null -> SyncV2EpochResponse()
    else -> SyncV2EpochResponse(metadata.toResponse(), JSON.decodeFromString(pointerObjectJson))
}

private fun SyncV2ImmutablePutRepositoryResult.toResponse() = when (this) {
    is SyncV2ImmutablePutRepositoryResult.Stored -> SyncV2ImmutablePutResponse(true, idempotentReplay)
    is SyncV2ImmutablePutRepositoryResult.Rejected -> SyncV2ImmutablePutResponse(false, error = error)
}

private fun SyncV2PointerPublishRepositoryResult.toResponse() = when (this) {
    is SyncV2PointerPublishRepositoryResult.Published -> SyncV2EpochCompareAndSetResponse(true, idempotentReplay)
    is SyncV2PointerPublishRepositoryResult.CompareAndSetFailed -> SyncV2EpochCompareAndSetResponse(
        false, current = current.toResponse(), error = "epoch_pointer_compare_and_set_failed",
    )
    is SyncV2PointerPublishRepositoryResult.Rejected -> SyncV2EpochCompareAndSetResponse(false, error = error)
}

private suspend fun ApplicationCall.requestWithinV2Bound(): Boolean {
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (length == null || length in 1..MAX_ENCODED_BODY_BYTES) return true
    respondError(HttpStatusCode.PayloadTooLarge, "v2_body_too_large")
    return false
}

private fun largestBoundedPullResponse(
    units: List<SyncV2CursorUnitResponse>,
    repositoryComplete: Boolean,
    rebootstrapRequired: Boolean,
    error: String?,
): SyncV2PullResponse? {
    fun candidate(count: Int) = SyncV2PullResponse(
        units = units.take(count),
        complete = repositoryComplete && count == units.size,
        rebootstrapRequired = rebootstrapRequired,
        error = error,
    )

    var low = 0
    var high = units.size
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (JSON.encodeToString(candidate(middle)).encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    if (units.isNotEmpty() && low == 0) return null
    return candidate(low)
}

private suspend inline fun <reified T> ApplicationCall.respondV2JsonBounded(value: T) {
    val encoded = JSON.encodeToString(value)
    check(encoded.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
        "V2 response exceeded the negotiated encoded body limit."
    }
    respondText(encoded, ContentType.Application.Json)
}

private fun String.isUuidV4(): Boolean = UUID_V4.matches(this)

private fun String.decodeCanonicalBase64(): ByteArray? = try {
    Base64.getDecoder().decode(this).takeIf { Base64.getEncoder().encodeToString(it) == this }
} catch (_: IllegalArgumentException) {
    null
}

private fun ciphertextDigest(nonce: ByteArray, ciphertext: ByteArray): String {
    val output = ByteArrayOutputStream()
    output.write(0xa3)
    output.write(cborText("nonce"))
    output.write(cborBytes(nonce))
    output.write(cborText("domain"))
    output.write(cborText("someday-system-v2-ciphertext-digest-v2"))
    output.write(cborText("ciphertext"))
    output.write(cborBytes(ciphertext))
    return "ct2:sha256:${MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).hex()}"
}

private fun cborText(value: String): ByteArray = value.encodeToByteArray().let { cborLength(3, it.size) + it }
private fun cborBytes(value: ByteArray): ByteArray = cborLength(2, value.size) + value
private fun cborLength(major: Int, size: Int): ByteArray = when {
    size < 24 -> byteArrayOf(((major shl 5) or size).toByte())
    size <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), size.toByte())
    size <= 0xffff -> byteArrayOf(((major shl 5) or 25).toByte(), (size ushr 8).toByte(), size.toByte())
    else -> byteArrayOf(
        ((major shl 5) or 26).toByte(),
        (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
    )
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private val JSON = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val OBJECT_DIGEST = Regex("^od2:hmac-sha256:[0-9a-f]{64}$")
private val CONTROL_DIGEST = Regex("^cd2:hmac-sha256:[0-9a-f]{64}$")
private val CIPHERTEXT_DIGEST = Regex("^ct2:sha256:[0-9a-f]{64}$")
private val ALLOWED_OBJECT_TYPES = setOf(
    "workspace_entity_version_v2",
    "sync_epoch_pointer_v2",
    "sync_checkpoint_manifest_v2",
    "sync_checkpoint_chunk_v2",
    "webdav_writer_manifest_v2",
    "webdav_log_segment_v2",
)
private const val CONTRACT = "someday-system-v2"
private const val SCHEMA_SET = "workspace-entity-schema-set-v2"
private const val KEY_SET = "sync-key-set-v2"
private const val CIPHER_SUITE = "xchacha20-poly1305-ietf"
private const val OFFLINE_WINDOW_SECONDS = 15_552_000L
private const val MAX_V2_PUSH_OBJECTS = 100
private const val MAX_V2_PULL_UNITS = 500
private const val MAX_ENTITY_CIPHERTEXT_BYTES = 1_048_592
private const val MAX_CHECKPOINT_PLAINTEXT_BYTES = 8 * 1024 * 1024
private const val MAX_CHECKPOINT_CHUNKS = 1_000_000
private const val MAX_ENCODED_BODY_BYTES = 16L * 1024L * 1024L
