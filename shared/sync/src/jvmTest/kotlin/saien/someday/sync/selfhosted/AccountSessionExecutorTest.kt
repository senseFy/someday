package saien.someday.sync.selfhosted

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentials

class AccountSessionExecutorTest {
    @Test fun onlyOrdinaryUnauthorizedRefreshesOnceWithCapturedIncarnation() {
        val initial = credentials()
        val store = MemorySessionStore(initial)
        val auth = RefreshTransport()
        val executor = RefreshingSelfHostedSessionExecutor(auth, store)
        var attempts = 0
        val result = executor.authorized(initial.endpoint, initial.userId, initial.accessToken, initial.accountRequestContext()) { token, context ->
            assertEquals(INITIAL_ACCOUNT_INCARNATION, context.accountIncarnation)
            assertTrue(context.protocol1Known)
            attempts++
            if (attempts == 1) throw failure(SelfHostedErrorCode.UNAUTHORIZED)
            assertEquals("fresh-access", token)
            "complete"
        }
        assertEquals("complete", result)
        assertEquals(2, attempts)
        assertEquals(1, auth.refreshes)
        assertEquals(1, store.load()?.accountProtocolVersion)
    }

    @Test fun staleAuthorityBusyAndWrongPasswordNeverTriggerRefreshOrCredentialDeletion() {
        for (code in listOf(SelfHostedErrorCode.ACCOUNT_SESSION_STALE, SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH,
                SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED, SelfHostedErrorCode.ACCOUNT_BUSY, SelfHostedErrorCode.INVALID_CREDENTIALS)) {
            val initial = credentials()
            val store = MemorySessionStore(initial)
            val auth = RefreshTransport()
            val observations = mutableListOf<SelfHostedErrorCode?>()
            val executor = RefreshingSelfHostedSessionExecutor(auth, store, onAccountFailure = { _, _, _, error -> observations += error.errorCode })
            val thrown = assertFailsWith<SelfHostedSyncHttpException> {
                executor.authorized(initial.endpoint, initial.userId, initial.accessToken, initial.accountRequestContext()) { _, _ -> throw failure(code) }
            }
            assertEquals(code, thrown.errorCode)
            assertEquals(code, observations.single())
            assertEquals(0, auth.refreshes)
            assertEquals(initial, store.load())
        }
    }

    @Test fun refreshCannotChangeIncarnationAndBusyRefreshPreservesStoredCredentials() {
        for (newIncarnation in listOf("11111111-1111-4111-8111-111111111111", null)) {
            val initial = credentials()
            val store = MemorySessionStore(initial)
            val auth = RefreshTransport().apply {
                if (newIncarnation == null) refreshFailure = failure(SelfHostedErrorCode.ACCOUNT_BUSY)
                else incarnation = newIncarnation
            }
            val executor = RefreshingSelfHostedSessionExecutor(auth, store)
            var requests = 0
            val thrown = assertFailsWith<SelfHostedSyncHttpException> {
                executor.authorized(initial.endpoint, initial.userId, initial.accessToken, initial.accountRequestContext()) { _, _ ->
                    requests++
                    throw failure(SelfHostedErrorCode.UNAUTHORIZED)
                }
            }
            assertEquals(if (newIncarnation == null) SelfHostedErrorCode.ACCOUNT_BUSY else SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, thrown.errorCode)
            assertEquals(1, auth.refreshes)
            assertEquals(1, requests)
            assertEquals(initial, store.load())
        }
    }

    @Test fun unclassified401RequiresSameCredentialLegacyProofAndCannotOverrideProtocolMemory() {
        for ((verified, known) in listOf(false to false, true to false, true to true)) {
            val initial = credentials().copy(accountProtocolVersion = null)
            val store = MemorySessionStore(initial)
            val auth = RefreshTransport().apply { incarnation = null }
            val executor = RefreshingSelfHostedSessionExecutor(auth, store,
                protocol1Known = { _, _ -> known }, verifyLegacy = { assertEquals(initial, it); verified })
            var requests = 0
            val attempt = {
                executor.authorized(initial.endpoint, initial.userId, initial.accessToken, initial.accountRequestContext()) { _, _ ->
                    requests++
                    if (requests == 1) throw SelfHostedSyncHttpException(401, "Synthetic unclassified 401")
                    "legacy-success"
                }
            }
            if (verified && !known) assertEquals("legacy-success", attempt())
            else assertFailsWith<SelfHostedSyncHttpException> { attempt() }
            assertEquals(if (verified && !known) 1 else 0, auth.refreshes)
        }
    }

    @Test fun lateRefreshCannotOverwriteReplacementCredentialsOrResurrectClearedCredentials() {
        for (replacement in listOf(credentials().copy(accountIncarnation = "11111111-1111-4111-8111-111111111111"), null)) {
            val initial = credentials()
            val store = MemorySessionStore(initial)
            val auth = RefreshTransport().apply { beforeReturn = { if (replacement == null) store.clear() else store.save(replacement) } }
            val executor = RefreshingSelfHostedSessionExecutor(auth, store)
            assertFailsWith<SelfHostedSyncHttpException> {
                executor.authorized(initial.endpoint, initial.userId, initial.accessToken, initial.accountRequestContext()) { _, _ ->
                    throw failure(SelfHostedErrorCode.UNAUTHORIZED)
                }
            }
            assertEquals(replacement, store.load())
            assertEquals(1, auth.refreshes)
        }
    }

    private class RefreshTransport : SelfHostedSyncTransport by MemorySelfHostedPairingTransport() {
        var refreshes = 0
        var incarnation: String? = INITIAL_ACCOUNT_INCARNATION
        var refreshFailure: SelfHostedSyncHttpException? = null
        var beforeReturn: () -> Unit = {}
        override fun refresh(endpoint: String, request: SelfHostedRefreshRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAuthTokensResponse {
            refreshes++
            refreshFailure?.let { throw it }
            beforeReturn()
            return SelfHostedAuthTokensResponse("fresh-access", "fresh-refresh", 300,
                SelfHostedUserResponse(USER, "synthetic@example.invalid"), incarnation, incarnation?.let { 1 })
        }
    }

    private companion object {
        const val USER = "11111111-1111-4111-8111-111111111111"
        fun credentials() = SelfHostedSessionCredentials("https://sync.example.invalid", USER, "synthetic@example.invalid", "writer-a", "Synthetic", "desktop", "old-access", "old-refresh", accountProtocolVersion = 1)
        fun failure(code: SelfHostedErrorCode) = SelfHostedSyncHttpException(code.statuses.first(), "Synthetic request failure", code, true)
    }
}
