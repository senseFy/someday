@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.notes.DeletedWorkspaceItem
import saien.someday.domain.notes.DeletedWorkspaceItemType
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.MemoryDayCount
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteBatchDeletion
import saien.someday.domain.notes.NoteBatchUndelete
import saien.someday.domain.notes.NoteBatchUpdate
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.NotebookConflictBranch
import saien.someday.domain.notes.NotebookConflictDetails
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notes.VersionConflictBranch
import saien.someday.domain.notes.noteCalendarDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Product-facing implementation of the closed whole-product V2 entity set. */
class SystemV2NotesRepository(
    private val localRepository: SqlDelightLocalDataRepository,
    workspaceKeyProvider: () -> WorkspaceMasterKey?,
    writerDeviceIdProvider: () -> String,
    remoteProfileProvider: () -> String,
    private val clock: () -> Instant = { Clock.System.now() },
    private val workspaceKeyForEpochProvider: (StoredSyncEpochV2) -> WorkspaceMasterKey? = {
        workspaceKeyProvider()
    },
) : NotesRepository {
    private val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    private val contexts = WorkspaceSystemV2ContextProvider(
        localRepository = localRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = writerDeviceIdProvider,
        remoteProfileProvider = remoteProfileProvider,
    )

    override fun listNotebooks(): List<NotebookSummary> {
        val context = contexts.openOrNull() ?: return emptyList()
        val notebooks = context.store.loadProjections(WorkspaceEntityTypeV2.NOTEBOOK)
            .mapNotNull { notebookView(context, it) }
            .sortedWith(compareBy<NotebookSummary> { it.sortOrder }.thenBy { it.id })
        val hasRecovery = context.store.hasUnresolvedNotebookNoteProjections()
        return if (hasRecovery) {
            notebooks + NotebookSummary(
                id = RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2,
                title = "Recovered notes",
                sortOrder = Long.MAX_VALUE,
                syncBadge = NoteSyncBadge.Error("Some notes reference a missing, deleted, or conflicted notebook."),
            )
        } else {
            notebooks
        }
    }

    override fun createNotebook(title: String): NotebookSummary {
        val context = contexts.requireActive()
        val now = clock()
        val entityId = context.factory.newEntityId()
        val maxSort = listNotebooks()
            .asSequence()
            .filter { it.id != RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2 }
            .maxOfOrNull { it.sortOrder }
        val sortOrder = when (maxSort) {
            null -> 1L
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> maxSort + 1L
        }
        val version = context.factory.createGenesis(
            entityType = WorkspaceEntityTypeV2.NOTEBOOK,
            entityId = entityId,
            content = NotebookContentV2(title.trim(), sortOrder, now),
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        commit(context, listOf(version to context.factory.newMutationId()), now)
        return requireNotNull(listNotebooks().firstOrNull { it.id == entityId })
    }

    override fun renameNotebook(notebookId: String, title: String): NotebookSummary =
        error("A V2 notebook rename requires the causal token returned with the notebook view.")

    override fun renameNotebook(
        notebookId: String,
        title: String,
        causalToken: CausalEditToken,
    ): NotebookSummary {
        val context = contexts.requireActive()
        val now = clock()
        val base = requireTokenBase(context, notebookKey(notebookId), causalToken)
        val content = base.contentPayload as? NotebookContentV2
            ?: error("Restore this notebook before renaming it.")
        val next = content.copy(title = title.trim())
        if (next == content) return requireNotNull(listNotebooks().firstOrNull { it.id == notebookId })
        commitTokenEdit(context, causalToken, next, deletedAt = null, now = now)
        return requireNotNull(listNotebooks().firstOrNull { it.id == notebookId })
    }

    override fun reorderNotebooks(edits: List<NotebookOrderEdit>): List<NotebookSummary> {
        if (edits.isEmpty()) return listNotebooks()
        require(edits.map { it.notebookId }.distinct().size == edits.size) { "A reorder batch repeats a notebook." }
        val context = contexts.requireActive()
        val now = clock()
        val versions = edits.mapNotNull { edit ->
            val base = requireTokenBase(context, notebookKey(edit.notebookId), edit.causalToken)
            val content = base.contentPayload as? NotebookContentV2
                ?: error("A deleted notebook cannot be reordered.")
            if (content.sortOrder == edit.sortOrder) null else {
                val result = createFromToken(context, edit.causalToken, content.copy(sortOrder = edit.sortOrder), null, now)
                result.version to result.mutationId
            }
        }
        if (versions.isNotEmpty()) commit(context, versions, now)
        return listNotebooks()
    }

    override fun deleteNotebook(notebookId: String) {
        error("A V2 notebook deletion requires the causal token returned with the notebook view.")
    }

    override fun deleteNotebook(notebookId: String, causalToken: CausalEditToken) {
        require(notebookId != RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2) { "Recovery Inbox is a local projection." }
        val context = contexts.requireActive()
        val key = notebookKey(notebookId)
        val base = requireTokenBase(context, key, causalToken)
        if (base.kind == WorkspaceEntityVersionKindV2.DELETION) return
        require(!hasLiveNoteReference(context, notebookId)) {
            "Move every live note out of this notebook before deleting it."
        }
        require(!hasEffectiveDefaultReference(context, notebookId)) {
            "Clear or change the default notebook before deleting it."
        }
        val now = clock()
        commitTokenEdit(context, causalToken, content = null, deletedAt = now, now = now)
    }

    override fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ): NotebookSummary {
        val context = contexts.requireActive()
        val base = requireTokenBase(context, notebookKey(notebookId), causalToken)
        require(base.kind == WorkspaceEntityVersionKindV2.DELETION) { "Notebook restore requires a deletion base." }
        val retained = context.store.loadVersion(retainedContentVersionId)
            ?.takeIf { it.key == base.key }
            ?.contentPayload as? NotebookContentV2
            ?: findRetainedContentVersion(base.key, retainedContentVersionId)?.contentPayload as? NotebookContentV2
            ?: error("The selected retained notebook content is unavailable.")
        val now = clock()
        commitTokenEdit(context, causalToken, retained, deletedAt = null, now = now)
        return requireNotNull(listNotebooks().firstOrNull { it.id == notebookId })
    }

    override fun listDeletedWorkspaceItems(): List<DeletedWorkspaceItem> {
        val context = contexts.openOrNull() ?: return emptyList()
        return context.store.loadEntityKeys()
            .asSequence()
            .filter { it.entityType == WorkspaceEntityTypeV2.NOTE || it.entityType == WorkspaceEntityTypeV2.NOTEBOOK }
            .mapNotNull { key ->
                val heads = context.store.loadHeads(key)
                val deletion = heads.singleOrNull()
                    ?.takeIf { it.kind == WorkspaceEntityVersionKindV2.DELETION }
                    ?: return@mapNotNull null
                val retained = findRetainedContentForDeletion(context, deletion)
                val title = when (val payload = retained?.contentPayload) {
                    is NoteContentV2 -> payload.title.ifBlank { "Untitled note" }
                    is NotebookContentV2 -> payload.title.ifBlank { "Untitled notebook" }
                    else -> if (key.entityType == WorkspaceEntityTypeV2.NOTE) "Deleted note" else "Deleted notebook"
                }
                DeletedWorkspaceItem(
                    entityId = key.entityId,
                    type = if (key.entityType == WorkspaceEntityTypeV2.NOTE) {
                        DeletedWorkspaceItemType.Note
                    } else {
                        DeletedWorkspaceItemType.Notebook
                    },
                    displayTitle = title,
                    deletedAt = checkNotNull(deletion.deletionPayload).deletedAt,
                    retainedContentVersionId = retained?.versionId,
                    causalToken = deletion.toDomainToken(context, conflict = null),
                )
            }
            .sortedWith(compareByDescending<DeletedWorkspaceItem> { it.deletedAt }.thenBy { it.entityId })
            .toList()
    }

    override fun getNotebookConflictDetails(notebookId: String): NotebookConflictDetails? {
        val context = contexts.openOrNull() ?: return null
        val conflict = activeConflict(context, notebookKey(notebookId)) ?: return null
        return NotebookConflictDetails(
            conflictId = conflict.descriptor.conflictId,
            notebookId = notebookId,
            expectedHeadVersionIds = conflict.descriptor.headVersionIds,
            branches = conflict.descriptor.headVersionIds.map { versionId ->
                val version = requireNotNull(context.store.loadVersion(versionId))
                val payload = version.contentPayload as? NotebookContentV2
                NotebookConflictBranch(
                    versionId = versionId,
                    title = payload?.title,
                    sortOrder = payload?.sortOrder,
                    deleted = version.kind == WorkspaceEntityVersionKindV2.DELETION,
                    updatedAt = version.authoredAt,
                )
            },
            conflictingFields = conflict.descriptor.conflictingFields,
        )
    }

    override fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): NotebookSummary? {
        val context = contexts.requireActive()
        val conflict = context.store.loadActiveConflicts().singleOrNull { it.descriptor.conflictId == conflictId }
            ?: error("The notebook conflict is no longer active.")
        val selected = resolveSelectedBranch(context, conflict, selectedVersionId, expectedHeadVersionIds)
        val now = clock()
        val chain = context.factory.createManualResolutionChain(
            parents = conflict.descriptor.headVersionIds.map { requireNotNull(context.store.loadVersion(it)) },
            selectedContent = selected.contentPayload,
            selectedDeletion = selected.deletionPayload,
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        commit(context, chain.map { it to context.factory.newMutationId() }, now)
        return listNotebooks().firstOrNull { it.id == conflict.descriptor.entityId }
    }

    override fun listNotes(notebookId: String): List<NoteSummary> {
        val context = contexts.openOrNull() ?: return emptyList()
        val effectiveNotebookId = if (notebookId == RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2) {
            RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2
        } else {
            notebookId
        }
        return noteSummariesFromListProjections(
            context = context,
            projections = context.store.loadContentNoteListProjectionsForEffectiveNotebook(effectiveNotebookId),
        )
    }

    override fun getNoteDetails(noteId: String): NoteDetails? {
        val context = contexts.openOrNull() ?: return null
        return projectedNoteView(context, noteId)?.toDetails(context)
    }

    override fun createNote(input: NoteInput): NoteDetails {
        val context = contexts.requireActive()
        val notebookId = requireWritableNotebook(context, input.notebookId)
        val now = clock()
        val noteId = context.factory.newEntityId()
        val content = input.toSystemContent(
            notebookId = notebookId,
            noteCreatedAt = input.createdAt ?: now,
            locationFallbackTime = now,
        )
        val version = context.factory.createGenesis(
            entityType = WorkspaceEntityTypeV2.NOTE,
            entityId = noteId,
            content = content,
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        commit(context, listOf(version to context.factory.newMutationId()), now)
        return requireNotNull(getNoteDetails(noteId))
    }

    override fun updateNote(noteId: String, input: NoteInput): NoteDetails {
        val token = input.causalToken
            ?: error("A V2 note save requires the causal token captured when the editor opened.")
        val context = contexts.requireActive()
        val base = requireTokenBase(context, noteKey(noteId), token)
        val current = base.contentPayload as? NoteContentV2
            ?: error("Use the explicit undelete action for a deleted note.")
        val notebookId = if (input.notebookId == RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2) {
            current.notebookId
        } else {
            requireWritableNotebook(context, input.notebookId)
        }
        val now = clock()
        val next = input.toSystemContent(
            notebookId = notebookId,
            noteCreatedAt = input.createdAt ?: current.noteCreatedAt,
            locationFallbackTime = now,
        )
        if (next == current) return requireNotNull(getNoteDetails(noteId))
        val created = commitTokenEdit(context, token, next, deletedAt = null, now = now)
        return getNoteDetails(noteId) ?: created.toTransientDetails(context)
    }

    override fun updateNotes(edits: List<NoteBatchUpdate>): List<NoteDetails> {
        require(edits.map { it.noteId }.distinct().size == edits.size) {
            "A note can only appear once in a batch update."
        }
        if (edits.isEmpty()) return emptyList()
        val context = contexts.requireActive()
        val now = clock()
        val versions = edits.mapNotNull { edit ->
            val token = edit.input.causalToken
                ?: error("A V2 note save requires the causal token captured when the note was loaded.")
            val base = requireTokenBase(context, noteKey(edit.noteId), token)
            val current = base.contentPayload as? NoteContentV2
                ?: error("Use the explicit undelete action for a deleted note.")
            val notebookId = if (edit.input.notebookId == RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2) {
                current.notebookId
            } else {
                requireWritableNotebook(context, edit.input.notebookId)
            }
            val next = edit.input.toSystemContent(
                notebookId = notebookId,
                noteCreatedAt = edit.input.createdAt ?: current.noteCreatedAt,
                locationFallbackTime = now,
            )
            if (next == current) null else createFromToken(context, token, next, deletedAt = null, now = now)
        }
        if (versions.isNotEmpty()) {
            commit(context, versions.map { it.version to it.mutationId }, now)
        }
        return edits.map { edit -> requireNotNull(getNoteDetails(edit.noteId)) }
    }

    override fun deleteNote(noteId: String) {
        error("A V2 note deletion requires the causal token returned with the note detail view.")
    }

    override fun deleteNote(noteId: String, causalToken: CausalEditToken) {
        val context = contexts.requireActive()
        val base = requireTokenBase(context, noteKey(noteId), causalToken)
        if (base.kind == WorkspaceEntityVersionKindV2.DELETION) return
        val now = clock()
        commitTokenEdit(context, causalToken, content = null, deletedAt = now, now = now)
    }

    override fun deleteNotes(deletions: List<NoteBatchDeletion>) {
        require(deletions.map { it.noteId }.distinct().size == deletions.size) {
            "A note can only appear once in a batch deletion."
        }
        if (deletions.isEmpty()) return
        val context = contexts.requireActive()
        val now = clock()
        val versions = deletions.mapNotNull { deletion ->
            val token = deletion.causalToken
                ?: error("A V2 note deletion requires the causal token returned with the note detail view.")
            val base = requireTokenBase(context, noteKey(deletion.noteId), token)
            if (base.kind == WorkspaceEntityVersionKindV2.DELETION) {
                null
            } else {
                createFromToken(context, token, content = null, deletedAt = now, now = now)
            }
        }
        if (versions.isNotEmpty()) {
            commit(context, versions.map { it.version to it.mutationId }, now)
        }
    }

    override fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ): NoteDetails {
        val context = contexts.requireActive()
        val base = requireTokenBase(context, noteKey(noteId), causalToken)
        require(base.kind == WorkspaceEntityVersionKindV2.DELETION) { "Note undelete requires a deletion base." }
        val retained = context.store.loadVersion(retainedContentVersionId)
            ?.takeIf { it.key == base.key }
            ?.contentPayload as? NoteContentV2
            ?: findRetainedContentVersion(base.key, retainedContentVersionId)?.contentPayload as? NoteContentV2
            ?: error("The selected retained note snapshot is unavailable.")
        val now = clock()
        val created = commitTokenEdit(context, causalToken, retained, deletedAt = null, now = now)
        return getNoteDetails(noteId) ?: created.toTransientDetails(context)
    }

    override fun undeleteNotes(restores: List<NoteBatchUndelete>): List<NoteDetails> {
        require(restores.map { it.noteId }.distinct().size == restores.size) {
            "A note can only appear once in a batch restore."
        }
        if (restores.isEmpty()) return emptyList()
        val context = contexts.requireActive()
        val now = clock()
        val versions = restores.map { restore ->
            val base = requireTokenBase(context, noteKey(restore.noteId), restore.causalToken)
            require(base.kind == WorkspaceEntityVersionKindV2.DELETION) {
                "Note undelete requires a deletion base."
            }
            val retained = context.store.loadVersion(restore.retainedContentVersionId)
                ?.takeIf { it.key == base.key }
                ?.contentPayload as? NoteContentV2
                ?: findRetainedContentVersion(base.key, restore.retainedContentVersionId)
                    ?.contentPayload as? NoteContentV2
                ?: error("The selected retained note snapshot is unavailable.")
            createFromToken(context, restore.causalToken, retained, deletedAt = null, now = now)
        }
        commit(context, versions.map { it.version to it.mutationId }, now)
        return restores.map { restore -> requireNotNull(getNoteDetails(restore.noteId)) }
    }

    override fun listNoteVersions(noteId: String): List<NoteVersionSummary> {
        val context = contexts.requireActive()
        val key = noteKey(noteId)
        val current = context.store.loadVersions(key).map { context.syncEpochId to it }
        val retained = retainedEpochContexts().flatMap { retainedContext ->
            retainedContext.store.loadVersions(key).map { retainedContext.syncEpochId to it }
        }
        val candidates = (current + retained)
            .filter { (_, version) -> version.contentPayload is NoteContentV2 }
        require(candidates.groupBy { it.second.versionId }.none { (_, versions) ->
            versions.map { it.second.objectDigest }.distinct().size > 1
        }) { "A retained V2 history id is ambiguous across epochs." }
        return candidates
            .distinctBy { it.second.versionId }
            .sortedWith(compareBy<Pair<String, WorkspaceEntityVersionV2>>(
                { it.second.authoredAt },
                { it.second.generation },
                { it.first },
                { it.second.versionId },
            ))
            .map { (epochId, version) ->
                version.toNoteVersionSummary(
                    retainedEpochId = epochId.takeUnless { it == context.syncEpochId },
                )
            }
    }

    override fun restoreNoteVersion(noteId: String, versionId: String): NoteDetails =
        error("A V2 history restore requires the causal token returned with the note detail view.")

    override fun restoreNoteVersion(
        noteId: String,
        versionId: String,
        causalToken: CausalEditToken,
    ): NoteDetails {
        val context = contexts.requireActive()
        val currentVersion = requireTokenBase(context, noteKey(noteId), causalToken)
        val current = currentVersion.contentPayload as? NoteContentV2
            ?: error("History restore is distinct from undelete; choose the explicit undelete action.")
        val historical = context.store.loadVersion(versionId)
            ?.takeIf { it.key == currentVersion.key }
            ?.contentPayload as? NoteContentV2
            ?: findRetainedContentVersion(currentVersion.key, versionId)?.contentPayload as? NoteContentV2
            ?: error("The selected V2 history snapshot is unavailable.")
        // Product history restore intentionally copies only title/body.  Every
        // other current field, including the atomic location, is retained.
        val restored = current.copy(title = historical.title, markdownBody = historical.markdownBody)
        if (restored == current) return requireNotNull(getNoteDetails(noteId))
        val now = clock()
        val created = commitTokenEdit(context, causalToken, restored, deletedAt = null, now = now)
        return getNoteDetails(noteId) ?: created.toTransientDetails(context)
    }

    override fun getConflictDetails(noteId: String): ConflictDetails? {
        val context = contexts.openOrNull() ?: return null
        val conflict = context.store.loadActiveConflicts().firstOrNull {
            it.descriptor.entityType == WorkspaceEntityTypeV2.NOTE &&
                (it.descriptor.entityId == noteId || it.descriptor.conflictId == noteId)
        } ?: return null
        return conflict.toNoteConflictDetails(context)
    }

    override fun getConflictDetailsForOriginal(originalNoteId: String): ConflictDetails? =
        getConflictDetails(originalNoteId)

    override fun resolveConflict(
        conflictNoteId: String,
        action: ConflictResolutionAction,
    ): NoteDetails? = error(
        "Whole-product V2 has no original/conflict-copy roles. Select an exact immutable branch or submit an explicit full resolution.",
    )

    override fun resolveConflictBranch(conflictNoteId: String, versionId: String): NoteDetails? =
        error("A V2 conflict resolution requires the exact expected head set displayed by the conflict view.")

    override fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ): NoteDetails? {
        val context = contexts.requireActive()
        val conflict = context.store.loadActiveConflicts().singleOrNull {
            it.descriptor.entityType == WorkspaceEntityTypeV2.NOTE &&
                (it.descriptor.conflictId == conflictNoteId || it.descriptor.entityId == conflictNoteId)
        } ?: error("The note conflict is no longer active.")
        val selected = resolveSelectedBranch(context, conflict, versionId, expectedHeadVersionIds)
        val now = clock()
        val chain = context.factory.createManualResolutionChain(
            parents = conflict.descriptor.headVersionIds.map { requireNotNull(context.store.loadVersion(it)) },
            selectedContent = selected.contentPayload,
            selectedDeletion = selected.deletionPayload,
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        commit(context, chain.map { it to context.factory.newMutationId() }, now)
        return getNoteDetails(conflict.descriptor.entityId)
    }

    override fun listMemoryDayCounts(month: MemoryMonth): List<MemoryDayCount> =
        contentNoteListProjections()
            .map { noteCalendarDate(it.noteCreatedAt, it.timeZoneId) }
            .filter(month::contains)
            .groupingBy { it }
            .eachCount()
            .map { (date, count) -> MemoryDayCount(date, count) }
            .sortedBy { it.date.toString() }

    override fun listActiveNoteDates(): List<LocalDate> =
        contentNoteListProjections()
            .map { noteCalendarDate(it.noteCreatedAt, it.timeZoneId) }

    override fun listNotesForDate(date: LocalDate): List<NoteSummary> {
        val context = contexts.openOrNull() ?: return emptyList()
        return noteSummariesFromListProjections(
            context = context,
            projections = context.store.loadContentNoteListProjections().filter {
                noteCalendarDate(it.noteCreatedAt, it.timeZoneId) == date
            },
        )
    }

    override fun listPriorYearNotesForDate(date: LocalDate): List<NoteSummary> {
        val context = contexts.openOrNull() ?: return emptyList()
        return noteSummariesFromListProjections(
            context = context,
            projections = context.store.loadContentNoteListProjections().filter {
                val candidate = noteCalendarDate(it.noteCreatedAt, it.timeZoneId)
                candidate < date && candidate.month == date.month && candidate.day == date.day
            },
        )
    }

    override fun searchNotes(query: String): List<NoteSummary> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        val context = contexts.openOrNull() ?: return emptyList()
        return noteSummariesFromListProjections(
            context = context,
            projections = context.store.loadContentNoteListProjections().filter {
                it.title.lowercase().contains(normalized) ||
                    it.markdownBody.lowercase().contains(normalized) ||
                    it.locationPlaceText?.lowercase()?.contains(normalized) == true
            },
        )
    }

    private fun contentNoteListProjections(): List<StoredNoteListProjectionV2> {
        val context = contexts.openOrNull() ?: return emptyList()
        return context.store.loadContentNoteListProjections()
    }

    /**
     * Diary list order is journal [NoteSummary.createdAt] (noteCreatedAt), newest first.
     * Version authoredAt remains available as [NoteSummary.updatedAt] for sync/display of last write,
     * but must not drive the notebook timeline.
     */
    private fun noteSummariesFromListProjections(
        context: ActiveWorkspaceSystemV2,
        projections: List<StoredNoteListProjectionV2>,
    ): List<NoteSummary> {
        if (projections.isEmpty()) return emptyList()
        val pendingObjectIds = context.store.loadPendingObjectIds(context.remoteProfile)
        val conflictReasons = context.store.loadActiveConflicts()
            .asSequence()
            .filter { it.descriptor.entityType == WorkspaceEntityTypeV2.NOTE }
            .associate { it.descriptor.entityId to it.descriptor.reason.wireValue }
        return projections
            .map { projection ->
                projection.toSummary(
                    pending = projection.preferredHeadVersionId != null &&
                        projection.preferredHeadVersionId in pendingObjectIds,
                    conflictReason = conflictReasons[projection.noteId],
                )
            }
            .sortedWith(NOTE_LIST_COMPARATOR_V2)
    }

    private fun StoredNoteListProjectionV2.toSummary(
        pending: Boolean,
        conflictReason: String?,
    ): NoteSummary = NoteSummary(
        id = noteId,
        notebookId = effectiveNotebookId
            ?: referencedNotebookId
            ?: RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2,
        title = title,
        excerpt = markdownBody.lineSequence().joinToString(" ").trim().take(180),
        createdAt = noteCreatedAt,
        updatedAt = authoredAt ?: noteCreatedAt,
        syncBadge = when {
            conflictReason != null -> NoteSyncBadge.Conflict(conflictReason)
            pending -> NoteSyncBadge.Pending
            warning != null -> NoteSyncBadge.Error(warning)
            else -> NoteSyncBadge.Synced
        },
        timeZoneId = timeZoneId,
    )

    private fun retainedEpochContexts(): List<ActiveWorkspaceSystemV2> =
        protocolStore.loadAllEpochs()
            .asSequence()
            .filter { it.lifecycle == SyncEpochLifecycleV2.READ_ONLY }
            .sortedByDescending { it.descriptor.createdAt }
            .mapNotNull { epoch ->
                val key = workspaceKeyForEpochProvider(epoch) ?: return@mapNotNull null
                contexts.openRetainedEpochOrNull(epoch, key)
            }
            .toList()

    private fun findRetainedContentVersion(
        key: WorkspaceEntityKeyV2,
        versionId: String,
    ): WorkspaceEntityVersionV2? {
        val matches = retainedEpochContexts().mapNotNull { retained ->
            retained.store.loadVersion(versionId)?.takeIf {
                it.key == key && it.kind == WorkspaceEntityVersionKindV2.CONTENT
            }
        }
        require(matches.map { it.objectDigest }.distinct().size <= 1) {
            "A retained V2 version id is ambiguous across epochs."
        }
        return matches.firstOrNull()
    }

    /**
     * Finds the last complete content snapshot in exact deletion ancestry.
     * A checkpoint deletion root has no parents in the new epoch, so its
     * authenticated provenance is followed into retained read-only epochs.
     * No old id is ever attached as a new-epoch parent.
     */
    private fun findRetainedContentForDeletion(
        context: ActiveWorkspaceSystemV2,
        deletion: WorkspaceEntityVersionV2,
    ): WorkspaceEntityVersionV2? {
        val retainedByEpoch = retainedEpochContexts().associateBy { it.syncEpochId }
        val seen = mutableSetOf<Pair<String, String>>()

        fun search(
            epochContext: ActiveWorkspaceSystemV2,
            start: WorkspaceEntityVersionV2,
        ): WorkspaceEntityVersionV2? {
            if (!seen.add(epochContext.syncEpochId to start.versionId)) return null
            val versions = epochContext.store.loadVersions(start.key).associateBy { it.versionId }
            val reachable = mutableListOf<WorkspaceEntityVersionV2>()
            val queue = ArrayDeque<String>().apply { add(start.versionId) }
            val visited = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                val version = versions[id] ?: continue
                if (version.kind == WorkspaceEntityVersionKindV2.CONTENT) reachable += version
                version.parentVersionIds.forEach(queue::addLast)
            }
            reachable.maxWithOrNull(
                compareBy<WorkspaceEntityVersionV2>({ it.generation }, { it.authoredAt }, { it.versionId }),
            )?.let { return it }

            val provenance = start.provenance
                ?.takeIf { it.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT }
                ?: return null
            val sourceEpoch = provenance.sourceEpoch ?: return null
            val sourceId = provenance.sourceObjectId ?: return null
            val prior = retainedByEpoch[sourceEpoch] ?: return null
            val priorVersion = prior.store.loadVersion(sourceId)
                ?.takeIf { it.key == start.key }
                ?: return null
            return search(prior, priorVersion)
        }

        return search(context, deletion)
    }

    private fun projectedNoteView(context: ActiveWorkspaceSystemV2, noteId: String): SystemV2NoteView? {
        val key = noteKey(noteId)
        val projection = context.store.loadProjection(key) ?: return null
        val conflict = activeConflict(context, key)
        val head = when (projection.status) {
            WorkspaceProjectionStatusV2.CONTENT -> projection.preferredHeadVersionId?.let(context.store::loadVersion)
            WorkspaceProjectionStatusV2.DELETION -> null
            WorkspaceProjectionStatusV2.CONFLICT -> context.store.loadHeads(key)
                .filter { it.contentPayload is NoteContentV2 }
                .sortedBy { it.versionId }
                .firstOrNull()
        } ?: return null
        val content = head.contentPayload as? NoteContentV2 ?: return null
        val pending = context.store.loadPendingObjectIds(context.remoteProfile).contains(head.versionId)
        val badge = when {
            conflict != null -> NoteSyncBadge.Conflict(conflict.descriptor.reason.wireValue)
            pending -> NoteSyncBadge.Pending
            projection.warning != null -> NoteSyncBadge.Error(projection.warning)
            else -> NoteSyncBadge.Synced
        }
        return SystemV2NoteView(
            version = head,
            content = content,
            unresolvedNotebook = projection.warning == "unresolved_notebook_reference" ||
                !isNotebookLive(context, content.notebookId),
            conflict = conflict,
            badge = badge,
        )
    }

    private fun notebookView(
        context: ActiveWorkspaceSystemV2,
        projection: WorkspaceProjectionSnapshotV2,
    ): NotebookSummary? {
        val conflict = activeConflict(context, projection.key)
        val head = when (projection.status) {
            WorkspaceProjectionStatusV2.CONTENT -> projection.preferredHeadVersionId?.let(context.store::loadVersion)
            WorkspaceProjectionStatusV2.DELETION -> null
            WorkspaceProjectionStatusV2.CONFLICT -> context.store.loadHeads(projection.key)
                .filter { it.contentPayload is NotebookContentV2 }
                .sortedBy { it.versionId }
                .firstOrNull()
        } ?: return null
        val content = head.contentPayload as? NotebookContentV2 ?: return null
        val pending = context.store.loadPendingObjectIds(context.remoteProfile).contains(head.versionId)
        return NotebookSummary(
            id = projection.key.entityId,
            title = content.title,
            sortOrder = content.sortOrder,
            syncBadge = when {
                conflict != null -> NoteSyncBadge.Conflict(conflict.descriptor.reason.wireValue)
                pending -> NoteSyncBadge.Pending
                else -> NoteSyncBadge.Synced
            },
            causalToken = head.toDomainToken(context, conflict),
        )
    }

    private fun commitTokenEdit(
        context: ActiveWorkspaceSystemV2,
        token: CausalEditToken,
        content: WorkspaceEntityContentV2?,
        deletedAt: Instant?,
        now: Instant,
    ): WorkspaceEntityVersionV2 {
        val created = createFromToken(context, token, content, deletedAt, now)
        commit(context, listOf(created.version to created.mutationId), now)
        return created.version
    }

    private fun createFromToken(
        context: ActiveWorkspaceSystemV2,
        token: CausalEditToken,
        content: WorkspaceEntityContentV2?,
        deletedAt: Instant?,
        now: Instant,
    ): TokenBasedVersionResultV2.Created {
        val base = requireNotNull(context.store.loadVersion(token.expectedBaseVersionId))
        val result = context.factory.createFromToken(
            token = token.toSystemToken(),
            retainedVersions = mapOf(base.versionId to base),
            content = content,
            deletedAt = deletedAt,
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        return result as? TokenBasedVersionResultV2.Created
            ?: error((result as TokenBasedVersionResultV2.Rejected).error.safeMessage)
    }

    private fun commit(
        context: ActiveWorkspaceSystemV2,
        versions: List<Pair<WorkspaceEntityVersionV2, String>>,
        now: Instant,
    ) {
        versions.forEach { context.wireCodec.encode(it.first) }
        when (val result = context.store.commitLocalMutations(versions.map { (version, mutationId) ->
            LocalWorkspaceMutationV2(context.remoteProfile, mutationId, version, now)
        })) {
            is WorkspaceLocalCommitResultV2.Committed,
            is WorkspaceLocalCommitResultV2.AlreadyCommitted,
            -> Unit
            is WorkspaceLocalCommitResultV2.Rejected -> error(result.error.safeMessage)
        }
    }

    private fun requireTokenBase(
        context: ActiveWorkspaceSystemV2,
        key: WorkspaceEntityKeyV2,
        token: CausalEditToken,
    ): WorkspaceEntityVersionV2 {
        require(token.syncEpochId == context.syncEpochId && token.entityType == key.entityType.wireValue &&
            token.entityId == key.entityId
        ) { "The edit token does not belong to this V2 entity or epoch." }
        val active = activeConflict(context, key)
        require(active == null && token.activeConflictId == null) {
            "Resolve the active V2 conflict before an ordinary mutation."
        }
        return context.store.loadVersion(token.expectedBaseVersionId)
            ?.takeIf { it.key == key }
            ?: error("The exact viewed edit base is no longer retained; reload without discarding the draft.")
    }

    private fun resolveSelectedBranch(
        context: ActiveWorkspaceSystemV2,
        conflict: WorkspaceConflictStateV2,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): WorkspaceEntityVersionV2 {
        val expected = expectedHeadVersionIds.distinct().sorted()
        require(expected.isNotEmpty() && expected == conflict.descriptor.headVersionIds) {
            "The conflict head set changed; reload before resolving it."
        }
        require(selectedVersionId in expected) { "The selected branch is not an active conflict head." }
        return requireNotNull(context.store.loadVersion(selectedVersionId))
    }

    private fun hasLiveNoteReference(context: ActiveWorkspaceSystemV2, notebookId: String): Boolean =
        context.store.loadEntityKeys()
            .filter { it.entityType == WorkspaceEntityTypeV2.NOTE }
            .any { key ->
                context.store.loadHeads(key).any {
                    (it.contentPayload as? NoteContentV2)?.notebookId == notebookId
                }
            }

    private fun hasEffectiveDefaultReference(context: ActiveWorkspaceSystemV2, notebookId: String): Boolean {
        val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES, WORKSPACE_PREFERENCES_ENTITY_ID_V2)
        val projection = context.store.loadProjection(key) ?: return false
        return projection.effectiveEntityId == notebookId
    }

    private fun requireWritableNotebook(context: ActiveWorkspaceSystemV2, notebookId: String): String {
        require(notebookId != RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2) {
            "Choose a live notebook rather than the local Recovery Inbox."
        }
        require(isNotebookLive(context, notebookId)) { "The target notebook is missing, deleted, or conflicted." }
        return notebookId
    }

    private fun isNotebookLive(context: ActiveWorkspaceSystemV2, notebookId: String): Boolean {
        val heads = context.store.loadHeads(notebookKey(notebookId))
        return heads.size == 1 && heads.single().kind == WorkspaceEntityVersionKindV2.CONTENT
    }

    private fun activeConflict(
        context: ActiveWorkspaceSystemV2,
        key: WorkspaceEntityKeyV2,
    ): WorkspaceConflictStateV2? = context.store.loadConflicts(key)
        .singleOrNull { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }
}

