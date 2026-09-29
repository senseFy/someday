package saien.someday.data.account

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION

class SqlDelightAccountStateRepositoryTest {
    @Test
    fun submissionSurvivesProcessLossAndUnknownRetryNeverClearsTheIntent() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.persistIntent(intent())
            val first = repository.markSubmitted(OPERATION)
            assertFalse(first.hadEarlierUncertainty)
            assertEquals(AccountResetIntentState.OutcomeUnknown, repository.loadIntent()?.state)
        }
        fixture.open { _, repository ->
            assertTrue(requireNotNull(repository.loadIntent()).earlierOutcomeUnknown)
            val retry = repository.markSubmitted(OPERATION)
            assertTrue(retry.hadEarlierUncertainty)
            assertFalse(repository.clearDefinitiveRejection(retry, G0))
            assertNotNull(repository.loadIntent())
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
        }
    }

    @Test
    fun definitiveRejectionNeedsLatestSubmissionAndUnchangedOriginalIncarnation() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.persistIntent(intent())
            val first = repository.markSubmitted(OPERATION)
            assertFalse(repository.clearDefinitiveRejection(first, NEXT))
            assertTrue(repository.clearDefinitiveRejection(first, G0))
            assertNull(repository.loadIntent())
            assertNull(repository.loadGate(ENDPOINT, USER, WORKSPACE))
            repository.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE)
            repository.persistIntent(intent())
            val previous = repository.markSubmitted(OPERATION)
            repository.markSubmitted(OPERATION)
            assertFalse(repository.clearDefinitiveRejection(previous, G0))
            assertNotNull(repository.loadIntent())
        }
    }

    @Test
    fun rejectionCannotEraseAnIndependentResetRequiredGate() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.markResetRequired(ENDPOINT, USER, WORKSPACE, G0)
            repository.persistIntent(intent())
            val submission = repository.markSubmitted(OPERATION)
            assertTrue(repository.clearDefinitiveRejection(submission, G0))
            assertNull(repository.loadIntent())
            assertEquals(AccountWorkspaceGateReason.ResetRequired, repository.loadGate(ENDPOINT, USER, WORKSPACE)?.reason)
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
        }
    }

    @Test
    fun offlineEditingInvalidatesOldConsentButRetainsNetworkBlockAndReceipt() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.persistIntent(intent())
            repository.markSubmitted(OPERATION)
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            repository.enterOfflineMode(ENDPOINT, USER, WORKSPACE)
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            assertFalse(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            assertFalse(repository.completeLocalReconciliation(OPERATION, NEXT))
        }
        fixture.open { _, repository ->
            assertEquals(NEXT, repository.loadIntent()?.receiptIncarnation)
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
            // A late C-level confirmation cannot undo a newer offline choice made through another workflow.
            assertFailsWith<IllegalStateException> { repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT) }
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            assertFalse(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            repository.freezeForLocalDiscardConfirmation(OPERATION)
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            assertTrue(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            assertFalse(repository.completeLocalReconciliation(OPERATION, LATER))
            assertTrue(repository.completeLocalReconciliation(OPERATION, NEXT))
            assertNull(repository.loadIntent())
            assertNull(repository.loadGate(ENDPOINT, USER, WORKSPACE))
        }
    }

    @Test
    fun missingFrozenGateCannotAcceptALateLocalDiscardConsent() = withFixture { fixture ->
        fixture.open { database, repository ->
            repository.persistIntent(intent())
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            repository.freezeForLocalDiscardConfirmation(OPERATION)
            database.somedayQueries.deleteMatchingAccountWorkspaceGate(ENDPOINT, USER, WORKSPACE, G0)
            assertFailsWith<IllegalStateException> { repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT) }
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            assertFalse(repository.completeLocalReconciliation(OPERATION, NEXT))
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
        }
    }

    @Test
    fun unresolvedIntentCannotBeBypassedByWorkspaceReplacementOrAnAbsentGate() = withFixture { fixture ->
        fixture.open { database, repository ->
            repository.persistIntent(intent())
            repository.markSubmitted(OPERATION)
            assertFalse(repository.clearGateAfterReplacement(ENDPOINT, USER, WORKSPACE, G0))
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, OTHER_WORKSPACE) }
            // Even a damaged gate cannot disable the independently durable intent.
            database.somedayQueries.deleteMatchingAccountWorkspaceGate(ENDPOINT, USER, WORKSPACE, G0)
            assertFailsWith<AccountNetworkBlockedException> { repository.requireNetworkAllowed(ENDPOINT, USER, OTHER_WORKSPACE) }
            repository.requireNetworkAllowed(ENDPOINT, "other-user", OTHER_WORKSPACE)
        }
    }

    @Test
    fun discardConfirmationFreezesBeforeDiscoveryAndFailedDiscoveryCannotRestorePriorConsent() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.persistIntent(intent())
            assertFailsWith<IllegalStateException> { repository.freezeForLocalDiscardConfirmation(OPERATION) }
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            repository.enterOfflineMode(ENDPOINT, USER, WORKSPACE)
            assertFalse(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            repository.freezeForLocalDiscardConfirmation(OPERATION)
            assertTrue(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            assertFalse(repository.completeLocalReconciliation(OPERATION, NEXT))
        }
        fixture.open { _, repository ->
            // Discovery failed or the process stopped after freezing: require new consent.
            assertTrue(requireNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE)).productReadOnly)
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            repository.freezeForLocalDiscardConfirmation(OPERATION)
            assertNull(repository.loadIntent()?.consentTargetIncarnation)
            assertFalse(repository.completeLocalReconciliation(OPERATION, NEXT))
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            assertTrue(repository.completeLocalReconciliation(OPERATION, NEXT))
        }
    }

    @Test
    fun intentAndGateWritesRollBackTogetherAndCompletionSharesReplacementTransaction() = withFixture { fixture ->
        fixture.open { database, repository ->
            repository.markResetRequired(ENDPOINT, USER, WORKSPACE, NEXT)
            assertFailsWith<IllegalArgumentException> { repository.persistIntent(intent()) }
            assertNull(repository.loadIntent())
            assertEquals(NEXT, repository.loadGate(ENDPOINT, USER, WORKSPACE)?.expectedIncarnation)
            assertTrue(repository.clearGateAfterReplacement(ENDPOINT, USER, WORKSPACE, NEXT))
            repository.persistIntent(intent())
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            repository.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
            assertFailsWith<SimulatedReplacementFailure> {
                database.transaction {
                    assertTrue(repository.completeLocalReconciliation(OPERATION, NEXT))
                    throw SimulatedReplacementFailure()
                }
            }
            assertNotNull(repository.loadIntent())
            assertNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE))
        }
    }

    @Test
    fun protocolEvidenceAndIntentSurviveWorkspaceCleanupAndReopen() = withFixture { fixture ->
        fixture.open { database, repository ->
            repository.markProtocol1("https://sync.example/", USER)
            repository.markProtocol1(ENDPOINT, USER)
            repository.persistIntent(intent())
            database.transaction {
                database.somedayQueries.deleteAllLocalAuthorityForWorkspaceReplacementV2()
                database.somedayQueries.deleteAllEpochsForWorkspaceReplacementV2()
            }
        }
        fixture.open { _, repository ->
            assertTrue(repository.hasProtocol1(ENDPOINT, USER))
            assertFalse(repository.hasProtocol1(ENDPOINT, "other-user"))
            assertFalse(repository.hasProtocol1("https://other.example", USER))
            assertNotNull(repository.loadIntent())
            assertNotNull(repository.loadGate(ENDPOINT, USER, WORKSPACE))
        }
    }

    @Test
    fun receiptIdentityCannotChangeAndACommittedOperationCannotBeResubmitted() = withFixture { fixture ->
        fixture.open { _, repository ->
            repository.persistIntent(intent())
            val submission = repository.markSubmitted(OPERATION)
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            repository.recordCommittedReceipt(OPERATION, NEXT, 1234)
            assertFailsWith<IllegalStateException> { repository.recordCommittedReceipt(OPERATION, LATER, 1234) }
            assertFailsWith<IllegalStateException> { repository.markSubmitted(OPERATION) }
            assertFalse(repository.clearDefinitiveRejection(submission, G0))
            assertEquals(NEXT, repository.loadIntent()?.receiptIncarnation)
        }
    }

    private class SimulatedReplacementFailure : RuntimeException()
    private fun intent() = AccountResetIntent(OPERATION, ENDPOINT, USER, G0, WORKSPACE, "writer")
    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("someday-account-state-")
        try { block(Fixture("jdbc:sqlite:${directory.resolve("local.db")}")) } finally { directory.toFile().deleteRecursively() }
    }
    private class Fixture(private val url: String) {
        fun open(block: (SomedayDatabase, SqlDelightAccountStateRepository) -> Unit) {
            val driver = createSomedayJdbcDriver(url)
            try {
                val database = SomedayDatabase(driver)
                block(database, SqlDelightAccountStateRepository(database, now = { 1234 }))
            } finally { driver.close() }
        }
    }
    private companion object {
        const val ENDPOINT = "https://sync.example"
        const val USER = "user"
        const val WORKSPACE = "workspace-11111111111111111111111111111111"
        const val OTHER_WORKSPACE = "workspace-22222222222222222222222222222222"
        const val OPERATION = "11111111-1111-4111-8111-111111111111"
        const val NEXT = "22222222-2222-4222-8222-222222222222"
        const val LATER = "33333333-3333-4333-8333-333333333333"
        const val G0 = INITIAL_ACCOUNT_INCARNATION
    }
}
