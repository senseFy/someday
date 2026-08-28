package saien.someday.app.android

import android.graphics.BitmapFactory
import okio.BufferedSource
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.MediaAssetDecodeValidator

internal object AndroidMediaAssetDecodeValidator : MediaAssetDecodeValidator {
    override fun decode(source: BufferedSource): DecodedMediaAsset {
        val bitmap = checkNotNull(BitmapFactory.decodeStream(source.inputStream())) {
            "Android image decoder rejected the encoded image."
        }
        return try {
            DecodedMediaAsset(bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
