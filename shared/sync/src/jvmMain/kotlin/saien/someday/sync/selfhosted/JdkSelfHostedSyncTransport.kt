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
    SelfHostedAccountControlTransport,
    AutoCloseable {
    init { require(client.followRedirects() == HttpClient.Redirect.NEVER) { "Self-hosted transport must not follow redirects." } }

    override fun close() {
        client.close()
    }

    override fun register(
        endpoint: String,
        request: SelfHostedAuthRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/register",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
            accountContext = accountContext,
        )

    override fun login(
        endpoint: String,
        request: SelfHostedAuthRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/login",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
            accountContext = accountContext,
        )

    override fun refresh(
        endpoint: String,
        request: SelfHostedRefreshRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedAuthTokensResponse =
        post(
            endpoint = endpoint,
            path = "/auth/refresh",
            bearerToken = null,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedAuthTokensResponse.serializer(),
            accountContext = accountContext,
        )

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedDeviceRegistrationResponse =
        post(
            endpoint = endpoint,
            path = "/devices/register",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedDeviceRegistrationResponse.serializer(),
            accountContext = accountContext,
        )

    override fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedPairingInviteCreateResponse =
        put(
            endpoint = endpoint,
            path = "/pairing/invites/${encodePathSegment(inviteId)}",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedPairingInviteCreateResponse.serializer(),
            accountContext = accountContext,
        )

    override fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedPairingInviteClaimResponse =
        post(
            endpoint = endpoint,
            path = "/pairing/invites/${encodePathSegment(inviteId)}/claim",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedPairingInviteClaimResponse.serializer(),
            accountContext = accountContext,
        )

    override fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
        accountContext: SelfHostedAccountRequestContext,
    ) = postNoContent(
        endpoint = endpoint,
        path = "/pairing/invites/${encodePathSegment(inviteId)}/complete",
        bearerToken = accessToken,
        encodedBody = json.encodeToString(request),
        accountContext = accountContext,
    )

    override fun cancelPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        accountContext: SelfHostedAccountRequestContext,
    ) = postNoContent(
        endpoint = endpoint,
        path = "/pairing/invites/${encodePathSegment(inviteId)}/cancel",
        bearerToken = accessToken,
        encodedBody = "{}",
        accountContext = accountContext,
    )

    override fun getWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse? =
        try {
            get(
                endpoint,
                "/workspace/recovery-envelope",
                accessToken,
                SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
                accountContext = accountContext,
            )
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.errorCode == SelfHostedErrorCode.NOT_FOUND || (!failure.protocol1 && !accountContext.protocol1Known && failure.status == 404)) null else throw failure
        }

    override fun putWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse = put(
        endpoint,
        "/workspace/recovery-envelope",
        accessToken,
        json.encodeToString(request),
        SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
        accountContext = accountContext,
    )

    override fun v2Capabilities(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedV2CapabilitiesResponse =
        systemV3Capabilities(endpoint, accessToken, accountContext = accountContext).toInternalEntityV2Capabilities()

    override fun systemV3Capabilities(
        endpoint: String,
        accessToken: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedSystemV3CapabilitiesResponse =
        get(endpoint, "/sync/v3/capabilities", accessToken, SelfHostedSystemV3CapabilitiesResponse.serializer(), accountContext = accountContext)

    override fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedV2EpochResponse =
        get(endpoint, jdkEntityPath(workspaceId, "/epoch"), accessToken, SelfHostedV2EpochResponse.serializer(), accountContext = accountContext)

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/chunk"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
        acceptedStatuses = setOf(409),
        accountContext = accountContext,
    )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/manifest"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
        acceptedStatuses = setOf(409),
        accountContext = accountContext,
    )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CheckpointFetchResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/fetch"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2CheckpointFetchResponse.serializer(),
        accountContext = accountContext,
    )

    override fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2EpochCompareAndSetResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/epoch/compare-and-set"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2EpochCompareAndSetResponse.serializer(),
        acceptedStatuses = setOf(409),
        accountContext = accountContext,
    )

    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CheckpointCleanupResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/checkpoint/cleanup"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2CheckpointCleanupResponse.serializer(),
        acceptedStatuses = setOf(409),
        accountContext = accountContext,
    )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2PushResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/push"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2PushResponse.serializer(),
        acceptedStatuses = setOf(409),
        accountContext = accountContext,
    )

    override fun v2Pull(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PullRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2PullResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/pull"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2PullResponse.serializer(),
        accountContext = accountContext,
    )

    override fun v2Frontiers(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2FrontierRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2FrontierResponse = post(
        endpoint,
        jdkEntityPath(request.workspaceId, "/frontiers"),
        accessToken,
        json.encodeToString(request),
        SelfHostedV2FrontierResponse.serializer(),
        accountContext = accountContext,
    )

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
        accountContext: SelfHostedAccountRequestContext,
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
            accountContext = accountContext,
        )
    }

    override fun headMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedMediaRemoteHeadV3? {
        requireSystemV3WorkspaceId(workspaceId)
        requireJdkMediaId(mediaId)
        return headMedia(endpoint, "/sync/v3/workspaces/$workspaceId/media/$mediaId", accessToken, accountContext = accountContext)
    }

    override fun getMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedMediaRemoteObjectV3 {
        requireSystemV3WorkspaceId(workspaceId)
        requireJdkMediaId(mediaId)
        return getMediaBytes(
            endpoint,
            "/sync/v3/workspaces/$workspaceId/media/$mediaId",
            accessToken,
            SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES,
            accountContext = accountContext,
        )
    }

    override fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountDiscoveryResult {
        val response = exchange("GET", endpoint, "/account/data-state", accessToken, null, accountContext, maxBody = SELF_HOSTED_ACCOUNT_BODY_LIMIT, sendIncarnation = false)
        if (SelfHostedAccountWire.legacyCandidate(response.status, response.errorHeaders, response.issuanceHeaders, response.body, response.contentType, accountContext)) {
            return SelfHostedAccountDiscoveryResult.LegacyCandidate404
        }
        return SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountWire.validateState(decode(response, SelfHostedAccountDataStateResponse.serializer(), accountContext, limit = SELF_HOSTED_ACCOUNT_BODY_LIMIT)))
    }

    override fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountMeResponse =
        SelfHostedAccountWire.validateMe(decode(exchange("GET", endpoint, "/me", accessToken, null, accountContext, maxBody = SELF_HOSTED_ACCOUNT_BODY_LIMIT, sendIncarnation = false), SelfHostedAccountMeResponse.serializer(), accountContext, limit = SELF_HOSTED_ACCOUNT_BODY_LIMIT))

    override fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse? {
        require(SelfHostedAccountWire.isOperationId(operationId))
        return try {
            SelfHostedAccountWire.validateReceipt(decode(exchange("GET", endpoint, "/account/data-resets/$operationId", accessToken, null, accountContext, maxBody = SELF_HOSTED_ACCOUNT_BODY_LIMIT, sendIncarnation = false), SelfHostedAccountResetReceiptResponse.serializer(), accountContext, limit = SELF_HOSTED_ACCOUNT_BODY_LIMIT), operationId)
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.errorCode == SelfHostedErrorCode.NOT_FOUND) null else throw failure
        }
    }

    override fun resetAccountData(endpoint: String, accessToken: String, request: SelfHostedAccountResetRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse {
        SelfHostedAccountWire.validateResetRequest(request)
        val encoded = json.encodeToString(request)
        require(encoded.encodeToByteArray().size <= SELF_HOSTED_ACCOUNT_BODY_LIMIT)
        val expected = SelfHostedAccountRequestContext(request.expectedIncarnation, protocol1Known = true)
        val receipt = SelfHostedAccountWire.validateReceipt(decode(exchange("POST", endpoint, "/account/data-resets", accessToken, encoded, expected, maxBody = SELF_HOSTED_ACCOUNT_BODY_LIMIT), SelfHostedAccountResetReceiptResponse.serializer(), expected, limit = SELF_HOSTED_ACCOUNT_BODY_LIMIT), request.operationId)
        if (receipt.previousIncarnation != request.expectedIncarnation) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.INVALID_CONTROL_RESPONSE)
        return receipt
    }

    private fun putMediaBytes(endpoint: String, path: String, accessToken: String, bytes: ByteArray, ciphertextSha256: String, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaPutResponseV3 {
        val request = builder(endpoint, path, accessToken, accountContext)
            .header("Content-Type", SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE)
            .header(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER, ciphertextSha256)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        return decode(readResponse(send(request), setOf(409)), SelfHostedMediaPutResponseV3.serializer(), accountContext, setOf(409))
    }

    private fun headMedia(endpoint: String, path: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaRemoteHeadV3? {
        val request = builder(endpoint, path, accessToken, accountContext).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        requireUnredirected(response, request)
        try {
            SelfHostedAccountWire.classify(response.statusCode(), response.headerValues(SELF_HOSTED_ERROR_CODE_HEADER), "", accountContext, head = true)
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.errorCode in setOf(SelfHostedErrorCode.MEDIA_OBJECT_NOT_FOUND, SelfHostedErrorCode.MEDIA_OBJECT_UNAVAILABLE) ||
                (!failure.protocol1 && !accountContext.protocol1Known && failure.status == 404)
            ) return null
            throw failure
        }
        return response.mediaHead()
    }

    private fun getMediaBytes(endpoint: String, path: String, accessToken: String, maxBytes: Int, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaRemoteObjectV3 {
        val response = send(builder(endpoint, path, accessToken, accountContext).GET().build())
        val errorHeaders = response.headerValues(SELF_HOSTED_ERROR_CODE_HEADER)
        if (errorHeaders != null || response.statusCode() !in 200..299) {
            val body = response.body().use { readBoundedText(it, SELF_HOSTED_ACCOUNT_BODY_LIMIT) }
            SelfHostedAccountWire.classify(response.statusCode(), errorHeaders, body, accountContext)
        }
        require(response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim() == SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE) {
            response.body().close(); "Self-hosted media response has invalid content type."
        }
        val declared = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        if (declared != null && declared !in 0..maxBytes.toLong()) {
            response.body().close(); SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
        }
        val bytes = response.body().use { readBoundedBytes(it, maxBytes) }
        if (declared != null && declared != bytes.size.toLong()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
        val head = response.mediaHead()
        require(head.ciphertextBytes == bytes.size)
        return SelfHostedMediaRemoteObjectV3(head.ciphertextBytes, head.ciphertextSha256, bytes)
    }

    private fun HttpResponse<*>.mediaHead(): SelfHostedMediaRemoteHeadV3 {
        val bytes = headers().firstValue(SYSTEM_V3_MEDIA_CIPHERTEXT_BYTES_HEADER).orElse(null)?.canonicalPositiveIntOrNullForJdk()
            ?: error("Self-hosted media response has invalid size metadata.")
        val digest = headers().firstValue(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER).orElse(null)?.takeIf(MEDIA_DIGEST::matches)
            ?: error("Self-hosted media response has invalid digest metadata.")
        return SelfHostedMediaRemoteHeadV3(bytes, digest)
    }

    private fun <T> post(endpoint: String, path: String, bearerToken: String?, encodedBody: String, responseSerializer: KSerializer<T>, acceptedStatuses: Set<Int> = emptySet(), accountContext: SelfHostedAccountRequestContext): T =
        decode(exchange("POST", endpoint, path, bearerToken, encodedBody, accountContext, acceptedStatuses), responseSerializer, accountContext, acceptedStatuses)

    private fun <T> put(endpoint: String, path: String, bearerToken: String?, encodedBody: String, responseSerializer: KSerializer<T>, acceptedStatuses: Set<Int> = emptySet(), accountContext: SelfHostedAccountRequestContext): T =
        decode(exchange("PUT", endpoint, path, bearerToken, encodedBody, accountContext, acceptedStatuses), responseSerializer, accountContext, acceptedStatuses)

    private fun <T> get(endpoint: String, path: String, bearerToken: String, responseSerializer: KSerializer<T>, accountContext: SelfHostedAccountRequestContext): T =
        decode(exchange("GET", endpoint, path, bearerToken, null, accountContext), responseSerializer, accountContext)

    private fun postNoContent(endpoint: String, path: String, bearerToken: String, encodedBody: String, accountContext: SelfHostedAccountRequestContext) {
        val response = exchange("POST", endpoint, path, bearerToken, encodedBody, accountContext)
        SelfHostedAccountWire.classify(response.status, response.errorHeaders, response.body, accountContext)
    }

    private fun exchange(method: String, endpoint: String, path: String, bearerToken: String?, body: String?, accountContext: SelfHostedAccountRequestContext, acceptedStatuses: Set<Int> = emptySet(), maxBody: Int = MAX_ENCODED_BODY_BYTES, sendIncarnation: Boolean = true): SelfHostedWireResponse {
        require(body == null || body.encodeToByteArray().size <= maxBody)
        val builder = builder(endpoint, path, bearerToken, accountContext, sendIncarnation)
        if (body != null) builder.header("Content-Type", "application/json")
        val request = builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build()
        return readResponse(send(request), acceptedStatuses, maxBody)
    }

    private fun builder(endpoint: String, path: String, bearerToken: String?, context: SelfHostedAccountRequestContext, sendIncarnation: Boolean = true): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri(endpoint, path)).timeout(Duration.ofMillis(SELF_HOSTED_REQUEST_TIMEOUT_MILLIS))
        bearerToken?.let {
            builder.header("Authorization", "Bearer $it")
            if (sendIncarnation) builder.header(SELF_HOSTED_ACCOUNT_PROTOCOL_HEADER, "1").header(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER, context.accountIncarnation)
        }
        return builder
    }

    private fun send(request: HttpRequest): HttpResponse<InputStream> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.uri() != request.uri()) {
            response.body().close(); SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.REDIRECTED_RESPONSE)
        }
        return response
    }

    private fun requireUnredirected(response: HttpResponse<*>, request: HttpRequest) {
        if (response.uri() != request.uri()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.REDIRECTED_RESPONSE)
    }

    private fun readResponse(response: HttpResponse<InputStream>, acceptedStatuses: Set<Int> = emptySet(), maxBody: Int = MAX_ENCODED_BODY_BYTES): SelfHostedWireResponse {
        val errorHeaders = response.headerValues(SELF_HOSTED_ERROR_CODE_HEADER)
        val limit = if (SelfHostedAccountWire.errorBodyLimit(response.statusCode(), errorHeaders, acceptedStatuses)) SELF_HOSTED_ACCOUNT_BODY_LIMIT else maxBody
        val body = response.body().use { input ->
            val declared = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
            if (declared != null && declared !in 0..limit.toLong()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
            val bytes = readBoundedBytes(input, limit)
            if (declared != null && declared != bytes.size.toLong()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
            decodeUtf8(bytes)
        }
        return SelfHostedWireResponse(response.statusCode(), body, errorHeaders, response.headerValues(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER), response.headers().firstValue("Content-Type").orElse(null))
    }

    private fun HttpResponse<*>.headerValues(name: String): List<String>? = headers().allValues(name).takeIf { it.isNotEmpty() }

    private fun <T> decode(response: SelfHostedWireResponse, serializer: KSerializer<T>, context: SelfHostedAccountRequestContext, acceptedStatuses: Set<Int> = emptySet(), limit: Int = MAX_ENCODED_BODY_BYTES): T {
        SelfHostedAccountWire.classify(response.status, response.errorHeaders, response.body, context, acceptedStatuses)
        return SelfHostedAccountWire.captureIssuance(SelfHostedAccountWire.decode(serializer, response.body, limit), response.issuanceHeaders, context)
    }

    private fun uri(endpoint: String, path: String): URI {
        require(isSecureSyncEndpoint(endpoint)) { "Self-hosted requires HTTPS unless the server is on this device's loopback interface." }
        return URI.create("${endpoint.trim().trimEnd('/')}$path")
    }

    private fun encodePathSegment(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.~:") append(ch)
            else append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }

    private fun readBoundedText(input: InputStream, limit: Int): String = decodeUtf8(readBoundedBytes(input, limit))

    private fun decodeUtf8(bytes: ByteArray): String = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) {
        SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBytes) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
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
