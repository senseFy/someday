package saien.someday.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun noteEditorMarkdownFieldValue(editor: NoteEditorState): TextFieldValue =
    TextFieldValue(
        text = editor.markdownBody,
        selection = TextRange(
            editor.markdownSelectionStart.coerceIn(0, editor.markdownBody.length),
            editor.markdownSelectionEnd.coerceIn(0, editor.markdownBody.length),
        ),
    )

internal fun TextFieldValue.syncedWithEditorMarkdown(editor: NoteEditorState): TextFieldValue {
    val editorValue = noteEditorMarkdownFieldValue(editor)
    return when {
        text != editorValue.text -> editorValue
        composition != null -> this
        selection != editorValue.selection -> copy(selection = editorValue.selection)
        else -> this
    }
}
