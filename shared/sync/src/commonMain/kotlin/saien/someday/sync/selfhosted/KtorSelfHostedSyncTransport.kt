package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.sync.StrictJsonV2

class KtorSelfHostedSyncTransport(
    private val client: HttpClient = HttpClient {
        configureSelfHostedHttpClient()
    },
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    },
) : SelfHostedSyncTransport,
    SelfHostedWorkspaceRecoveryTransport,
    SelfHostedSyncTransportV2,
    SelfHostedMediaTransportV3 {
    fun close() {
        client.close()
    }

    override fun register(
        endpoint: String,
        request: SelfHostedAuthRequest,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/register",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
        )

    override fun login(
        endpoint: String,
        request: SelfHostedAuthRequest,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/login",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
        )

    override fun refresh(
        endpoint: String,
        request: SelfHostedRefreshRequest,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/refresh",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
        )

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
    ): SelfHostedDeviceRegistrationResponse =
        post(
            endpoint = endpoint,
            path = "/devices/register",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedDeviceRegistrationResponse.serializer(),
        )

    override fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
    ): SelfHostedPairingInviteCreateResponse =
        put(
            endpoint = endpoint,
            path = "/pairing/invites/${encodePathSegment(inviteId)}",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedPairingInviteCreateResponse.serializer(),
        )

    override fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
    ): SelfHostedPairingInviteClaimResponse =
        post(
            endpoint = endpoint,
            path = "/pairing/invites/${encodePathSegment(inviteId)}/claim",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedPairingInviteClaimResponse.serializer(),
        )

    override fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
    ) = postNoContent(
        endpoint = endpoint,
        path = "/pairing/invites/${encodePathSegment(inviteId)}/complete",
        bearerToken = accessToken,
        encodedBody = json.encodeToString(request),
    )

    override fun cancelPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
    ) = postNoContent(
        endpoint = endpoint,
        path = "/pairing/invites/${encodePathSegment(inviteId)}/cancel",
        bearerToken = accessToken,
        encodedBody = "{}",
    )

    override fun getWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse? =
        try {
            get(
                endpoint = endpoint,
                path = "/workspace/recovery-envelope",
                bearerToken = accessToken,
                responseSerializer = SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
            )
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.status == 404) null else throw failure
        }

    override fun putWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse =
        put(
            endpoint = endpoint,
            path = "/workspace/recovery-envelope",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
        )

    override fun v2Capabilities(
        endpoint: String,
        accessToken: String,
    ): SelfHostedV2CapabilitiesResponse = systemV3Capabilities(endpoint, accessToken).toInternalEntityV2Capabilities()

    override fun systemV3Capabilities(
        endpoint: String,
        accessToken: String,
    ): SelfHostedSystemV3CapabilitiesResponse =
        get(
            endpoint = endpoint,
            path = "/sync/v3/capabilities",
            bearerToken = accessToken,
            responseSerializer = SelfHostedSystemV3CapabilitiesResponse.serializer(),
        )

    override fun v2Epoch(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
    ): SelfHostedV2EpochResponse =
        get(
            endpoint = endpoint,
            path = entityPath(workspaceId, "/epoch"),
            bearerToken = accessToken,
            responseSerializer = SelfHostedV2EpochResponse.serializer(),
        )

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/chunk"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/manifest"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
    ): SelfHostedV2CheckpointFetchResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/fetch"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2CheckpointFetchResponse.serializer(),
        )

    override fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
    ): SelfHostedV2EpochCompareAndSetResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/epoch/compare-and-set"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2EpochCompareAndSetResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
    ): SelfHostedV2CheckpointCleanupResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/cleanup"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2CheckpointCleanupResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
    ): SelfHostedV2PushResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/push"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2PushResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2Pull(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PullRequest,
    ): SelfHostedV2PullResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/pull"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2PullResponse.serializer(),
        )

    override fun v2Frontiers(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2FrontierRequest,
    ): SelfHostedV2FrontierResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/frontiers"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2FrontierResponse.serializer(),
        )

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
    ): SelfHostedMediaPutResponseV3 {
        requireSystemV3WorkspaceId(workspaceId)
        requireMediaId(mediaId)
        require(prepared.metadata.mediaId == mediaId)
        return putMediaBytes(
            endpoint,
            "/sync/v3/workspaces/$workspaceId/media/$mediaId",
            accessToken,
            prepared.encryptedBytes,
            prepared.encryptedSha256,
        )
    }

    override fun headMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
    ): SelfHostedMediaRemoteHeadV3? {
        requireSystemV3WorkspaceId(workspaceId)
        requireMediaId(mediaId)
        return headMedia(endpoint, "/sync/v3/workspaces/$workspaceId/media/$mediaId", accessToken)
    }

    override fun getMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
    ): SelfHostedMediaRemoteObjectV3 {
        requireSystemV3WorkspaceId(workspaceId)
        requireMediaId(mediaId)
        return getMediaBytes(
            endpoint,
            "/sync/v3/workspaces/$workspaceId/media/$mediaId",
            accessToken,
            SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES,
        )
    }

    private fun putMediaBytes(
        endpoint: String,
        path: String,
        accessToken: String,
        bytes: ByteArray,
        ciphertextSha256: String,
    ): SelfHostedMediaPutResponseV3 = runBlocking {
        requireSecureEndpoint(endpoint)
        val response = client.put("${endpoint.trim().trimEnd('/')}$path") {
            contentType(ContentType.parse(SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE))
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER, ciphertextSha256)
            setBody(bytes)
        }
        decode(
            response.status.value,
            boundedBody(response),
            SelfHostedMediaPutResponseV3.serializer(),
            acceptedStatuses = setOf(409),
        )
    }

    private fun headMedia(
        endpoint: String,
        path: String,
        accessToken: String,
    ): SelfHostedMediaRemoteHeadV3? = runBlocking {
        requireSecureEndpoint(endpoint)
        val response = client.head("${endpoint.trim().trimEnd('/')}$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        boundedBytes(response, MEDIA_ERROR_BODY_LIMIT, enforceDeclaredLength = false)
        if (response.status.value == 404) return@runBlocking null
        if (response.status.value !in 200..299) requireSuccessful(response.status.value)
        response.mediaHead()
    }

    private fun getMediaBytes(
        endpoint: String,
        path: String,
        accessToken: String,
        maxBytes: Int,
    ): SelfHostedMediaRemoteObjectV3 = runBlocking {
        requireSecureEndpoint(endpoint)
        val response = client.get("${endpoint.trim().trimEnd('/')}$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (response.status.value !in 200..299) {
            boundedBytes(response, MEDIA_ERROR_BODY_LIMIT)
            requireSuccessful(response.status.value)
        }
        require(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim() ==
            SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE)
        val bytes = boundedBytes(response, maxBytes)
        val head = response.mediaHead()
        require(head.ciphertextBytes == bytes.size)
        SelfHostedMediaRemoteObjectV3(
            head.ciphertextBytes,
            head.ciphertextSha256,
            bytes,
        )
    }

    private fun HttpResponse.mediaHead(): SelfHostedMediaRemoteHeadV3 {
        val ciphertextBytes = headers[SYSTEM_V3_MEDIA_CIPHERTEXT_BYTES_HEADER]
            ?.canonicalPositiveIntOrNull()
            ?: error("Self-hosted media response has invalid size metadata.")
        val ciphertextSha256 = headers[SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER]
            ?.takeIf(MEDIA_DIGEST::matches)
            ?: error("Self-hosted media response has invalid digest metadata.")
        return SelfHostedMediaRemoteHeadV3(ciphertextBytes, ciphertextSha256)
    }

    private fun <T> post(
        endpoint: String,
        path: String,
        bearerToken: String?,
        encodedBody: String,
        responseSerializer: KSerializer<T>,
        acceptedStatuses: Set<Int> = emptySet(),
    ): T =
        runBlocking {
            require(isSecureSyncEndpoint(endpoint)) {
                "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
            }
            require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
                "Self-hosted request exceeds the V2 encoded body limit."
            }
            val response = client.post("${endpoint.trim().trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(encodedBody)
            }
            decode(response.status.value, boundedBody(response), responseSerializer, acceptedStatuses)
        }

    private fun <T> put(
        endpoint: String,
        path: String,
        bearerToken: String?,
        encodedBody: String,
        responseSerializer: KSerializer<T>,
        acceptedStatuses: Set<Int> = emptySet(),
    ): T =
        runBlocking {
            require(isSecureSyncEndpoint(endpoint)) {
                "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
            }
            require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
                "Self-hosted request exceeds the V2 encoded body limit."
            }
            val response = client.put("${endpoint.trim().trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(encodedBody)
            }
            decode(response.status.value, boundedBody(response), responseSerializer, acceptedStatuses)
        }

    private fun postNoContent(
        endpoint: String,
        path: String,
        bearerToken: String,
        encodedBody: String,
    ) {
        runBlocking {
            require(isSecureSyncEndpoint(endpoint)) {
                "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
            }
            require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES)
            val response = client.post("${endpoint.trim().trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
                setBody(encodedBody)
            }
            if (response.status.value !in 200..299) {
                boundedBody(response)
                throw SelfHostedSyncHttpException(
                    status = response.status.value,
                    safeMessage = "Self-hosted request failed with HTTP ${response.status.value}; credentials redacted.",
                )
            }
            boundedBody(response)
        }
    }

    private fun <T> get(
        endpoint: String,
        path: String,
        bearerToken: String,
        responseSerializer: KSerializer<T>,
    ): T =
        runBlocking {
            require(isSecureSyncEndpoint(endpoint)) {
                "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
            }
            val response = client.get("${endpoint.trim().trimEnd('/')}$path") {
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }
            decode(response.status.value, boundedBody(response), responseSerializer)
        }

    private suspend fun boundedBody(response: HttpResponse): String {
        return boundedBytes(response, MAX_ENCODED_BODY_BYTES).decodeToString(throwOnInvalidSequence = true)
    }

    private suspend fun boundedBytes(
        response: HttpResponse,
        maxBytes: Int,
        enforceDeclaredLength: Boolean = true,
    ): ByteArray {
        val channel = response.bodyAsChannel()
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        return try {
            require(declared == null || declared in 0..maxBytes.toLong()) {
                "Self-hosted response exceeds its configured body limit."
            }
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                require(total + read <= maxBytes) {
                    "Self-hosted response exceeds its configured body limit."
                }
                chunks += buffer.copyOf(read)
                total += read
            }
            val bytes = ByteArray(total)
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            require(!enforceDeclaredLength || declared == null || declared == bytes.size.toLong()) {
                "Self-hosted response body length does not match Content-Length."
            }
            bytes
        } catch (failure: Throwable) {
            channel.cancel(failure)
            throw failure
        }
    }

    private fun <T> decode(
        status: Int,
        body: String,
        responseSerializer: KSerializer<T>,
        acceptedStatuses: Set<Int> = emptySet(),
    ): T {
        if (status !in 200..299 && status !in acceptedStatuses) {
            throw SelfHostedSyncHttpException(
                status = status,
                safeMessage = "Self-hosted request failed with HTTP $status; credentials redacted.",
            )
        }
        StrictJsonV2.requireValidObjectKeys(body, MAX_ENCODED_BODY_BYTES)
        return json.decodeFromString(responseSerializer, body)
    }

    private fun encodePathSegment(value: String): String =
        buildString(value.length + 8) {
            value.forEach { ch ->
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == ':' -> append(ch)
                    else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

    private fun requireSecureEndpoint(endpoint: String) {
        require(isSecureSyncEndpoint(endpoint)) {
            "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
        }
    }

    private fun requireSuccessful(status: Int): Nothing {
        throw SelfHostedSyncHttpException(
            status = status,
            safeMessage = "Self-hosted request failed with HTTP $status; credentials redacted.",
        )
    }

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
        const val MEDIA_ERROR_BODY_LIMIT: Int = 64 * 1024
        val MEDIA_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}

private fun entityPath(workspaceId: String, suffix: String): String {
    require(TRANSPORT_WORKSPACE_ID.matches(workspaceId)) { "Invalid workspace scope." }
    return "/sync/v3/workspaces/$workspaceId/entities$suffix"
}

private val TRANSPORT_WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")

private fun requireMediaId(mediaId: String) {
    MediaAssetId.fromCanonicalValue(mediaId)
}

private fun String.canonicalPositiveIntOrNull(): Int? =
    toIntOrNull()?.takeIf { it > 0 && it.toString() == this }

private fun String.canonicalPositiveLongOrNull(): Long? =
    toLongOrNull()?.takeIf { it > 0L && it.toString() == this }
