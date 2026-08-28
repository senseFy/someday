package saien.someday.app.ios

import okio.BufferedSource
import org.jetbrains.compose.resources.decodeToImageBitmap
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.MediaAssetDecodeValidator

internal object IosMediaAssetDecodeValidator : MediaAssetDecodeValidator {
    override fun decode(source: BufferedSource): DecodedMediaAsset {
        val bitmap = source.readByteArray().decodeToImageBitmap()
        return DecodedMediaAsset(bitmap.width, bitmap.height)
    }
}
