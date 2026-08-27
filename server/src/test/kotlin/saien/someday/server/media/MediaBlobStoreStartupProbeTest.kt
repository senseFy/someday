package saien.someday.server.media

import java.nio.file.Files
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
