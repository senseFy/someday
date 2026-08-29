package saien.someday.app.android

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

internal data class AndroidImageOrientation(
    val rotationDegrees: Int = 0,
    val flipped: Boolean = false,
) {
    val swapsDimensions: Boolean = rotationDegrees == 90 || rotationDegrees == 270
}

internal fun ByteArray.androidImageOrientation(): AndroidImageOrientation = runCatching {
    val exif = ExifInterface(ByteArrayInputStream(this))
    AndroidImageOrientation(
        rotationDegrees = exif.rotationDegrees,
        flipped = exif.isFlipped,
    )
}.getOrDefault(AndroidImageOrientation())

internal fun Bitmap.applyImageOrientation(orientation: AndroidImageOrientation): Bitmap {
    if (orientation.rotationDegrees == 0 && !orientation.flipped) return this
    val matrix = Matrix().apply {
        if (orientation.flipped) postScale(-1f, 1f)
        if (orientation.rotationDegrees != 0) postRotate(orientation.rotationDegrees.toFloat())
    }
    val transformed = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (transformed !== this) recycle()
    return transformed
}