private data class SystemV2NoteView(
    val version: WorkspaceEntityVersionV2,
    val content: NoteContentV2,
    val unresolvedNotebook: Boolean,
    val conflict: WorkspaceConflictStateV2?,
    val badge: NoteSyncBadge,
) {
    fun toSummary(): NoteSummary = NoteSummary(
        id = version.entityId,
        notebookId = if (unresolvedNotebook) RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2 else content.notebookId,
        title = content.title,
        excerpt = content.markdownBody.lineSequence().joinToString(" ").trim().take(180),
        createdAt = content.noteCreatedAt,
        updatedAt = version.authoredAt,
        syncBadge = badge,
        timeZoneId = content.timeZoneId,
    )

    fun toDetails(context: ActiveWorkspaceSystemV2): NoteDetails = NoteDetails(
        id = version.entityId,
        notebookId = if (unresolvedNotebook) RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2 else content.notebookId,
        title = content.title,
        markdownBody = content.markdownBody,
        createdAt = content.noteCreatedAt,
        updatedAt = version.authoredAt,
        location = content.location.toDomainLocation(),
        syncBadge = badge,
        timeZoneId = content.timeZoneId,
        causalToken = version.toDomainToken(context, conflict),
    )
}

private fun WorkspaceEntityVersionV2.toTransientDetails(context: ActiveWorkspaceSystemV2): NoteDetails {
    val content = contentPayload as? NoteContentV2 ?: error("A deletion has no note detail payload.")
    val conflict = context.store.loadConflicts(key).singleOrNull { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }
    return NoteDetails(
        id = entityId,
        notebookId = content.notebookId,
        title = content.title,
        markdownBody = content.markdownBody,
        createdAt = content.noteCreatedAt,
        updatedAt = authoredAt,
        location = content.location.toDomainLocation(),
        syncBadge = if (conflict == null) NoteSyncBadge.Pending else NoteSyncBadge.Conflict(conflict.descriptor.reason.wireValue),
        timeZoneId = content.timeZoneId,
        causalToken = toDomainToken(context, conflict),
    )
}

