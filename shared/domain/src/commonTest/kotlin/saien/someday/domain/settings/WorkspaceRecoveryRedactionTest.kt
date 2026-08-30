package saien.someday.domain.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceRecoveryRedactionTest {
    @Test
    fun diagnosticMessagesRedactDisplayedAndNormalizedRecoveryCodes() {
        val displayed = "SOMEDAY-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"
        val normalized = displayed.replace("-", "")

        val result = WorkspaceRecoveryRestoreResult.failure(
            reason = WorkspaceRecoveryReason.Failed,
            diagnosticMessage = "displayed=${displayed.lowercase()} normalized=$normalized",
        )
        val diagnostic = result.diagnosticMessage.orEmpty()

        assertFalse(diagnostic.contains(displayed.lowercase()))
        assertFalse(diagnostic.contains(normalized))
        assertTrue(diagnostic.contains("SOMEDAY-REDACTED"))
    }
}
