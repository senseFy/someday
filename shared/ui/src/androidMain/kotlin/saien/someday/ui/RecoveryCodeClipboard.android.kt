package saien.someday.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry

internal actual fun recoveryCodeClipEntry(value: String, label: String): ClipEntry {
    val clipData = ClipData.newPlainText(label, value)
    clipData.description.extras = PersistableBundle().apply {
        putBoolean(SENSITIVE_CLIP_EXTRA, true)
    }
    return ClipEntry(clipData)
}

// This is ClipDescription.EXTRA_IS_SENSITIVE. Using the protocol value keeps
// the hint available on Android 26–32 without referencing the API 33 field.
private const val SENSITIVE_CLIP_EXTRA = "android.content.extra.IS_SENSITIVE"