private fun WorkspaceEntityVersionV2.toDomainToken(
    context: ActiveWorkspaceSystemV2,
    conflict: WorkspaceConflictStateV2?,
): CausalEditToken = CausalEditToken(
    syncEpochId = syncEpochId,
    entityType = entityType.wireValue,
    entityId = entityId,
    expectedBaseVersionId = versionId,
    activeConflictId = conflict?.descriptor?.conflictId,
)

private fun CausalEditToken.toSystemToken(): WorkspaceCausalEditTokenV2 = WorkspaceCausalEditTokenV2(
    syncEpochId = syncEpochId,
    entityType = WorkspaceEntityTypeV2.fromWire(entityType)
        ?: error("The edit token has an unsupported entity type."),
    entityId = entityId,
    expectedBaseVersionId = expectedBaseVersionId,
    activeConflictId = activeConflictId,
)

private fun NoteInput.toSystemContent(
    notebookId: String,
    noteCreatedAt: Instant,
    locationFallbackTime: Instant,
): NoteContentV2 = NoteContentV2(
    notebookId = notebookId,
    title = title,
    markdownBody = markdownBody,
    noteCreatedAt = noteCreatedAt,
    timeZoneId = timeZoneId,
    location = location.toSystemLocation(locationFallbackTime),
)

