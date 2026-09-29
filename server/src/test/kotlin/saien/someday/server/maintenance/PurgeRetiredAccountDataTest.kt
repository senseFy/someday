package saien.someday.server.maintenance

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.persistence.MediaReclamationEvidence
import saien.someday.server.persistence.MediaReclamationRevalidationRequired
import saien.someday.server.persistence.RetiredAccountAudit
import saien.someday.server.persistence.RetiredAccountMaintenance
import saien.someday.server.persistence.RetiredAccountTarget

class PurgeRetiredAccountDataTest {
    @Test fun dryRunDoesNotClearCertificateOrDeleteSqlOrObjects() {
        val repository = FakeRepository(previouslyCertified = true)
        val media = FakeMedia(mutableListOf(RetiredMediaEntry("orphan")))
        val result = service(repository, media).run(request(repository, execute = false))
        assertEquals(RetiredAccountPurgeStatus.DRY_RUN, result.status)
        assertTrue(repository.marker)
        assertFalse(repository.began)
        assertEquals(1, media.entries.size)
        assertEquals(0, repository.sqlCalls)
    }

    @Test fun deletesThenRequiresAnotherCompleteEmptyScanBeforeCertification() {
        val repository = FakeRepository()
        val media = FakeMedia(mutableListOf(RetiredMediaEntry("orphan"), RetiredMediaEntry(".media-upload-tmp")))
        val result = service(repository, media).run(request(repository))
        assertEquals(RetiredAccountPurgeStatus.RECLAIMED, result.status)
        assertEquals(3, media.scans)
        assertEquals(2L, result.observedEntries)
        assertEquals(3L, result.deletedSqlRows)
        assertTrue(repository.marker)
        assertTrue(checkNotNull(repository.evidence).completeEmpty)
    }

    @Test fun s3CertificationCannotStartEvenOneInstantBeforeSettlementDeadline() {
        val repository = FakeRepository()
        val media = FakeMedia(backend = MaintenanceMediaBackend.S3)
        val deadline = repository.target.retiredAt.plusSeconds(24 * 60 * 60)
        val early = service(repository, media, deadline.minusNanos(1)).run(request(repository))
        assertEquals(RetiredAccountPurgeStatus.SETTLING_REQUIRED, early.status)
        assertFalse(repository.marker)
        assertEquals(1, media.scans)
        val ready = service(repository, media, deadline).run(request(repository))
        assertEquals(RetiredAccountPurgeStatus.RECLAIMED, ready.status)
        assertEquals(deadline, assertNotNull(repository.evidence).scanStartedAt)
    }

    @Test fun lateObjectInvalidatesPriorAttestationAndRequiresExplicitRevalidationOnRerun() {
        val repository = FakeRepository(previouslyCertified = true)
        val media = FakeMedia(mutableListOf(RetiredMediaEntry("late-version", "opaque-version")))
        val result = service(repository, media).run(request(repository))
        assertEquals(RetiredAccountPurgeStatus.ASSUMPTION_VIOLATION, result.status)
        assertFalse(repository.marker)
        assertTrue(repository.violation)
        assertEquals(1, media.entries.size)
        assertFailsWith<MediaReclamationRevalidationRequired> { service(repository, media).run(request(repository)) }
        assertEquals(RetiredAccountPurgeStatus.RECLAIMED, service(repository, media).run(request(repository).copy(operatorRevalidated = true)).status)
    }

    @Test fun interruptedListingAndAnObjectAppearingDuringVerificationNeverCertify() {
        val repository = FakeRepository()
        val media = FakeMedia(mutableListOf(RetiredMediaEntry("orphan"))).apply { failOnScan = 2 }
        assertFailsWith<MaintenanceMediaFailure> { service(repository, media).run(request(repository)) }
        assertFalse(repository.marker)
        assertEquals(null, repository.evidence)

        val otherRepository = FakeRepository()
        val arrivingMedia = FakeMedia().apply { addOnScan = 2 }
        val result = service(otherRepository, arrivingMedia).run(request(otherRepository))
        assertEquals(RetiredAccountPurgeStatus.NONEMPTY_VERIFICATION, result.status)
        assertFalse(otherRepository.marker)
    }

