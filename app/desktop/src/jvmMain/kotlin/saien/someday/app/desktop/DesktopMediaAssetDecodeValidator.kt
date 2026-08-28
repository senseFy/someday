package saien.someday.app.desktop

import okio.BufferedSource
import org.jetbrains.compose.resources.decodeToImageBitmap
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.MediaAssetDecodeValidator

internal object DesktopMediaAssetDecodeValidator : MediaAssetDecodeValidator {
    override fun decode(source: BufferedSource): DecodedMediaAsset {
        val bitmap = source.readByteArray().decodeToImageBitmap()
        return DecodedMediaAsset(bitmap.width, bitmap.height)
    }
}