private fun NotesLocationInput?.toSystemLocation(fallbackTime: Instant): NoteLocationV2? {
    val value = this?.takeIf(NotesLocationInput::hasValue) ?: return null
    return NoteLocationV2(
        latitude = value.latitude,
        longitude = value.longitude,
        placeText = value.placeText?.takeUnless(String::isBlank),
        accuracyMeters = value.accuracyMeters,
        altitudeMeters = value.altitudeMeters,
        capturedAt = value.capturedAt ?: fallbackTime,
    ).normalized()
}

private fun NoteLocationV2?.toDomainLocation(): NotesLocationInput? = this?.let {
    NotesLocationInput(
        latitude = it.latitude,
        longitude = it.longitude,
        placeText = it.placeText,
        accuracyMeters = it.accuracyMeters,
        altitudeMeters = it.altitudeMeters,
        capturedAt = it.capturedAt,
    )
}

/** Notebook timeline: journal createdAt desc, then stable id asc (not version authoredAt). */
private val NOTE_LIST_COMPARATOR_V2 =
    compareByDescending<NoteSummary> { it.createdAt }
        .thenBy { it.id }

private fun WorkspaceEntityVersionV2.toNoteVersionSummary(retainedEpochId: String? = null): NoteVersionSummary {
    val content = contentPayload as NoteContentV2
    return NoteVersionSummary(
        versionId = versionId,
        noteId = entityId,
        parentVersionId = parentVersionIds.firstOrNull(),
        baseVersionId = null,
        revision = generation,
        title = content.title,
        markdownBody = content.markdownBody,
        contentHash = payloadDigest,
        deviceId = authorActorId,
        mergeMetadata = retainedEpochId?.let { epoch ->
            listOfNotNull("retained-v2:$epoch", mergeAlgorithmVersion).joinToString(";")
        } ?: mergeAlgorithmVersion,
        createdAt = authoredAt,
    )
}

