package saien.someday.sync.selfhosted

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import saien.someday.sync.causality.v2.WorkspacePublicationExceptionV2
import saien.someday.sync.causality.v2.WorkspacePublicationFailureV2

/** Only transport I/O is classified here; local filesystem errors are not network failures. */
internal inline fun <T> mediaPublicationRequest(request: () -> T): T = try {
    request()
} catch (failure: Exception) {
    when (failure) {
        is CancellationException -> throw failure
        is SelfHostedSyncHttpException -> when {
            failure.status == 429 -> throw WorkspacePublicationExceptionV2(WorkspacePublicationFailureV2.RATE_LIMITED)
            failure.status in 500..599 -> throw WorkspacePublicationExceptionV2(WorkspacePublicationFailureV2.TRANSFER_PENDING)
            // In particular, preserve authentication/incarnation handling and the exact missing-object response.
            else -> throw failure
        }
        is IOException, is HttpRequestTimeoutException ->
            throw WorkspacePublicationExceptionV2(WorkspacePublicationFailureV2.TRANSFER_PENDING)
        else -> throw failure
    }
}
