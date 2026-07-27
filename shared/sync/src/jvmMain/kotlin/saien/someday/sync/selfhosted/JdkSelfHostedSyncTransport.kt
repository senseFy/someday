package saien.someday.sync.selfhosted

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.io.InputStream
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.sync.StrictJsonV2

class JdkSelfHostedSyncTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
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

    override fun v2Capabilities(endpoint: String, accessToken: String): SelfHostedV2CapabilitiesResponse =
        get(endpoint, "/sync/v2/capabilities", accessToken, SelfHostedV2CapabilitiesResponse.serializer())

    override fun v2Epoch(endpoint: String, accessToken: String): SelfHostedV2EpochResponse =
        get(endpoint, "/sync/v2/epoch", accessToken, SelfHostedV2EpochResponse.serializer())

    override fun v2EpochHistory(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochHistoryRequest,
    ): SelfHostedV2EpochResponse = post(
        endpoint,
        "/sync/v2/epoch/history",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2EpochResponse.serializer(),
    )

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        "/sync/v2/checkpoint/chunk",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
    )

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse = post(
        endpoint,
        "/sync/v2/checkpoint/manifest",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2ImmutablePutResponse.serializer(),
    )

    override fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
    ): SelfHostedV2CheckpointFetchResponse = post(
        endpoint,
        "/sync/v2/checkpoint/fetch",
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
        "/sync/v2/epoch/compare-and-set",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2EpochCompareAndSetResponse.serializer(),
        acceptedStatuses = setOf(409),
    )

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
    ): SelfHostedV2PushResponse = post(
        endpoint,
        "/sync/v2/push",
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
        "/sync/v2/pull",
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
        "/sync/v2/frontiers",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2FrontierResponse.serializer(),
    )

    override fun v2RepairObject(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2RepairObjectRequest,
    ): SelfHostedV2RepairObjectResponse = post(
        endpoint,
        "/sync/v2/repair/object",
        accessToken,
        json.encodeToString(request),
        SelfHostedV2RepairObjectResponse.serializer(),
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
    ): T {
        require(encodedBody.encodeToByteArray().size <= MAX_ENCODED_BODY_BYTES) {
            "Self-hosted request exceeds the V2 encoded body limit."
        }
        val builder = HttpRequest.newBuilder(uri(endpoint, path))
            .timeout(Duration.ofSeconds(20))
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
            .timeout(Duration.ofSeconds(20))
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
            .timeout(Duration.ofSeconds(20))
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
            .timeout(Duration.ofSeconds(20))
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
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= MAX_ENCODED_BODY_BYTES) {
                "Self-hosted response exceeds the V2 encoded body limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private companion object {
        const val MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
    }
}
