package saien.someday.data.media

import okio.BufferedSource

data class DecodedMediaAsset(
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    init {
        require(pixelWidth > 0 && pixelHeight > 0) { "Decoded image dimensions must be positive." }
    }
}

/**
 * Platform boundary that must fully decode an encoded image before the store
 * promotes it. Metadata inspection remains responsible for applying the
 * bounded MIME, dimension, animation, and decoded-pixel rules first.
 */
fun interface MediaAssetDecodeValidator {
    fun decode(source: BufferedSource): DecodedMediaAsset
}
