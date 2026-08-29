@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.Base64
import org.jetbrains.compose.resources.getString
import saien.someday.ui.media.MediaMaterializationRunner
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewLoader
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.notes.MarkdownPreviewBlock
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.image_preview_download
import saien.someday.ui.resources.image_preview_remote
import saien.someday.ui.resources.sync_retry
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownImagePreviewBlockTest {
    @Test
    fun previewDecoderHonorsEncodedOrientation() {
        val bitmap = addExifOrientation(JPEG_3X2, orientation = 6).decodeToImageBitmap()

        assertEquals(2, bitmap.width)
        assertEquals(3, bitmap.height)
    }

    @Test
    fun remoteImageNeverInvokesMediaPorts() = runComposeUiTest {
        val remoteMessage = getString(Res.string.image_preview_remote)
        var previewCalls = 0
        var materializationCalls = 0
        setContent {
            MaterialTheme {
                MarkdownImagePreviewBlock(
                    block = MarkdownPreviewBlock.Image("Remote", "https://example.test/image.jpg"),
                    mediaUiPorts = MediaUiPorts(
                        previewLoader = MediaPreviewLoader {
                            previewCalls += 1
                            MediaPreviewUiResult.Missing
                        },
                        materializationRunner = MediaMaterializationRunner { _, _ ->
                            materializationCalls += 1
                        },
                    ),
                )
            }
        }

        onNodeWithText(remoteMessage).assertExists()
        assertEquals(0, previewCalls)
        assertEquals(0, materializationCalls)
    }

    @Test
    fun previewReadFailureCanBeRetried() = runComposeUiTest {
        val retryLabel = getString(Res.string.sync_retry)
        var previewCalls = 0
        setContent {
            MaterialTheme {
                MarkdownImagePreviewBlock(
                    block = localImage(),
                    mediaUiPorts = MediaUiPorts(
                        previewLoader = MediaPreviewLoader {
                            previewCalls += 1
                            MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewLoadFailed)
                        },
                    ),
                )
            }
        }

        waitUntil { previewCalls == 1 }
        onNodeWithText(retryLabel).performClick()
        waitUntil { previewCalls == 2 }
    }

    @Test
    fun successfulMaterializationReloadsMissingPreviewOnce() = runComposeUiTest {
        val downloadLabel = getString(Res.string.image_preview_download)
        var previewCalls = 0
        var materializationCalls = 0
        var locallyAvailable = false
        setContent {
            MaterialTheme {
                MarkdownImagePreviewBlock(
                    block = localImage(),
                    mediaUiPorts = MediaUiPorts(
                        previewLoader = MediaPreviewLoader {
                            previewCalls += 1
                            if (locallyAvailable) {
                                MediaPreviewUiResult.Loaded(PNG_1X1.decodeToImageBitmap())
                            } else {
                                MediaPreviewUiResult.Missing
                            }
                        },
                        materializationRunner = MediaMaterializationRunner { _, callback ->
                            materializationCalls += 1
                            locallyAvailable = true
                            callback(MediaMaterializationUiResult.Materialized)
                            callback(MediaMaterializationUiResult.Materialized)
                        },
                    ),
                )
            }
        }

        waitUntil { previewCalls == 1 }
        onNodeWithText(downloadLabel).performClick()
        waitUntil { previewCalls == 2 }
        onNodeWithContentDescription("Sunset").assertExists()
        waitForIdle()
        assertEquals(1, materializationCalls)
        assertEquals(2, previewCalls)
    }

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val JPEG_3X2: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjExLjEwMAD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAACAAMDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAE1/f//Z",
        )

        fun addExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
            require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
            require(orientation in 1..8)
            val exifSegment = byteArrayOf(
                0xff.toByte(), 0xe1.toByte(), 0x00, 0x22,
                0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
                0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
                orientation.toByte(), 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            )
            return jpeg.copyOfRange(0, 2) + exifSegment + jpeg.copyOfRange(2, jpeg.size)
        }

        fun localImage(): MarkdownPreviewBlock.Image = MarkdownPreviewBlock.Image(
            altText = "Sunset",
            destination = "someday-asset://${"ab".repeat(32)}",
        )
    }
}
