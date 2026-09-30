package saien.someday.sync.causality.v2

import kotlinx.coroutines.CancellationException
import saien.someday.domain.settings.ManualSyncReason

/** Closed, content-free failures from the product's entity-publication prerequisite. */
internal enum class WorkspacePublicationFailureV2(
    val safeCode: String,
    val safeMessage: String,
    val manualSyncReason: ManualSyncReason,
    val retryable: Boolean = false,
) {
    TRANSFER_PENDING(
        "media_transfer_pending", "Media transfer is temporarily unavailable; retry synchronization.",
        ManualSyncReason.MediaTransferPending, true,
    ),
    RATE_LIMITED(
        "media_rate_limited", "The server temporarily limited media requests; retry synchronization later.",
        ManualSyncReason.MediaRateLimited, true,
    ),
    STORAGE_FULL(
        "media_storage_full", "The server media quota is exhausted.", ManualSyncReason.MediaStorageFull,
    ),
    MISSING(
        "media_missing", "A referenced media original is unavailable; preserve local data and check the original images.",
        ManualSyncReason.MediaUnavailable,
    ),
    INTEGRITY(
        "media_integrity_failed", "Media integrity verification failed; preserve local data and check the original images.",
        ManualSyncReason.MediaUnavailable,
    ),
    UNKNOWN(
        "entity_publication_prerequisite_failed", "Media publication could not be verified safely.",
        ManualSyncReason.MediaPublicationFailed,
    );

    companion object {
        fun fromCode(code: String?): WorkspacePublicationFailureV2? = entries.firstOrNull { it.safeCode == code }
    }
}

internal class WorkspacePublicationExceptionV2(val failure: WorkspacePublicationFailureV2) :
    IllegalStateException(failure.safeMessage)

internal fun Throwable.publicationFailureV2(): WorkspacePublicationFailureV2 {
    if (this is CancellationException) throw this
    return (this as? WorkspacePublicationExceptionV2)?.failure ?: WorkspacePublicationFailureV2.UNKNOWN
}
