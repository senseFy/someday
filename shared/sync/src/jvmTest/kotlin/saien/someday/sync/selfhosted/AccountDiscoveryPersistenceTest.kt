package saien.someday.sync.selfhosted

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentials

class AccountDiscoveryPersistenceTest {
    @Test fun legacyProofRequiresMatchingMeAndIsSpecificToOneCredential() = fixture { states ->
        val transport = DiscoveryTransport()
        val service = SelfHostedAccountDiscoveryService(transport, states)
        transport.meUser = "22222222-2222-4222-8222-222222222222"
        assertFailsWith<SelfHostedProtocolException> { service.isVerifiedLegacy(credentials()) }
        transport.meUser = USER
        assertTrue(service.isVerifiedLegacy(credentials()))
        val verifiedCalls = transport.discoveryCalls
        assertTrue(service.isVerifiedLegacy(credentials()))
        assertEquals(verifiedCalls, transport.discoveryCalls)
        transport.failure = IOException("network unavailable")
        assertFailsWith<IOException> { service.isVerifiedLegacy(credentials().copy(accessToken = "different-access")) }
        assertFalse(states.hasProtocol1(ENDPOINT, USER))
    }

    @Test fun protocolMemoryOverridesEarlierLegacyProofAndSurvivesServiceRecreation() = fixture { states ->
        val transport = DiscoveryTransport()
        val service = SelfHostedAccountDiscoveryService(transport, states)
        assertTrue(service.isVerifiedLegacy(credentials()))
        service.recordIssuance(credentials().copy(accountProtocolVersion = 1))
        assertTrue(states.hasProtocol1(ENDPOINT, USER))
        assertFalse(service.isVerifiedLegacy(credentials()))
        val restarted = SelfHostedAccountDiscoveryService(transport, states)
        assertFailsWith<SelfHostedProtocolException> { restarted.discover(credentials()) }
        assertTrue(states.hasProtocol1(ENDPOINT, USER))
    }

    @Test fun discoveryCanEstablishCapabilityWithoutRelabellingOldCredentials() = fixture { states ->
        val old = credentials()
        val transport = DiscoveryTransport().apply {
            result = SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountDataStateResponse(
                1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", false, "retired_media_pending"))
        }
        val service = SelfHostedAccountDiscoveryService(transport, states)
        assertFalse(service.isVerifiedLegacy(old))
        assertTrue(states.hasProtocol1(ENDPOINT, USER))
        assertEquals(INITIAL_ACCOUNT_INCARNATION, old.accountIncarnation)
        assertEquals(null, old.accountProtocolVersion)
        assertEquals(0, transport.meCalls)
    }

    @Test fun issuanceDuringLegacyMeVerificationPreventsDowngrade() = fixture { states ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = DiscoveryTransport().apply {
            beforeMe = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        val service = SelfHostedAccountDiscoveryService(transport, states)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> { service.isVerifiedLegacy(credentials()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            service.recordIssuance(credentials().copy(accountProtocolVersion = 1))
            release.countDown()
            val thrown = assertFailsWith<ExecutionException> { pending.get(10, TimeUnit.SECONDS) }
            assertTrue(thrown.cause is SelfHostedProtocolException)
            assertFalse(service.isVerifiedLegacy(credentials()))
        } finally {
            release.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun pendingLegacyHttpDoesNotHoldCacheMutexAgainstNewProtocolDiscovery() = fixture { states ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = DiscoveryTransport().apply {
            beforeMe = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        val service = SelfHostedAccountDiscoveryService(transport, states)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val legacy = workers.submit<Boolean> { service.isVerifiedLegacy(credentials()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            transport.result = SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountDataStateResponse(1, INITIAL_ACCOUNT_INCARNATION, true, null))
            val current = workers.submit<SelfHostedAccountDiscoveryResult> { service.discover(credentials()) }
            assertTrue(current.get(10, TimeUnit.SECONDS) is SelfHostedAccountDiscoveryResult.Protocol1)
            assertTrue(states.hasProtocol1(ENDPOINT, USER))
            release.countDown()
            assertTrue(assertFailsWith<ExecutionException> { legacy.get(10, TimeUnit.SECONDS) }.cause is SelfHostedProtocolException)
            assertFalse(service.isVerifiedLegacy(credentials()))
        } finally {
            release.countDown()
            workers.shutdownNow()
            check(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun typedDiscoveryAndMeFailuresPersistCapabilityBeforeRethrowing() = fixture { states ->
        for (duringMe in listOf(false, true)) {
            val account = credentials().copy(userId = if (duringMe) "22222222-2222-4222-8222-222222222222" else USER)
            val failure = SelfHostedSyncHttpException(503, "Synthetic busy", SelfHostedErrorCode.ACCOUNT_BUSY, true)
            val transport = DiscoveryTransport().apply {
                meUser = account.userId
                if (duringMe) beforeMe = { throw failure } else this.failure = failure
            }
            val service = SelfHostedAccountDiscoveryService(transport, states)
            assertEquals(failure, assertFailsWith<SelfHostedSyncHttpException> { service.discover(account) })
            assertTrue(states.hasProtocol1(account.endpoint, account.userId))
            transport.failure = null
            transport.beforeMe = {}
            assertFalse(SelfHostedAccountDiscoveryService(transport, states).isVerifiedLegacy(account))
        }
    }

    private fun fixture(block: (SqlDelightAccountStateRepository) -> Unit) {
        val file = Files.createTempFile("someday-discovery-proof-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:$file")
        try { block(SqlDelightAccountStateRepository(SomedayDatabase(driver))) } finally { driver.close(); Files.deleteIfExists(file) }
    }

    private class DiscoveryTransport : SelfHostedAccountControlTransport {
        var discoveryCalls = 0
        var meCalls = 0
        var meUser = USER
        var failure: Exception? = null
        var beforeMe: () -> Unit = {}
        var result: SelfHostedAccountDiscoveryResult = SelfHostedAccountDiscoveryResult.LegacyCandidate404
        override fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountDiscoveryResult {
            discoveryCalls++
            failure?.let { throw it }
            return result
        }
        override fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountMeResponse {
            meCalls++
            beforeMe()
            return SelfHostedAccountMeResponse(meUser, "synthetic@example.invalid", DEVICE, listOf("auth"))
        }
        override fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse? = error("Unused")
        override fun resetAccountData(endpoint: String, accessToken: String, request: SelfHostedAccountResetRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse = error("Unused")
    }

    private companion object {
        const val ENDPOINT = "https://legacy.example.invalid"
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val DEVICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        fun credentials() = SelfHostedSessionCredentials(ENDPOINT, USER, "synthetic@example.invalid", DEVICE, "Synthetic", "desktop", "access", "refresh")
    }
}
