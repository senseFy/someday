package saien.someday.server.maintenance

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import saien.someday.server.persistence.MediaReclamationEvidence
import saien.someday.server.persistence.ReclamationBackend
import saien.someday.server.persistence.RetiredAccountMaintenance
import saien.someday.server.persistence.RetiredAccountTarget

data class RetiredAccountPurgeRequest(
    val userId: UUID,
    val incarnation: UUID,
    val expectedDatabaseIdentity: String? = null,
    val expectedVerificationContext: UUID? = null,
    val expectedStorageIdentity: String? = null,
    val execute: Boolean = false,
    val publishersDrained: Boolean = false,
    val profileAttested: Boolean = false,
    val retirementClockTrusted: Boolean = false,
    val operatorRevalidated: Boolean = false,
)

enum class RetiredAccountPurgeStatus { DRY_RUN, RECLAIMED, SETTLING_REQUIRED, SQL_BATCH_LIMIT, ASSUMPTION_VIOLATION, NONEMPTY_VERIFICATION }

data class RetiredAccountPurgeReport(
    val target: RetiredAccountTarget,
    val status: RetiredAccountPurgeStatus,
    val observedEntries: Long,
    val storageIdentity: String,
    val deletedSqlRows: Long = 0,
    val reclaimedAt: Instant? = null,
)

/** No database transaction spans storage work, and no scan result survives another invocation. */
class PurgeRetiredAccountData(
    private val repository: RetiredAccountMaintenance,
    private val media: RetiredMediaStore,
    private val clock: Clock = Clock.systemUTC(),
    private val sqlBatchSize: Int = 500,
    private val maxSqlBatches: Int = 10_000,
) {
    init { require(sqlBatchSize in 1..1000 && maxSqlBatches > 0) }

    fun run(request: RetiredAccountPurgeRequest): RetiredAccountPurgeReport {
        val storageIdentity = media.storageIdentity
        request.expectedStorageIdentity?.let { require(it == storageIdentity) { "Storage identity changed." } }
        fun validateStorage() = require(media.storageIdentity == storageIdentity) { "Storage identity changed during maintenance." }
        val target = requireNotNull(repository.inspectTarget(request.userId, request.incarnation)) {
            "The exact target does not identify a retired account incarnation."
        }
        request.expectedDatabaseIdentity?.let { require(it == target.databaseIdentity) { "Database identity changed." } }
        request.expectedVerificationContext?.let { require(it == target.verificationContext) { "Maintenance verification context changed." } }
        val namespace = RetiredMediaNamespace(target.userId, target.incarnation, target.storageLayout)
        if (!request.execute) {
            val observed = media.scan(namespace) {
                validateStorage()
                require(repository.inspectTarget(request.userId, request.incarnation) == target) { "Retired target changed during inspection." }
            }
            require(repository.inspectTarget(request.userId, request.incarnation) == target) { "Retired target changed during inspection." }
            validateStorage()
            return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.DRY_RUN, observed.entries, storageIdentity)
        }
        require(request.expectedDatabaseIdentity != null && request.expectedVerificationContext != null && request.expectedStorageIdentity != null) {
            "Execution requires database, storage and verification identities from a fresh dry run."
        }
        require(request.publishersDrained && request.profileAttested) {
            "Execution requires drained publishers and an attested maintenance profile."
        }
        if (media.backend == MaintenanceMediaBackend.S3) {
            require(request.retirementClockTrusted) { "S3 certification requires a trusted retirement clock." }
        }
        val audit = repository.beginAudit(target, request.operatorRevalidated)
        var assumptionViolationObserved = false
        fun inspectBatch(entries: List<RetiredMediaEntry>) {
            validateStorage()
            repository.validateAudit(audit)
            if (audit.previouslyReclaimedAt != null && entries.isNotEmpty() && !assumptionViolationObserved) {
                // Persist the first contradictory observation before a later page can fail.
                // An interrupted scan must not forget a known profile violation.
                repository.recordAssumptionViolation(audit)
                assumptionViolationObserved = true
            }
        }
        val observed = media.scan(namespace, ::inspectBatch)
        if (audit.previouslyReclaimedAt != null && observed.entries > 0) {
            return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.ASSUMPTION_VIOLATION, observed.entries, storageIdentity)
        }

        var sqlRows = 0L
        var sqlComplete = false
        repeat(maxSqlBatches) {
            if (!sqlComplete) {
                val removed = repository.purgeSqlBatch(audit, sqlBatchSize)
                sqlRows += removed
                sqlComplete = removed == 0
            }
        }
        if (!sqlComplete) return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.SQL_BATCH_LIMIT, observed.entries, storageIdentity, sqlRows)

        if (observed.entries > 0) {
            media.scan(namespace) { entries ->
                validateStorage()
                repository.validateAudit(audit)
                media.deleteBatch(namespace, entries)
            }
        }
        val barrier = if (media.backend == MaintenanceMediaBackend.S3) target.retiredAt.plus(Duration.ofHours(24)) else target.retiredAt
        val scanStartedAt = clock.instant()
        if (scanStartedAt.isBefore(barrier)) {
            return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.SETTLING_REQUIRED, observed.entries, storageIdentity, sqlRows)
        }
        repository.validateAudit(audit)
        // A deletion scan is never evidence of emptiness: enumerate the entire namespace again.
        val verification = media.scan(namespace, ::inspectBatch)
        val scanCompletedAt = clock.instant()
        if (verification.entries != 0L) {
            if (audit.previouslyReclaimedAt != null) {
                return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.ASSUMPTION_VIOLATION, verification.entries, storageIdentity, sqlRows)
            }
            return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.NONEMPTY_VERIFICATION, verification.entries, storageIdentity, sqlRows)
        }
        validateStorage()
        val reclaimedAt = repository.certifyMediaReclaimed(
            audit,
            MediaReclamationEvidence(
                backend = if (media.backend == MaintenanceMediaBackend.S3) ReclamationBackend.S3 else ReclamationBackend.FILESYSTEM,
                scanStartedAt = scanStartedAt,
                scanCompletedAt = scanCompletedAt,
                completeEmpty = true,
            ),
        )
        return RetiredAccountPurgeReport(target, RetiredAccountPurgeStatus.RECLAIMED, observed.entries, storageIdentity, sqlRows, reclaimedAt)
    }
}
