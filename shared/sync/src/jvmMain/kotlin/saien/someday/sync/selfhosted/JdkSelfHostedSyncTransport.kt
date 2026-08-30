package saien.someday.sync.selfhosted

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.sync.StrictJsonV2

class JdkSelfHostedSyncTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(SELF_HOSTED_CONNECT_TIMEOUT_MILLIS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    },
) : SelfHostedSyncTransport,
    SelfHostedWorkspaceRecoveryTransport,
    SelfHostedSyncTransportV2,
    SelfHostedMediaTransportV3,
    AutoCloseable {
    override fun close() {
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
                endpoint,
                "/workspace/recovery-envelope",
                accessToken,
                SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
            )
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.status == 404) null else throw failure
        }

    override fun putWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse = put(
        endpoint,
        "/workspace/recovery-envelope",
        accessToken,
        json.encodeToString(request),
        SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
    )

    override fun v2Capabilities(endpoint: String, accessToken: String): SelfHostedV2CapabilitiesResponse =
        systemV3Capabilities(endpoint, accessToken).toInternalEntityV2Capabilities()

    override fun systemV3Capabilities(
        endpoint: String,
        accessToken: String,
    ): SelfHostedSystemV3CapabilitiesResponse =
        get(endpoint, "/sync/v3/capabilities", accessToken, SelfHostedSystemV3CapabilitiesResponse.serializer())

    override fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String): SelfHostedV2EpochResponse =
        get(endpoint, jdkEntityPath(workspaceId, "/epoch"), accessToken, SelfHostedV2EpochResponse.serializer())

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/chunk"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/manifest"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
    ): SelfHostedV2CheckpointFetchResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/fetch"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2CheckpointFetchResponse.serializer(),
    )

    override fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
    ): SelfHostedV2EpochCompareAndSetResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/epoch/compare-and-set"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2EpochCompareAndSetResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
    ): SelfHostedV2CheckpointCleanupResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/cleanup"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2CheckpointCleanupResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
    ): SelfHostedV2PushResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/push"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2PushResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2Pull(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PullRequest,
    ): SelfHostedV2PullResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/pull"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2PullResponse.serializer(),
    )

    override fun v2Frontiers(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2FrontierRequest,
    ): SelfHostedV2FrontierResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/frontiers"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2FrontierResponse.serializer(),
    )

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
    ): SelfHostedMediaPutResponseV3 {
        requireSystemV3WorkspaceId(workspaceId)
        requireJdkMediaId(mediaId)
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
        requireJdkMediaId(mediaId)
        return headMedia(endpoint, "/sync/v3/workspaces/$workspaceId/media/$mediaId", accessToken)
    }

    override fun getMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
    ): SelfHostedMediaRemoteObjectV3 {
        requireSystemV3WorkspaceId(workspaceId)
        requireJdkMediaId(mediaId)
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
    ): SelfHostedMediaPutResponseV3 {
        val builder = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Content-Type", SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE)
            .header("Authorization", "Bearer $accessToken")
            .header(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER, ciphertextSha256)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        return execute(
            builder.build(),
            SelfHostedMediaPutResponseV3.serializer(),
            acceptedStatuses = setOf(409),
        )
    }

    private fun headMedia(
        endpoint: String,
        path: String,
        accessToken: String,
    ): SelfHostedMediaRemoteHeadV3? {
        val request = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Authorization", "Bearer $accessToken")
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 404) return null
        requireJdkSuccessful(response.statusCode())
        return response.mediaHead()
    }

    private fun getMediaBytes(
        endpoint: String,
        path: String,
        accessToken: String,
        maxBytes: Int,
    ): SelfHostedMediaRemoteObjectV3 {
        val request = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().use { readBoundedBytes(it, MEDIA_ERROR_BODY_LIMIT) }
            requireJdkSuccessful(response.statusCode())
        }
        require(response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim() ==
            SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE)
        val declared = response.headers().firstValueAsLong("Content-Length")
        require(declared.isEmpty || declared.asLong in 0..maxBytes.toLong()) {
            response.body().close()
            "Self-hosted media response exceeds its configured body limit."
        }
        val bytes = response.body().use { readBoundedBytes(it, maxBytes) }
        require(declared.isEmpty || declared.asLong == bytes.size.toLong())
        val head = response.mediaHead()
        require(head.ciphertextBytes == bytes.size)
        return SelfHostedMediaRemoteObjectV3(
            head.ciphertextBytes,
            head.ciphertextSha256,
            bytes,
        )
    }

    private fun HttpResponse<*>.mediaHead(): SelfHostedMediaRemoteHeadV3 {
        val ciphertextBytes = headers().firstValue(SYSTEM_V3_MEDIA_CIPHERTEXT_BYTES_HEADER).orElse(null)
            ?.canonicalPositiveIntOrNullForJdk()
            ?: error("Self-hosted media response has invalid size metadata.")
        val ciphertextSha256 = headers().firstValue(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER).orElse(null)
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
    ): T {
        require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
            "Self-hosted request exceeds the V2 encoded body limit."
        }
        val builder = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodedBody))
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build(), responseSerializer, acceptedStatuses)
    }

    private fun <T> put(
        endpoint: String,
        path: String,
        bearerToken: String?,
        encodedBody: String,
        responseSerializer: KSerializer<T>,
        acceptedStatuses: Set<Int> = emptySet(),
    ): T {
        require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
            "Self-hosted request exceeds the V2 encoded body limit."
        }
        val builder = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(encodedBody))
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build(), responseSerializer, acceptedStatuses)
    }

    private fun postNoContent(
        endpoint: String,
        path: String,
        bearerToken: String,
        encodedBody: String,
    ) {
        require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES)
        val request = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .POST(HttpRequest.BodyPublishers.ofString(encodedBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw SelfHostedSyncHttpException(
                status = response.statusCode(),
                safeMessage = "Self-hosted request failed with HTTP ${response.statusCode()}; credentials redacted.",
            )
        }
    }

    private fun <T> get(
        endpoint: String,
        path: String,
        bearerToken: String,
        responseSerializer: KSerializer<T>,
    ): T {
        val request = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
            .header("Authorization", "Bearer $bearerToken")
            .GET()
            .build()
        return execute(request, responseSerializer)
    }

    private fun <T> execute(
        request: HttpRequest,
        responseSerializer: KSerializer<T>,
        acceptedStatuses: Set<Int> = emptySet(),
    ): T {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val declaredLength = response.headers().firstValueAsLong("Content-Length")
        require(declaredLength.isEmpty || declaredLength.asLong in 0..MAX_ENCODED_BODY_BYTES.toLong()) {
            response.body().close()
            "Self-hosted response exceeds the V2 encoded body limit."
        }
        val body = response.body().use(::readBoundedBody)
        if (response.statusCode() !in 200..299 && response.statusCode() !in acceptedStatuses) {
            throw SelfHostedSyncHttpException(
                status = response.statusCode(),
                safeMessage = "Self-hosted request failed with HTTP ${response.statusCode()}; credentials redacted.",
            )
        }
        StrictJsonV2.requireValidObjectKeys(body, MAX_ENCODED_BODY_BYTES)
        return json.decodeFromString(responseSerializer, body)
    }

    private fun uri(endpoint: String, path: String): URI {
        require(isSecureSyncEndpoint(endpoint)) {
            "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
        }
        return URI.create("${endpoint.trim().trimEnd('/')}$path")
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

    private fun readBoundedBody(input: InputStream): String {
        return readBoundedBytes(input, MAX_ENCODED_BODY_BYTES).decodeToString(throwOnInvalidSequence = true)
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= maxBytes) {
                "Self-hosted response exceeds its configured body limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun requireJdkSuccessful(status: Int) {
        if (status !in 200..299) {
            throw SelfHostedSyncHttpException(
                status = status,
                safeMessage = "Self-hosted request failed with HTTP $status; credentials redacted.",
            )
        }
    }

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
        const val MEDIA_ERROR_BODY_LIMIT: Int = 64 * 1024
        val MEDIA_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}

private fun jdkEntityPath(workspaceId: String, suffix: String): String {
    require(JDK_TRANSPORT_WORKSPACE_ID.matches(workspaceId)) { "Invalid workspace scope." }
    return "/sync/v3/workspaces/$workspaceId/entities$suffix"
}

private val JDK_TRANSPORT_WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")

private fun requireJdkMediaId(mediaId: String) {
    MediaAssetId.fromCanonicalValue(mediaId)
}

private fun String.canonicalPositiveIntOrNullForJdk(): Int? =
    toIntOrNull()?.takeIf { it > 0 && it.toString() == this }

private fun String.canonicalPositiveLongOrNullForJdk(): Long? =
    toLongOrNull()?.takeIf { it > 0L && it.toString() == this }
