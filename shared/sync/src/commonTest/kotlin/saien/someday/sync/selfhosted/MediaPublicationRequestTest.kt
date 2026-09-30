package saien.someday.sync.selfhosted

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import saien.someday.sync.causality.v2.WorkspacePublicationExceptionV2
import saien.someday.sync.causality.v2.WorkspacePublicationFailureV2

class MediaPublicationRequestTest {
    @Test
    fun successfulRequestPreservesItsResult() {
        val response = Any()

        assertSame(response, mediaPublicationRequest { response })
    }

    @Test
    fun actualHttpRateLimitAndServiceFailureHaveDistinctSafeReasons() {
        assertClassification(
            SelfHostedSyncHttpException(429, SENSITIVE_DETAIL),
            WorkspacePublicationFailureV2.RATE_LIMITED,
        )
        assertClassification(
            SelfHostedSyncHttpException(503, SENSITIVE_DETAIL),
            WorkspacePublicationFailureV2.TRANSFER_PENDING,
        )
    }

    @Test
    fun transportIoAndKtorRequestTimeoutAreRetryableWithoutRetainingPrivateDetails() {
        val failures = listOf(
            IOException(SENSITIVE_DETAIL),
            HttpRequestTimeoutException("https://sync.example/media?token=$SECRET_TOKEN", 120_000L),
        )

        failures.forEach { assertClassification(it, WorkspacePublicationFailureV2.TRANSFER_PENDING) }
    }

    @Test
    fun accountFailuresAndMissingObjectsRetainTheOriginalHttpException() {
        val failures = listOf(
            SelfHostedSyncHttpException(401, SENSITIVE_DETAIL, SelfHostedErrorCode.ACCOUNT_SESSION_STALE, true),
            SelfHostedSyncHttpException(403, SENSITIVE_DETAIL, SelfHostedErrorCode.FORBIDDEN, true),
            SelfHostedSyncHttpException(404, SENSITIVE_DETAIL, SelfHostedErrorCode.MEDIA_OBJECT_NOT_FOUND, true),
            SelfHostedSyncHttpException(409, SENSITIVE_DETAIL, SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, true),
            SelfHostedSyncHttpException(409, SENSITIVE_DETAIL, SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED, true),
            SelfHostedSyncHttpException(426, SENSITIVE_DETAIL, SelfHostedErrorCode.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED, true),
        )

        failures.forEach { failure ->
            assertSame(failure, assertFailsWith<SelfHostedSyncHttpException> { mediaPublicationRequest { throw failure } })
        }
    }

    @Test
    fun protocolUnknownAndAlreadyClassifiedFailuresAreNotRelabeledAsNetworkFailures() {
        val failures = listOf(
            SelfHostedProtocolException(SelfHostedProtocolFailureReason.MALFORMED_BODY),
            IllegalArgumentException(SENSITIVE_DETAIL),
            WorkspacePublicationExceptionV2(WorkspacePublicationFailureV2.INTEGRITY),
        )

        failures.forEach { failure ->
            assertSame(failure, assertFailsWith<Exception> { mediaPublicationRequest { throw failure } })
        }
    }

    @Test
    fun cancellationIsRethrownUnchanged() {
        val cancellation = CancellationException(SENSITIVE_DETAIL)

        assertSame(cancellation, assertFailsWith<CancellationException> { mediaPublicationRequest { throw cancellation } })
    }

    private fun assertClassification(source: Exception, expected: WorkspacePublicationFailureV2) {
        val classified = assertFailsWith<WorkspacePublicationExceptionV2> { mediaPublicationRequest { throw source } }

        assertEquals(expected, classified.failure)
        assertEquals(expected.safeMessage, classified.message)
        assertFalse(classified.toString().contains(SECRET_TOKEN))
        assertFalse(classified.toString().contains(PRIVATE_PATH))
        assertNull(classified.cause)
    }

    private companion object {
        const val SECRET_TOKEN = "test-private-access-token"
        const val PRIVATE_PATH = "/private/import/day-one/photo.jpg"
        const val SENSITIVE_DETAIL = "Request failed: token=$SECRET_TOKEN, source=$PRIVATE_PATH"
    }
}
