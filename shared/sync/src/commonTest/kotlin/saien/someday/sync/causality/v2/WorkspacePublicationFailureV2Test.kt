package saien.someday.sync.causality.v2

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import saien.someday.domain.settings.ManualSyncReason

class WorkspacePublicationFailureV2Test {
    @Test
    fun durableSafeCodesRestoreTheTypedUiReasonAndRetryPolicy() {
        val expected = listOf(
            ExpectedFailure("media_transfer_pending", ManualSyncReason.MediaTransferPending, true),
            ExpectedFailure("media_rate_limited", ManualSyncReason.MediaRateLimited, true),
            ExpectedFailure("media_storage_full", ManualSyncReason.MediaStorageFull, false),
            ExpectedFailure("media_missing", ManualSyncReason.MediaUnavailable, false),
            ExpectedFailure("media_integrity_failed", ManualSyncReason.MediaUnavailable, false),
            ExpectedFailure("entity_publication_prerequisite_failed", ManualSyncReason.MediaPublicationFailed, false),
        )

        assertEquals(expected.map { it.code }.toSet(), WorkspacePublicationFailureV2.entries.map { it.safeCode }.toSet())
        expected.forEach { fixture ->
            val failure = requireNotNull(WorkspacePublicationFailureV2.fromCode(fixture.code))
            assertEquals(fixture.reason, failure.manualSyncReason)
            assertEquals(fixture.retryable, failure.retryable)
            assertSame(failure, WorkspacePublicationExceptionV2(failure).publicationFailureV2())
        }
    }

    @Test
    fun absentUnknownAndUnrelatedCodesDoNotInventAPublicationFailure() {
        listOf(null, "", "MEDIA_RATE_LIMITED", "account_incarnation_mismatch", "v2_sync_failed").forEach { code ->
            assertNull(WorkspacePublicationFailureV2.fromCode(code))
        }
    }

    @Test
    fun unclassifiedFailuresIncludingLocalIoKeepTheSafeUnknownReason() {
        val sensitiveDetail = "token=test-secret-token, source=/private/import/photo.jpg"
        val failures = listOf(IllegalStateException(sensitiveDetail), IOException(sensitiveDetail))

        failures.forEach { source ->
            val classified = source.publicationFailureV2()

            assertEquals(WorkspacePublicationFailureV2.UNKNOWN, classified)
            assertFalse(classified.retryable)
            assertEquals("Media publication could not be verified safely.", classified.safeMessage)
            assertFalse(classified.safeMessage.contains("test-secret-token"))
            assertFalse(classified.safeMessage.contains("/private/import"))
        }
    }

    @Test
    fun cancellationCannotBecomeAPersistedPublicationFailure() {
        val cancellation = CancellationException("User canceled synchronization")

        assertSame(cancellation, assertFailsWith<CancellationException> { cancellation.publicationFailureV2() })
    }

    private data class ExpectedFailure(val code: String, val reason: ManualSyncReason, val retryable: Boolean)
}
