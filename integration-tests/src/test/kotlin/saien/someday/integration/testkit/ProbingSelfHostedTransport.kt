package saien.someday.integration.testkit

import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedMediaTransportV3
import saien.someday.sync.selfhosted.SelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointChunkRequest
import saien.someday.sync.selfhosted.SelfHostedV2CheckpointManifestRequest
import saien.someday.sync.selfhosted.SelfHostedV2ImmutablePutResponse
import saien.someday.sync.selfhosted.SelfHostedV2PushRequest
import saien.someday.sync.selfhosted.SelfHostedV2PushResponse

/**
 * Real transport with one explicit cross-plane observation point. The media
 * journey uses it to assert that the server already has the immutable media
 * object before the first entity publication request is sent.
 */
internal class ProbingSelfHostedTransport(
    private val delegate: JdkSelfHostedSyncTransport = JdkSelfHostedSyncTransport(),
) : SelfHostedSyncTransport by delegate,
    SelfHostedSyncTransportV2 by delegate,
    SelfHostedMediaTransportV3 by delegate {
    @Volatile
    var beforeEntityPublication: ((endpoint: String, accessToken: String, workspaceId: String) -> Unit)? = null

    override fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse {
        beforeEntityPublication?.invoke(endpoint, accessToken, request.workspaceId)
        return delegate.v2PutCheckpointChunk(endpoint, accessToken, request)
    }

    override fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse {
        beforeEntityPublication?.invoke(endpoint, accessToken, request.workspaceId)
        return delegate.v2PutCheckpointManifest(endpoint, accessToken, request)
    }

    override fun v2Push(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2PushRequest,
    ): SelfHostedV2PushResponse {
        beforeEntityPublication?.invoke(endpoint, accessToken, request.workspaceId)
        return delegate.v2Push(endpoint, accessToken, request)
    }
}
