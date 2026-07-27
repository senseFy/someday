package saien.someday.data.crypto

import saien.someday.data.local.Note
import saien.someday.data.local.NoteVersion
import saien.someday.data.local.SqlDelightLocalDataRepository

class WorkspaceProtectedLocalDataRepository(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKeys: WorkspaceKeyRepository,
) {
    fun getNote(
        noteId: String,
        includeDeleted: Boolean = false,
    ): PlaintextAccessResult<Note> =
        if (workspaceKeys.unlockedKeyOrNull() == null) {
            PlaintextAccessResult.Locked
        } else {
            localRepository.getNote(noteId, includeDeleted)
                ?.let { PlaintextAccessResult.Available(it) }
                ?: PlaintextAccessResult.Missing
        }

    fun listActiveNotes(notebookId: String): PlaintextAccessResult<List<Note>> =
        if (workspaceKeys.unlockedKeyOrNull() == null) {
            PlaintextAccessResult.Locked
        } else {
            PlaintextAccessResult.Available(localRepository.listActiveNotes(notebookId))
        }

    fun listNoteVersions(noteId: String): PlaintextAccessResult<List<NoteVersion>> =
        if (workspaceKeys.unlockedKeyOrNull() == null) {
            PlaintextAccessResult.Locked
        } else {
            PlaintextAccessResult.Available(localRepository.listNoteVersions(noteId))
        }

    fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): PlaintextAccessResult<Note> =
        if (workspaceKeys.unlockedKeyOrNull() == null) {
            PlaintextAccessResult.Locked
        } else {
            PlaintextAccessResult.Available(localRepository.restoreNoteVersion(noteId, versionId))
        }
}

sealed interface PlaintextAccessResult<out T> {
    data class Available<out T>(
        val value: T,
    ) : PlaintextAccessResult<T>

    data object Locked : PlaintextAccessResult<Nothing>

    data object Missing : PlaintextAccessResult<Nothing>
}