    @Test fun aLateObjectObservationStaysStickyWhenALaterListingPageFails() {
        val repository = FakeRepository(previouslyCertified = true)
        val media = FakeMedia(mutableListOf(RetiredMediaEntry("late-object"))).apply { failAfterObservationOnScan = 1 }
        assertFailsWith<MaintenanceMediaFailure> { service(repository, media).run(request(repository)) }
        assertTrue(repository.violation)
        assertFalse(repository.marker)
        assertEquals(0, repository.sqlCalls)
        // Even if the external writer removes its object, the known violation cannot be forgotten.
        media.entries.clear()
        media.failAfterObservationOnScan = null
        assertFailsWith<MediaReclamationRevalidationRequired> { service(repository, media).run(request(repository)) }
        assertEquals(null, repository.evidence)
    }

    @Test fun staleDeletionPlanOrMissingAttestationFailsBeforeMarkerMutation() {
        val repository = FakeRepository(previouslyCertified = true)
        val media = FakeMedia()
        assertFailsWith<IllegalArgumentException> {
            service(repository, media).run(request(repository).copy(expectedVerificationContext = UUID.randomUUID()))
        }
        assertFailsWith<IllegalArgumentException> {
            service(repository, media).run(request(repository).copy(publishersDrained = false))
        }
        assertFalse(repository.began)
        assertTrue(repository.marker)
    }

    private fun service(repository: FakeRepository, media: FakeMedia, now: Instant = NOW) =
        PurgeRetiredAccountData(repository, media, Clock.fixed(now, ZoneOffset.UTC))

    private fun request(repository: FakeRepository, execute: Boolean = true) = RetiredAccountPurgeRequest(
        repository.target.userId, repository.target.incarnation, repository.target.databaseIdentity,
        repository.target.verificationContext, "synthetic-storage", execute, publishersDrained = true, profileAttested = true,
        retirementClockTrusted = true,
    )

    private class FakeRepository(previouslyCertified: Boolean = false) : RetiredAccountMaintenance {
        var marker = previouslyCertified
        var began = false
        var violation = false
        var sqlCalls = 0
        var evidence: MediaReclamationEvidence? = null
        var target = RetiredAccountTarget(
            UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy", NOW.minusSeconds(25 * 60 * 60),
            if (previouslyCertified) NOW.minusSeconds(1) else null, UUID.randomUUID(), "synthetic-db:1:local:local", false,
        )
        override fun inspectTarget(userId: UUID, incarnation: UUID) = target
        override fun databaseIdentity() = target.databaseIdentity
        override fun beginAudit(target: RetiredAccountTarget, operatorRevalidated: Boolean): RetiredAccountAudit {
            if (violation && !operatorRevalidated) throw MediaReclamationRevalidationRequired()
            if (operatorRevalidated) {
                violation = false
                this.target = this.target.copy(previouslyReclaimedAt = null, revalidationRequired = false)
            }
            began = true
            marker = false
            return RetiredAccountAudit(this.target, UUID.randomUUID(), this.target.previouslyReclaimedAt)
        }
        override fun validateAudit(audit: RetiredAccountAudit) {}
        override fun purgeSqlBatch(audit: RetiredAccountAudit, batchSize: Int): Int = if (++sqlCalls == 1) 3 else 0
        override fun recordAssumptionViolation(audit: RetiredAccountAudit) { violation = true }
        override fun certifyMediaReclaimed(audit: RetiredAccountAudit, evidence: MediaReclamationEvidence): Instant {
            this.evidence = evidence
            marker = true
            return evidence.scanCompletedAt
        }
        override fun invalidateAfterRestore(expectedDatabaseIdentity: String) { error("Not expected") }
    }

    private class FakeMedia(
        val entries: MutableList<RetiredMediaEntry> = mutableListOf(),
        override val backend: MaintenanceMediaBackend = MaintenanceMediaBackend.FILESYSTEM,
    ) : RetiredMediaStore {
        override val storageIdentity = "synthetic-storage"
        var scans = 0
        var failOnScan: Int? = null
        var failAfterObservationOnScan: Int? = null
        var addOnScan: Int? = null
        override fun scan(namespace: RetiredMediaNamespace, consume: (List<RetiredMediaEntry>) -> Unit): RetiredMediaScan {
            scans++
            if (scans == failOnScan) throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.INCOMPLETE_SCAN)
            if (scans == addOnScan) entries += RetiredMediaEntry("late-object")
            val snapshot = entries.toList()
            snapshot.chunked(1).forEach(consume)
            if (scans == failAfterObservationOnScan) throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.INCOMPLETE_SCAN)
            return RetiredMediaScan(snapshot.size.toLong())
        }
        override fun deleteBatch(namespace: RetiredMediaNamespace, entries: List<RetiredMediaEntry>) { this.entries.removeAll(entries.toSet()) }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-09-28T12:00:00Z") }
}
