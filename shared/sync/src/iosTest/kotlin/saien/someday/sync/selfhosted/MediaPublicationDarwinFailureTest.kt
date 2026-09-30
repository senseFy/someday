package saien.someday.sync.selfhosted

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorTimedOut
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.sync.causality.v2.WorkspacePublicationExceptionV2
import saien.someday.sync.causality.v2.WorkspacePublicationFailureV2

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class MediaPublicationDarwinFailureTest {
    @Test
    fun realDarwinTimeoutAndConnectionLossBecomeSafeRetryableTransferFailures() {
        listOf(NSURLErrorTimedOut, NSURLErrorNetworkConnectionLost).forEach { errorCode ->
            val error = NSError(
                domain = NSURLErrorDomain,
                code = errorCode,
                userInfo = mapOf(NSLocalizedDescriptionKey to "token=test-secret-token, source=/private/import/photo.jpg"),
            )
            val transportFailure = DarwinHttpRequestException(error)

            val classified = assertFailsWith<WorkspacePublicationExceptionV2> {
                mediaPublicationRequest { throw transportFailure }
            }

            assertEquals(WorkspacePublicationFailureV2.TRANSFER_PENDING, classified.failure)
            assertEquals("media_transfer_pending", classified.failure.safeCode)
            assertEquals(ManualSyncReason.MediaTransferPending, classified.failure.manualSyncReason)
            assertEquals(true, classified.failure.retryable)
            assertEquals(classified.failure.safeMessage, classified.message)
            assertFalse(classified.toString().contains("test-secret-token"))
            assertFalse(classified.toString().contains("/private/import"))
            assertNull(classified.cause)
        }
    }
}
