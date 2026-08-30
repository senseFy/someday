package saien.someday.sync.selfhosted

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfHostedHttpClientPolicyTest {
    @Test
    fun defaultPolicySeparatesConnectIdleAndPerRequestDeadlines() {
        assertEquals(10_000L, SELF_HOSTED_CONNECT_TIMEOUT_MILLIS)
        assertEquals(60_000L, SELF_HOSTED_SOCKET_TIMEOUT_MILLIS)
        assertEquals(120_000L, SELF_HOSTED_REQUEST_TIMEOUT_MILLIS)
        assertTrue(SELF_HOSTED_CONNECT_TIMEOUT_MILLIS < SELF_HOSTED_SOCKET_TIMEOUT_MILLIS)
        assertTrue(SELF_HOSTED_SOCKET_TIMEOUT_MILLIS < SELF_HOSTED_REQUEST_TIMEOUT_MILLIS)
    }
}
