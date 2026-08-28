@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase

class SqlDelightSyncProtocolStoreV2Test {
    @Test
    fun resolvedDeadLetterIsDeletedInsteadOfEnteringARepairLifecycle() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val store = SqlDelightSyncProtocolStoreV2(SomedayDatabase(driver))
            val input = SyncDeadLetterInputV2(
                remoteProfile = PROFILE,
                epochId = "00000000-0000-4000-8000-0000000000e1",
                streamId = "writer:$WRITER",
                unitId = "00000000-0000-4000-8000-0000000000d1",
                cursorValue = "1",
                unitDigest = "digest",
                objectId = null,
                objectDigest = null,
                authenticatedUnit = "opaque",
                failureClass = SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY,
                safeErrorCode = "dependency_unresolved",
                safeErrorMessage = "A parent object has not arrived yet.",
            )

            store.recordDeadLetter(input, Instant.fromEpochMilliseconds(1_000))
            store.recordDeadLetter(input, Instant.fromEpochMilliseconds(2_000))

            assertEquals(1L, store.loadUnresolvedDeadLetters(PROFILE, input.epochId).single().retryCount)
            store.resolveDeadLetter(PROFILE, input.epochId, input.streamId, input.unitId)
            assertTrue(store.loadUnresolvedDeadLetters(PROFILE, input.epochId).isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun epochAuthorityBindingMismatchRollsBackBothRows() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val store = SqlDelightSyncProtocolStoreV2(SomedayDatabase(driver))
            val first = descriptor(
                epochId = "00000000-0000-4000-8000-0000000000e1",
                checkpointId = "00000000-0000-4000-8000-0000000000c1",
            )
            val competing = descriptor(
                epochId = "00000000-0000-4000-8000-0000000000e2",
                checkpointId = "00000000-0000-4000-8000-0000000000c2",
            )
            assertIs<SyncEpochPersistResultV2.Stored>(
                store.persistPreparingEpoch(PROFILE, first, "pointer-1", AUTHORITY, WRITER),
            )
            assertIs<SyncEpochPersistResultV2.Stored>(
                store.persistPreparingEpoch(PROFILE, competing, "pointer-2"),
            )

            assertIs<SyncEpochPersistResultV2.ImmutableMismatch>(
                store.persistPreparingEpoch(PROFILE, competing, "pointer-2", AUTHORITY, WRITER),
            )

            assertNull(store.loadEpoch(PROFILE, competing.syncEpochId)?.authorityBindingId)
            assertEquals(first.syncEpochId, store.loadLocalAuthority()?.epochId)
            assertEquals(AUTHORITY, store.loadLocalAuthority()?.authorityBindingId)
        } finally {
            driver.close()
        }
    }

    private fun descriptor(epochId: String, checkpointId: String) = SyncEpochDescriptorV2(
        syncEpochId = epochId,
        remoteProfile = PROFILE,
        checkpointId = checkpointId,
        checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
        createdByDeviceId = WRITER,
        createdAt = Instant.fromEpochMilliseconds(1_000),
    )

    private companion object {
        const val PROFILE = "self-hosted-v2"
        const val AUTHORITY = "self-hosted|authority-a"
        const val WRITER = "00000000-0000-4000-8000-0000000000a1"
    }
}
