package saien.someday.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun recoveryCodeClipEntry(value: String, label: String): ClipEntry =
    ClipEntry(StringSelection(value))
