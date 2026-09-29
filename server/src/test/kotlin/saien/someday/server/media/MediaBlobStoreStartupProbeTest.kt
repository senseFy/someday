package saien.someday.server.media

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MediaBlobStoreStartupProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun filesystemProbeKeepsOneIsolatedMarkerAndIsRepeatable() {
        val root = temporaryFolder.newFolder("startup-probe-media").toPath()
        val store = FileSystemMediaBlobStore(root)

        verifyMediaBlobStoreStartup(store)
        verifyMediaBlobStoreStartup(store)

        val marker = root.resolve(".someday-system/startup-probe-v1.bin")
        assertTrue(marker.isRegularFile())
        assertEquals(
            listOf(marker),
            Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).sorted().toList() },
        )
    }

    @Test
    fun nestedProbeUsesOneReusableMarkerOutsideAccountCleanupRoots() {
        val root = temporaryFolder.newFolder("nested-startup-probe").toPath()
        val store = FileSystemMediaBlobStore(root)
        verifyMediaBlobStoreStartup(store)
        verifyMediaBlobStoreStartup(store, incarnation = UUID.randomUUID())
        verifyMediaBlobStoreStartup(store, incarnation = UUID.randomUUID())

        assertEquals(
            setOf(
                root.resolve(".someday-system/startup-probe-v1.bin"),
                root.resolve(".incarnations/v1/.someday-system/startup-probe-v1.bin"),
            ),
            Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().toSet() },
        )
    }

    @Test
    fun legacyProbeCannotHideAnUnusableNestedLayout() {
        val root = temporaryFolder.newFolder("denied-nested-startup-probe").toPath()
        val store = FileSystemMediaBlobStore(root)
        Files.writeString(root.resolve(".incarnations"), "not a directory")
        verifyMediaBlobStoreStartup(store)

        assertFailsWith<IllegalStateException> {
            verifyMediaBlobStoreStartup(store, incarnation = UUID.randomUUID())
        }
    }

    @Test
    fun filesystemProbeFailsIfTheReservedImmutableValueWasChanged() {
        val root = temporaryFolder.newFolder("corrupt-startup-probe-media").toPath()
        val marker = root.resolve(".someday-system/startup-probe-v1.bin")
        Files.createDirectories(marker.parent)
        Files.write(marker, "different-retained-value".encodeToByteArray())

        assertFailsWith<IllegalStateException> {
            verifyMediaBlobStoreStartup(FileSystemMediaBlobStore(root))
        }
    }

    @Test
    fun filesystemProbeRejectsAnOccupiedMissingPathThatIsNotAReadableFile() {
        val root = temporaryFolder.newFolder("occupied-missing-probe-media").toPath()
        Files.createDirectories(root.resolve(".someday-system/startup-probe-missing-v1.bin"))

        assertFailsWith<IllegalStateException> {
            verifyMediaBlobStoreStartup(FileSystemMediaBlobStore(root))
        }
    }
}
