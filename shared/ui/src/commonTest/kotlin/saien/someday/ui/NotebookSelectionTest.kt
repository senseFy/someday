package saien.someday.ui

import saien.someday.domain.settings.ClientSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotebookSelectionTest {
    @Test
    fun initialNotebookPrefersLastSelectedBeforeDefaultNotebook() {
        assertEquals(
            "last-notebook",
            initialSelectedNotebookId(
                ClientSettings(
                    defaultNotebookId = "default-notebook",
                    lastSelectedNotebookId = "last-notebook",
                ),
            ),
        )
        assertEquals(
            "default-notebook",
            initialSelectedNotebookId(ClientSettings(defaultNotebookId = "default-notebook")),
        )
        assertNull(initialSelectedNotebookId(ClientSettings()))
    }
}
