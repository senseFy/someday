package saien.someday.server.persistence

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountAdmissionTest {
    @Test fun advisoryNamespaceAndSignedKeyMatchTheIndependentVector() {
        assertEquals(1396982098, AccountAdmission.LOCK_NAMESPACE)
        assertEquals(-1116314971, AccountAdmission.lockKey(UUID.fromString("11111111-1111-4111-8111-111111111111")))
    }
}
