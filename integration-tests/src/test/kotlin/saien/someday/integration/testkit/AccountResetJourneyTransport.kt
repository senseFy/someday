package saien.someday.integration.testkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import kotlinx.serialization.json.Json
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.KtorSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedAccountControlTransport
import saien.someday.sync.selfhosted.SelfHostedAccountRequestContext
import saien.someday.sync.selfhosted.SelfHostedAccountResetReceiptResponse
import saien.someday.sync.selfhosted.SelfHostedAccountResetRequest
import saien.someday.sync.selfhosted.SelfHostedDeviceRegistrationRequest
import saien.someday.sync.selfhosted.SelfHostedDeviceRegistrationResponse
import saien.someday.sync.selfhosted.SelfHostedMediaPutResponseV3
import saien.someday.sync.selfhosted.SelfHostedMediaTransportV3
import saien.someday.sync.selfhosted.SelfHostedPreparedMediaObjectV3
import saien.someday.sync.selfhosted.SelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointChunkRequest
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointCleanupRequest
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointCleanupResponse
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointManifestRequest
import saien.someday.sync.selfhosted.SelfHostedV2EpochCompareAndSetRequest
import saien.someday.sync.selfhosted.SelfHostedV2EpochCompareAndSetResponse
import saien.someday.sync.selfhosted.SelfHostedV2ImmutablePutResponse
import saien.someday.sync.selfhosted.SelfHostedV2PushRequest
import saien.someday.sync.selfhosted.SelfHostedV2PushResponse
import saien.someday.sync.selfhosted.SelfHostedWorkspaceRecoveryTransport
import saien.someday.sync.selfhosted.accountRequestContext

/** Every response comes from real HTTP. Faults discard an already received response. */
internal class AccountResetJourneyTransport private constructor(
    private val delegate: SelfHostedSyncTransport,
) : SelfHostedSyncTransport by delegate,
    SelfHostedSyncTransportV2 by (delegate as SelfHostedSyncTransportV2),
    SelfHostedMediaTransportV3 by (delegate as SelfHostedMediaTransportV3),
    SelfHostedWorkspaceRecoveryTransport by (delegate as SelfHostedWorkspaceRecoveryTransport),
    SelfHostedAccountControlTransport by (delegate as SelfHostedAccountControlTransport),
    AutoCloseable {
    private val entity = delegate as SelfHostedSyncTransportV2
    private val control = delegate as SelfHostedAccountControlTransport
    var registrations = 0
        private set
    var publicationRequests = 0
        private set
    var loseNextResetResponse = false
    var loseNextReceiptResponse = false
    val resetOperations = mutableListOf<String>()
    val receivedReceipts = mutableListOf<SelfHostedAccountResetReceiptResponse>()
    private var firstChunk: SelfHostedV2CheckpointChunkRequest? = null
    private var firstPush: SelfHostedV2PushRequest? = null

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedDeviceRegistrationResponse {
        registrations++
        return delegate.registerDevice(endpoint, accessToken, request, accountContext)
    }

    override fun resetAccountData(
        endpoint: String,
        accessToken: String,
        request: SelfHostedAccountResetRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedAccountResetReceiptResponse {
        resetOperations += request.operationId
        val receipt = control.resetAccountData(endpoint, accessToken, request, accountContext)
        receivedReceipts += receipt
        if (loseNextResetResponse) {
            loseNextResetResponse = false
            throw IOException("Synthetic loss after a real reset HTTP response; response redacted.")
        }
        return receipt
    }

    override fun getAccountResetReceipt(
        endpoint: String,
        accessToken: String,
        operationId: String,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedAccountResetReceiptResponse? {
        val receipt = control.getAccountResetReceipt(endpoint, accessToken, operationId, accountContext)
        if (loseNextReceiptResponse) {
            check(receipt != null) { "Fault injection requires a real committed receipt." }
            loseNextReceiptResponse = false
            throw IOException("Synthetic loss after a real receipt HTTP response; response redacted.")
        }
        return receipt
    }

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse {
        publicationRequests++
        val result = entity.v2PutCheckpointChunk(endpoint, accessToken, request, accountContext)
        if (firstChunk == null) firstChunk = request
        return result
    }

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2ImmutablePutResponse {
        publicationRequests++
        return entity.v2PutCheckpointManifest(endpoint, accessToken, request, accountContext)
    }

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2PushResponse {
        publicationRequests++
        val result = entity.v2Push(endpoint, accessToken, request, accountContext)
        if (firstPush == null && request.objects.isNotEmpty()) firstPush = request
        return result
    }

    override fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2EpochCompareAndSetResponse {
        publicationRequests++
        return entity.v2CompareAndSetEpoch(endpoint, accessToken, request, accountContext)
    }

    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedV2CheckpointCleanupResponse {
        publicationRequests++
        return entity.v2CleanupCheckpointDraft(endpoint, accessToken, request, accountContext)
    }

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
        accountContext: SelfHostedAccountRequestContext,
    ): SelfHostedMediaPutResponseV3 {
        publicationRequests++
        return (delegate as SelfHostedMediaTransportV3).putMediaObject(
            endpoint, accessToken, workspaceId, mediaId, prepared, accountContext,
        )
    }

    /** Reuses actual previously accepted ciphertext and its original workspace. */
    fun replayOldChunk(credentials: SelfHostedSessionCredentials) = entity.v2PutCheckpointChunk(
        credentials.endpoint, credentials.accessToken, checkNotNull(firstChunk), credentials.accountRequestContext(),
    )

    fun replayOldPush(credentials: SelfHostedSessionCredentials) = entity.v2Push(
        credentials.endpoint, credentials.accessToken, checkNotNull(firstPush), credentials.accountRequestContext(),
    )

    fun encodedOldPush(): String = Json.encodeToString(SelfHostedV2PushRequest.serializer(), checkNotNull(firstPush))

    override fun close() {
        (delegate as? JdkSelfHostedSyncTransport)?.close()
        (delegate as? KtorSelfHostedSyncTransport)?.close()
    }

    companion object {
        fun jdk() = AccountResetJourneyTransport(JdkSelfHostedSyncTransport())
        fun ktor() = AccountResetJourneyTransport(KtorSelfHostedSyncTransport(HttpClient(OkHttp)))
    }
}
