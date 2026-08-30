package saien.someday.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun recoveryCodeClipEntry(value: String, label: String): ClipEntry =
    ClipEntry.withPlainText(value)