private fun WorkspaceConflictStateV2.toNoteConflictDetails(context: ActiveWorkspaceSystemV2): ConflictDetails {
    val versions = context.store.loadVersions(descriptor.let { WorkspaceEntityKeyV2(it.entityType, it.entityId) })
        .associateBy { it.versionId }
    val branches = descriptor.headVersionIds.map { headId ->
        val head = requireNotNull(versions[headId])
        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(headId) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!reachable.add(id)) continue
            versions[id]?.parentVersionIds?.forEach(queue::addLast)
        }
        val headContent = head.contentPayload as? NoteContentV2
        val history = ConflictHistory(
            noteId = descriptor.entityId,
            title = headContent?.title ?: "Deleted branch",
            versions = versions.values
                .filter { it.versionId in reachable && it.contentPayload is NoteContentV2 }
                .sortedWith(compareBy<WorkspaceEntityVersionV2> { it.generation }.thenBy { it.versionId })
                .map(WorkspaceEntityVersionV2::toNoteVersionSummary),
        )
        VersionConflictBranch(
            versionId = headId,
            history = history,
            deleted = head.kind == WorkspaceEntityVersionKindV2.DELETION,
            authorDeviceId = head.authorActorId,
            updatedAt = head.authoredAt,
        )
    }
    val first = branches.first()
    val second = branches.getOrElse(1) { first }
    return ConflictDetails(
        conflictNoteId = descriptor.conflictId,
        originalNoteId = descriptor.entityId,
        originalHistory = first.history,
        conflictHistory = second.history,
        sourceDeviceId = second.authorDeviceId,
        sourceUpdatedAt = branches.drop(1).maxOfOrNull { it.updatedAt },
        availableActions = emptyList(),
        versionBranches = branches,
        expectedHeadVersionIds = descriptor.headVersionIds,
    )
}

private fun noteKey(noteId: String) = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)
private fun notebookKey(notebookId: String) = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, notebookId)
