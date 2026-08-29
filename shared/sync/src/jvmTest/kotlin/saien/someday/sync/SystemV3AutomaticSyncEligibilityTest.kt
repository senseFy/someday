@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncEpochDescriptorV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2

class SystemV3AutomaticSyncEligibilityTest {
    @Test
    fun eligibilityRequiresAnExistingAuthorityBinding() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val store = SqlDelightSyncProtocolStoreV2(SomedayDatabase(driver))
            val descriptor = SyncEpochDescriptorV2(
                syncEpochId = "00000000-0000-4000-8000-0000000000e1",
                remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                checkpointId = "00000000-0000-4000-8000-0000000000c1",
                checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
                createdByDeviceId = WRITER,
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )

            assertFalse(isAutomaticSyncEligible(store))
            store.persistPreparingEpoch(
                remoteProfile = descriptor.remoteProfile,
                descriptor = descriptor,
                descriptorDigest = "pointer-1",
            )
            assertFalse(isAutomaticSyncEligible(store))

            store.persistPreparingEpoch(
                remoteProfile = descriptor.remoteProfile,
                descriptor = descriptor,
                descriptorDigest = "pointer-1",
                authorityBindingId = "self-hosted|authority-a",
                localWriterDeviceId = WRITER,
            )
            assertTrue(isAutomaticSyncEligible(store))

            store.abandonPreparingEpoch(
                remoteProfile = descriptor.remoteProfile,
                epochId = descriptor.syncEpochId,
                safeErrorCode = "test_abandoned",
                safeErrorMessage = "Test-only abandoned first epoch.",
            )
            assertNull(store.loadLocalAuthority())
            assertTrue(isAutomaticSyncEligible(store))
        } finally {
            driver.close()
        }
    }

    private companion object {
        const val WRITER = "00000000-0000-4000-8000-0000000000a1"
    }
}
