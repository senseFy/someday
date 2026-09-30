@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.someday.app.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import saien.someday.ui.settings.SettingsExportSummary

class IosLocalExportTest {
    @Test
    fun stagingPreservesTheExportWithoutClaimingItWasDelivered() {
        val json = "{\"notes\":[],\"includesMediaBytes\":false}"
        val prepared = prepareIosLocalExport(
            "someday-export.json",
            json,
            SettingsExportSummary.unavailable().copy(destinationLabel = "must-not-claim-saved"),
        )
        try {
            assertTrue(prepared.filePath.startsWith(NSTemporaryDirectory().trimEnd('/') + "/"))
            assertNull(prepared.summary.destinationLabel)
            assertFalse(prepared.summary.includesMediaBytes)
            assertEquals(json, FileSystem.SYSTEM.read(prepared.filePath.toPath()) { readUtf8() })
        } finally {
            prepared.discard()
        }
        assertFalse(FileSystem.SYSTEM.exists(prepared.filePath.toPath()))
        assertFalse(FileSystem.SYSTEM.exists(checkNotNull(prepared.filePath.toPath().parent)))
    }

    @Test
    fun cleanupRemovesOnlyItsOwnAttemptAndIsSafeToRepeat() {
        val first = prepareIosLocalExport("someday-export.json", "first", SettingsExportSummary.unavailable())
        val second = prepareIosLocalExport("someday-export.json", "second", SettingsExportSummary.unavailable())
        try {
            first.discard()
            first.discard()
            assertFalse(FileSystem.SYSTEM.exists(first.filePath.toPath()))
            assertEquals("second", FileSystem.SYSTEM.read(second.filePath.toPath()) { readUtf8() })
        } finally {
            first.discard()
            second.discard()
        }
    }

    @Test
    fun stagingRejectsPathsOutsideItsAttemptDirectory() {
        for (name in listOf("", ".", "..", "../export.json", "folder/export.json")) {
            assertFailsWith<IllegalArgumentException> {
                prepareIosLocalExport(name, "{}", SettingsExportSummary.unavailable())
            }
        }
    }
}
