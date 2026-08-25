package saien.someday.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MediaAssetsTest {
    @Test
    fun canonicalAssetUriRoundTrips() {
        val digest = "ab".repeat(32)
        val id = MediaAssetId.fromCanonicalValue(digest)
        val uri = SomedayAssetUri(id)

        assertEquals(digest, id.value)
        assertEquals("someday-asset://$digest", uri.toString())
        assertEquals(uri, SomedayAssetUri.parseOrNull(uri.toString()))
    }

    @Test
    fun assetUriParserRejectsNonCanonicalAndExtendedForms() {
        val digest = "ab".repeat(32)

        assertNull(SomedayAssetUri.parseOrNull("https://example.test/$digest"))
        assertNull(SomedayAssetUri.parseOrNull("someday-asset://${digest.uppercase()}"))
        assertNull(SomedayAssetUri.parseOrNull("someday-asset://${digest.dropLast(1)}"))
        assertNull(SomedayAssetUri.parseOrNull("someday-asset://$digest?variant=thumb"))
        assertNull(SomedayAssetUri.parseOrNull("someday-asset:../$digest"))
    }

    @Test
    fun discoversDistinctCanonicalAssetReferencesWithoutMarkdownCoupling() {
        val first = "12".repeat(32)
        val second = "ab".repeat(32)
        val text = "![first](someday-asset://$first) and someday-asset://$second " +
            "again someday-asset://$first malformed someday-asset://${second}f"

        assertEquals(
            setOf(
                MediaAssetId.fromCanonicalValue(first),
                MediaAssetId.fromCanonicalValue(second),
            ),
            findSomedayAssetIds(text),
        )
    }

    @Test
    fun mediaMetadataUsesCanonicalBoundedValues() {
        assertEquals("image/jpeg", canonicalMediaTypeOrNull("image/jpeg"))
        assertNull(canonicalMediaTypeOrNull("IMAGE/JPEG"))
        assertNull(canonicalMediaTypeOrNull("image/jpeg; charset=binary"))
        assertTrue(isSafeOriginalFileName("holiday photo.jpg"))
        assertTrue(!isSafeOriginalFileName("../holiday.jpg"))

        val metadata = MediaAssetMetadata(
            id = MediaAssetId.fromCanonicalValue("12".repeat(32)),
            byteSize = 42,
            mediaType = "image/jpeg",
            originalFileName = "holiday photo.jpg",
            pixelWidth = 4,
            pixelHeight = 3,
            createdAt = Instant.fromEpochMilliseconds(1_000),
        )
        assertEquals(12L, metadata.decodedPixelCount)
        assertFailsWith<IllegalArgumentException> {
            metadata.copy(pixelWidth = 10_001, pixelHeight = 10_000)
        }
    }
}
