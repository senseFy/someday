package saien.someday.app.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.AccountDataResetPhase

class DesktopClientRepositoriesTest {
    @Test
    fun isolatedProductionGraphExposesResetAndKeepsWriterAndNotesAfterReopen() {
        val home = Files.createTempDirectory("someday-desktop-graph-")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.toString())
            val store = DesktopSelfHostedSessionCredentialStore(home.resolve("credentials"), keychain = null)
            val writer: String
            val workspace: String
            val noteId: String
            createDesktopClientRepositories(store).use { repositories ->
                assertEquals(AccountDataResetPhase.Unavailable, repositories.accountDataResetManager.load().phase)
                val snapshot = repositories.workspaceProductAccess.capture()
                writer = snapshot.writerDeviceId
                workspace = snapshot.workspaceId
                val notebook = repositories.notesRepository.createNotebook("Isolated graph")
                noteId = repositories.notesRepository.createNote(NoteInput(notebook.id, "Local note", "Saved in the task profile")).id
                assertTrue(repositories.workspaceProductAccess.isCurrent(snapshot))
            }
            createDesktopClientRepositories(store).use { reopened ->
                val snapshot = reopened.workspaceProductAccess.capture()
                assertEquals(writer, snapshot.writerDeviceId)
                assertEquals(workspace, snapshot.workspaceId)
                assertEquals("Saved in the task profile", assertNotNull(reopened.notesRepository.getNoteDetails(noteId)).markdownBody)
                assertEquals(AccountDataResetPhase.Unavailable, reopened.accountDataResetManager.load().phase)
            }
        } finally {
            System.setProperty("user.home", originalHome)
            home.toFile().deleteRecursively()
        }
    }
}
