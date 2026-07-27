package saien.someday.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorTextFieldSyncTest {
    @Test
    fun syncPreservesActiveImeCompositionWhenEditorTextAlreadyMatches() {
        val editor = editorState(markdownBody = "ni", selectionStart = 2, selectionEnd = 2)
        val composing = TextFieldValue(
            text = "ni",
            selection = TextRange(2, 2),
            composition = TextRange(0, 2),
        )

        assertEquals(composing, composing.syncedWithEditorMarkdown(editor))
    }

    @Test
    fun syncAppliesExternalTextChangesAndClearsComposition() {
        val editor = editorState(markdownBody = "**ni**", selectionStart = 2, selectionEnd = 4)
        val composing = TextFieldValue(
            text = "ni",
            selection = TextRange(2, 2),
            composition = TextRange(0, 2),
        )

        val synced = composing.syncedWithEditorMarkdown(editor)

        assertEquals("**ni**", synced.text)
        assertEquals(TextRange(2, 4), synced.selection)
        assertNull(synced.composition)
    }

    @Test
    fun syncDefersExternalSelectionChangesUntilCompositionEnds() {
        val editor = editorState(markdownBody = "ni", selectionStart = 0, selectionEnd = 2)
        val composing = TextFieldValue(
            text = "ni",
            selection = TextRange(2, 2),
            composition = TextRange(0, 2),
        )

        assertEquals(TextRange(2, 2), composing.syncedWithEditorMarkdown(editor).selection)

        val committed = composing.copy(composition = null)
        assertEquals(TextRange(0, 2), committed.syncedWithEditorMarkdown(editor).selection)
    }

    private fun editorState(
        markdownBody: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): NoteEditorState =
        NoteEditorState.newDraft(notebookId = "notebook", createdDateText = "2026-06-05").copy(
            markdownBody = markdownBody,
            markdownSelectionStart = selectionStart,
            markdownSelectionEnd = selectionEnd,
        )
}
