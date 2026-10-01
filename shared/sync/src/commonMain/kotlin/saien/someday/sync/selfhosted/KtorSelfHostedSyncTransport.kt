package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.request.request
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
    client: HttpClient = HttpClient {
        configureSelfHostedHttpClient()
    },
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    },
) : SelfHostedSyncTransport,
    SelfHostedWorkspaceAdmissionTransport,
    SelfHostedWorkspaceRecoveryTransport,
    SelfHostedSyncTransportV2,
    SelfHostedMediaTransportV3,
    SelfHostedAccountControlTransport {
    private val sourceClient = client
    private val client = client.config { followRedirects = false; expectSuccess = false }

    fun close() {
        client.close()
        sourceClient.close()
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
                endpoint = endpoint,
                path = "/workspace/recovery-envelope",
                bearerToken = accessToken,
                responseSerializer = SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
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
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse =
        put(
            endpoint = endpoint,
            path = "/workspace/recovery-envelope",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedWorkspaceRecoveryEnvelopeResponse.serializer(),
            accountContext = accountContext,
        )

    override fun v2Capabilities(
        endpoint: String,
        accessToken: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CapabilitiesResponse = systemV3Capabilities(endpoint, accessToken, accountContext = accountContext).toInternalEntityV2Capabilities()

    override fun systemV3Capabilities(
        endpoint: String,
        accessToken: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedSystemV3CapabilitiesResponse =
        get(
            endpoint = endpoint,
            path = "/sync/v3/capabilities",
            bearerToken = accessToken,
            responseSerializer = SelfHostedSystemV3CapabilitiesResponse.serializer(),
            accountContext = accountContext,
        )

    override fun v2Epoch(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2EpochResponse =
        get(
            endpoint = endpoint,
            path = entityPath(workspaceId, "/epoch"),
            bearerToken = accessToken,
            responseSerializer = SelfHostedV2EpochResponse.serializer(),
            accountContext = accountContext,
        )

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/chunk"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
            acceptedStatuses = setOf(409),
            accountContext = accountContext,
        )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/manifest"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
            acceptedStatuses = setOf(409),
            accountContext = accountContext,
        )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CheckpointFetchResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/fetch"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2CheckpointFetchResponse.serializer(),
            accountContext = accountContext,
        )

    override fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2EpochCompareAndSetResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/epoch/compare-and-set"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2EpochCompareAndSetResponse.serializer(),
            acceptedStatuses = setOf(409),
            accountContext = accountContext,
        )

    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CheckpointCleanupResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/checkpoint/cleanup"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2CheckpointCleanupResponse.serializer(),
            acceptedStatuses = setOf(409),
            accountContext = accountContext,
        )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2PushResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/push"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2PushResponse.serializer(),
            acceptedStatuses = setOf(409),
            accountContext = accountContext,
        )

    override fun v2Pull(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PullRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2PullResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/pull"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2PullResponse.serializer(),
            accountContext = accountContext,
        )

    override fun v2Frontiers(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2FrontierRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2FrontierResponse =
        post(
            endpoint = endpoint,
            path = entityPath(request.workspaceId, "/frontiers"),
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2FrontierResponse.serializer(),
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
        requireMediaId(mediaId)
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
        requireMediaId(mediaId)
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
        requireMediaId(mediaId)
        return getMediaBytes(
            endpoint,
            "/sync/v3/workspaces/$workspaceId/media/$mediaId",
            accessToken,
            SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES,
            accountContext = accountContext,
        )
    }

    override fun workspaceAdmission(endpoint: String, accessToken: String, workspaceId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedWorkspaceAdmissionResponse? {
        requireSystemV3WorkspaceId(workspaceId)
        val response = exchange("GET", endpoint, "/workspace/admission?workspaceId=$workspaceId", accessToken, null, accountContext, maxBody = SELF_HOSTED_ACCOUNT_BODY_LIMIT)
        if (response.status == 404 && response.errorHeaders == null) return null
        return try {
            decode(response, SelfHostedWorkspaceAdmissionResponse.serializer(), accountContext, limit = SELF_HOSTED_ACCOUNT_BODY_LIMIT).validated()
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.errorCode == SelfHostedErrorCode.NOT_FOUND) null else throw failure
        }
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

    private fun putMediaBytes(endpoint: String, path: String, accessToken: String, bytes: ByteArray, ciphertextSha256: String, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaPutResponseV3 = runBlocking {
        requireSecureEndpoint(endpoint)
        val target = "${endpoint.trim().trimEnd('/')}$path"
        val response = client.put(target) {
            contentType(ContentType.parse(SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE))
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            accountHeaders(accountContext)
            header(SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER, ciphertextSha256)
            setBody(bytes)
        }
        requireUnredirected(response, target)
        decode(readResponse(response, setOf(409)), SelfHostedMediaPutResponseV3.serializer(), accountContext, setOf(409))
    }

    private fun headMedia(endpoint: String, path: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaRemoteHeadV3? = runBlocking {
        requireSecureEndpoint(endpoint)
        val target = "${endpoint.trim().trimEnd('/')}$path"
        val response = client.head(target) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            accountHeaders(accountContext)
        }
        requireUnredirected(response, target)
        response.bodyAsChannel().cancel(null)
        try {
            SelfHostedAccountWire.classify(response.status.value, response.headers.getAll(SELF_HOSTED_ERROR_CODE_HEADER), "", accountContext, head = true)
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.errorCode in setOf(SelfHostedErrorCode.MEDIA_OBJECT_NOT_FOUND, SelfHostedErrorCode.MEDIA_OBJECT_UNAVAILABLE) ||
                (!failure.protocol1 && !accountContext.protocol1Known && failure.status == 404)
            ) return@runBlocking null
            throw failure
        }
        response.mediaHead()
    }

    private fun getMediaBytes(endpoint: String, path: String, accessToken: String, maxBytes: Int, accountContext: SelfHostedAccountRequestContext): SelfHostedMediaRemoteObjectV3 = runBlocking {
        requireSecureEndpoint(endpoint)
        val target = "${endpoint.trim().trimEnd('/')}$path"
        val response = client.get(target) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            accountHeaders(accountContext)
        }
        requireUnredirected(response, target)
        val errorHeaders = response.headers.getAll(SELF_HOSTED_ERROR_CODE_HEADER)
        if (errorHeaders != null || response.status.value !in 200..299) {
            val body = boundedText(response, SELF_HOSTED_ACCOUNT_BODY_LIMIT)
            SelfHostedAccountWire.classify(response.status.value, errorHeaders, body, accountContext)
        }
        require(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim() == SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE)
        val bytes = boundedBytes(response, maxBytes)
        val head = response.mediaHead()
        require(head.ciphertextBytes == bytes.size)
        SelfHostedMediaRemoteObjectV3(head.ciphertextBytes, head.ciphertextSha256, bytes)
    }

    private fun HttpResponse.mediaHead(): SelfHostedMediaRemoteHeadV3 {
        val bytes = headers[SYSTEM_V3_MEDIA_CIPHERTEXT_BYTES_HEADER]?.canonicalPositiveIntOrNull()
            ?: error("Self-hosted media response has invalid size metadata.")
        val digest = headers[SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER]?.takeIf(MEDIA_DIGEST::matches)
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

    private fun exchange(method: String, endpoint: String, path: String, bearerToken: String?, body: String?, accountContext: SelfHostedAccountRequestContext, acceptedStatuses: Set<Int> = emptySet(), maxBody: Int = MAX_ENCODED_BODY_BYTES, sendIncarnation: Boolean = true): SelfHostedWireResponse = runBlocking {
        requireSecureEndpoint(endpoint)
        require(body == null || body.encodeToByteArray().size <= maxBody)
        val target = "${endpoint.trim().trimEnd('/')}$path"
        val response = client.request(target) {
            this.method = io.ktor.http.HttpMethod.parse(method)
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (bearerToken != null && sendIncarnation) accountHeaders(accountContext)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        requireUnredirected(response, target)
        readResponse(response, acceptedStatuses, maxBody)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.accountHeaders(context: SelfHostedAccountRequestContext) {
        header(SELF_HOSTED_ACCOUNT_PROTOCOL_HEADER, "1")
        header(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER, context.accountIncarnation)
    }

    private suspend fun readResponse(response: HttpResponse, acceptedStatuses: Set<Int> = emptySet(), maxBody: Int = MAX_ENCODED_BODY_BYTES): SelfHostedWireResponse {
        val errorHeaders = response.headers.getAll(SELF_HOSTED_ERROR_CODE_HEADER)
        val limit = if (SelfHostedAccountWire.errorBodyLimit(response.status.value, errorHeaders, acceptedStatuses)) SELF_HOSTED_ACCOUNT_BODY_LIMIT else maxBody
        return SelfHostedWireResponse(response.status.value, boundedText(response, limit), errorHeaders, response.headers.getAll(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER), response.headers[HttpHeaders.ContentType])
    }

    private fun <T> decode(response: SelfHostedWireResponse, serializer: KSerializer<T>, context: SelfHostedAccountRequestContext, acceptedStatuses: Set<Int> = emptySet(), limit: Int = MAX_ENCODED_BODY_BYTES): T {
        SelfHostedAccountWire.classify(response.status, response.errorHeaders, response.body, context, acceptedStatuses)
        return SelfHostedAccountWire.captureIssuance(SelfHostedAccountWire.decode(serializer, response.body, limit), response.issuanceHeaders, context)
    }

    private suspend fun requireUnredirected(response: HttpResponse, target: String) {
        if (response.call.request.url != io.ktor.http.Url(target)) {
            response.bodyAsChannel().cancel(null)
            SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.REDIRECTED_RESPONSE)
        }
    }

    private suspend fun boundedText(response: HttpResponse, limit: Int): String {
        val bytes = boundedBytes(response, limit)
        return try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) {
            SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
        }
    }

    private suspend fun boundedBytes(response: HttpResponse, maxBytes: Int): ByteArray {
        val channel = response.bodyAsChannel()
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        try {
            if (declared != null && declared !in 0..maxBytes.toLong()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                if (total + read > maxBytes) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.BODY_TOO_LARGE)
                chunks += buffer.copyOf(read); total += read
            }
            if (declared != null && declared != total.toLong()) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
            val bytes = ByteArray(total)
            var offset = 0
            chunks.forEach { it.copyInto(bytes, offset); offset += it.size }
            return bytes
        } catch (failure: Throwable) { channel.cancel(failure); throw failure }
    }

    private fun encodePathSegment(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.~:") append(ch)
            else append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }

    private fun requireSecureEndpoint(endpoint: String) {
        require(isSecureSyncEndpoint(endpoint)) { "Self-hosted requires HTTPS unless the server is on this device's loopback interface." }
    }

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
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
