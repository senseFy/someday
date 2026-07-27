package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.sync.StrictJsonV2
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readAvailable


class KtorSelfHostedSyncTransport(
    private val client: HttpClient = HttpClient {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
    },
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    },
) : SelfHostedSyncTransport, SelfHostedSyncTransportV2 {
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

    override fun v2Capabilities(
        endpoint: String,
        accessToken: String,
    ): SelfHostedV2CapabilitiesResponse =
        get(
            endpoint = endpoint,
            path = "/sync/v2/capabilities",
            bearerToken = accessToken,
            responseSerializer = SelfHostedV2CapabilitiesResponse.serializer(),
        )

    override fun v2Epoch(
        endpoint: String,
        accessToken: String,
    ): SelfHostedV2EpochResponse =
        get(
            endpoint = endpoint,
            path = "/sync/v2/epoch",
            bearerToken = accessToken,
            responseSerializer = SelfHostedV2EpochResponse.serializer(),
        )

    override fun v2EpochHistory(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochHistoryRequest,
    ): SelfHostedV2EpochResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/epoch/history",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2EpochResponse.serializer(),
        )

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/checkpoint/chunk",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
        )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/checkpoint/manifest",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
        )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
    ): SelfHostedV2CheckpointFetchResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/checkpoint/fetch",
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
            path = "/sync/v2/epoch/compare-and-set",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2EpochCompareAndSetResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
    ): SelfHostedV2PushResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/push",
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
            path = "/sync/v2/pull",
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
            path = "/sync/v2/frontiers",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2FrontierResponse.serializer(),
        )

    override fun v2RepairObject(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2RepairObjectRequest,
    ): SelfHostedV2RepairObjectResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/repair/object",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2RepairObjectResponse.serializer(),
        )

    override fun v2PublishRepairReplica(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2RepairReplicaRequest,
    ): SelfHostedV2ImmutablePutResponse =
        post(
            endpoint = endpoint,
            path = "/sync/v2/repair/replica",
            bearerToken = accessToken,
            encodedBody = json.encodeToString(request),
            responseSerializer = SelfHostedV2ImmutablePutResponse.serializer(),
            acceptedStatuses = setOf(409),
        )

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
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(declared == null || declared in 0..MAX_ENCODED_BODY_BYTES.toLong()) {
            "Self-hosted response exceeds the V2 encoded body limit."
        }
        val channel = response.bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            require(total + read <= MAX_ENCODED_BODY_BYTES) {
                "Self-hosted response exceeds the V2 encoded body limit."
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
        return bytes.decodeToString(throwOnInvalidSequence = true)
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

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
    }
}
