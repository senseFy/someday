@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.ExportedLocation
import saien.someday.data.export.ExportedNote
import saien.someday.data.export.ExportedNotebook
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import kotlin.time.Clock
import kotlin.time.Instant

private data class WorkspaceSourceCandidateV2(
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val content: WorkspaceEntityContentV2?,
    val deletion: WorkspaceDeletionV2?,
    val sourceProfile: String,
    val sourceEpoch: String?,
    val sourceWriterId: String?,
    val sourceMutationId: String?,
    val sourceObjectId: String,
    val sourceDigest: String,
    val authoredAt: Instant,
)

private data class WorkspaceSourceImportCommitV2(
    val version: WorkspaceEntityVersionV2,
    val newlyQueued: Boolean,
)

private sealed interface WorkspaceSourceCommitResultV2 {
    data class Committed(val value: WorkspaceSourceImportCommitV2) : WorkspaceSourceCommitResultV2
    data class Blocked(val safeErrorCode: String, val safeMessage: String) : WorkspaceSourceCommitResultV2
}

class WorkspaceLocalDataTransferV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKeyProvider: () -> WorkspaceMasterKey?,
    private val writerDeviceIdProvider: () -> String,
    private val remoteProfileProvider: () -> String,
) {
    fun exportDocument(exportedAt: Instant): LocalDataExportDocument {
        val context = activeContext()
        require(context.store.loadActiveConflicts().isEmpty()) {
            "Resolve every V2 conflict before creating a single-snapshot local export."
        }
        val notebooks = context.store.loadProjections(WorkspaceEntityTypeV2.NOTEBOOK)
            .filter { it.status == WorkspaceProjectionStatusV2.CONTENT }
            .map { projection ->
                val head = requireNotNull(projection.preferredHeadVersionId?.let(context.store::loadVersion))
                val content = head.contentPayload as NotebookContentV2
                ExportedNotebook(
                    id = head.entityId,
                    title = content.title,
                    sortOrder = content.sortOrder,
                    createdAt = content.notebookCreatedAt.toString(),
                    updatedAt = head.authoredAt.toString(),
                )
            }
            .sortedWith(compareBy<ExportedNotebook> { it.sortOrder }.thenBy { it.id })
        val notes = context.store.loadProjections(WorkspaceEntityTypeV2.NOTE)
            .filter { it.status == WorkspaceProjectionStatusV2.CONTENT }
            .map { projection ->
                val head = requireNotNull(projection.preferredHeadVersionId?.let(context.store::loadVersion))
                val content = head.contentPayload as NoteContentV2
                ExportedNote(
                    id = head.entityId,
                    notebookId = content.notebookId,
                    title = content.title,
                    markdownBody = content.markdownBody,
                    excerpt = content.markdownBody.lineSequence().joinToString(" ").trim().take(180),
                    timeZoneId = content.timeZoneId,
                    createdAt = content.noteCreatedAt.toString(),
                    updatedAt = head.authoredAt.toString(),
                    revision = head.generation,
                    location = content.location?.let {
                        ExportedLocation(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyMeters = it.accuracyMeters,
                            altitudeMeters = it.altitudeMeters,
                            placeText = it.placeText,
                            capturedAt = it.capturedAt.toString(),
                        )
                    },
                    currentVersionId = head.versionId,
                    parentVersionId = head.parentVersionIds.singleOrNull(),
                    baseVersionId = null,
                    versionDeviceId = head.authorActorId,
                    mergeMetadataJson = head.mergeAlgorithmVersion,
                )
            }
            .sortedBy { it.id }
        return LocalDataExportDocument(
            exportedAt = exportedAt.toString(),
            notebooks = notebooks,
            notes = notes,
        )
    }

    fun importDocument(document: LocalDataExportDocument): LocalDataImportSummary {
        require(saien.someday.data.export.isSupportedLocalDataExportFormat(document.format))
        val context = activeContext()
        val committer = WorkspaceSourceImportCommitterV2(localRepository, context)
        val sourceProfile = "backup:${document.format}"
        val notebookIds = document.notebooks.associate { notebook ->
            notebook.id to mappedEntityId(context, WorkspaceEntityTypeV2.NOTEBOOK, notebook.id)
        }.toMutableMap()
        var notebooksCreated = 0
        var notebooksReused = 0
        var notesCreated = 0
        var notesUpdated = 0
        var notesMerged = 0
        var noteConflictsCreated = 0
        var notesSkipped = 0

        document.notebooks.sortedBy { it.id }.forEach { source ->
            val entityId = checkNotNull(notebookIds[source.id])
            val content = NotebookContentV2(
                source.title,
                source.sortOrder,
                Instant.parse(source.createdAt),
            )
            val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, entityId)
            val priorHeads = context.store.loadHeads(key)
            if (priorHeads.any { it.hasExactState(content, null) }) {
                notebooksReused++
                return@forEach
            }
            when (val committed = committer.import(
                backupCandidate(
                    context, WorkspaceEntityTypeV2.NOTEBOOK, entityId, content,
                    sourceProfile, "notebook:${source.id}", Instant.parse(source.updatedAt),
                ),
                verifiedParent = null,
            )) {
                is WorkspaceSourceCommitResultV2.Blocked -> error(committed.safeMessage)
                is WorkspaceSourceCommitResultV2.Committed -> if (committed.value.newlyQueued) {
                    if (priorHeads.isEmpty()) notebooksCreated++ else notebooksReused++
                } else {
                    notebooksReused++
                }
            }
        }

        document.notes.sortedBy { it.id }.forEach { source ->
            val entityId = mappedEntityId(context, WorkspaceEntityTypeV2.NOTE, source.id)
            val notebookId = notebookIds[source.notebookId]
                ?: mappedEntityId(context, WorkspaceEntityTypeV2.NOTEBOOK, source.notebookId).also {
                    notebookIds[source.notebookId] = it
                }
            val content = NoteContentV2(
                notebookId = notebookId,
                title = source.title,
                markdownBody = source.markdownBody,
                noteCreatedAt = Instant.parse(source.createdAt),
                timeZoneId = source.timeZoneId,
                location = source.location?.let {
                    NoteLocationV2(
                        it.latitude,
                        it.longitude,
                        it.placeText,
                        it.accuracyMeters,
                        it.altitudeMeters,
                        Instant.parse(it.capturedAt),
                    )
                },
            )
            val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, entityId)
            val priorHeads = context.store.loadHeads(key)
            if (priorHeads.any { it.hasExactState(content, null) }) {
                notesSkipped++
                return@forEach
            }
            val conflictsBefore = context.store.loadConflicts(key).count {
                it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE
            }
            when (val committed = committer.import(
                backupCandidate(
                    context, WorkspaceEntityTypeV2.NOTE, entityId, content,
                    sourceProfile, "note:${source.id}", Instant.parse(source.updatedAt),
                ),
                verifiedParent = null,
            )) {
                is WorkspaceSourceCommitResultV2.Blocked -> error(committed.safeMessage)
                is WorkspaceSourceCommitResultV2.Committed -> if (!committed.value.newlyQueued) {
                    notesSkipped++
                } else if (priorHeads.isEmpty()) {
                    notesCreated++
                } else {
                    val activeConflicts = context.store.loadConflicts(key).count {
                        it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE
                    }
                    if (activeConflicts > conflictsBefore) {
                        noteConflictsCreated++
                    } else {
                        val head = context.store.loadHeads(key).singleOrNull()
                        if (head?.mergeAlgorithmVersion == FIELD_MERGE_ALGORITHM_V2) notesMerged++ else notesUpdated++
                    }
                }
            }
        }
        return LocalDataImportSummary(
            notebooksCreated,
            notebooksReused,
            notesCreated,
            notesUpdated,
            notesMerged,
            noteConflictsCreated,
            notesSkipped,
        )
    }

    private fun activeContext(): ActiveWorkspaceSystemV2 = WorkspaceSystemV2ContextProvider(
        localRepository,
        workspaceKeyProvider,
        writerDeviceIdProvider,
        remoteProfileProvider,
    ).requireWritable()

    private fun mappedEntityId(
        context: ActiveWorkspaceSystemV2,
        entityType: WorkspaceEntityTypeV2,
        sourceId: String,
    ): String = if (sourceId.isWholeProductProtocolIdentifierV2()) {
        sourceId
    } else {
        context.materializer.mappedSourceEntityId(context.syncEpochId, entityType, sourceId)
    }

    private fun backupCandidate(
        context: ActiveWorkspaceSystemV2,
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        content: WorkspaceEntityContentV2,
        sourceProfile: String,
        sourceObjectId: String,
        authoredAt: Instant,
    ) = WorkspaceSourceCandidateV2(
        entityType,
        entityId,
        content,
        null,
        sourceProfile,
        null,
        context.writerDeviceId,
        null,
        sourceObjectId,
        context.materializer.payloadDigest(entityType, WorkspaceEntityVersionKindV2.CONTENT, content, null),
        authoredAt,
    )
}

private class WorkspaceSourceImportCommitterV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val context: ActiveWorkspaceSystemV2,
) {
    private val queries = localRepository.database.somedayQueries

    fun import(
        candidate: WorkspaceSourceCandidateV2,
        verifiedParent: WorkspaceEntityVersionV2?,
    ): WorkspaceSourceCommitResultV2 {
        val existing = queries.selectSourceImportSystemV2(
            context.remoteProfile,
            context.syncEpochId,
            candidate.sourceProfile,
            candidate.sourceObjectId,
            candidate.sourceDigest,
        ).executeAsOneOrNull()
        val importRecordId = existing?.source_mutation_id
            ?: candidate.sourceMutationId
            ?: context.factory.newMutationId()
        val provenance = WorkspaceVersionProvenanceV2(
            WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT,
            candidate.sourceProfile,
            candidate.sourceEpoch,
            candidate.sourceWriterId,
            importRecordId,
            candidate.sourceObjectId,
            candidate.sourceDigest,
        )
        val version = runCatching {
            context.factory.createSourceImport(
                candidate.entityType,
                candidate.entityId,
                candidate.content,
                candidate.deletion,
                provenance,
                candidate.authoredAt,
                verifiedParent,
            )
        }.getOrElse {
            return blocked("source_import_invalid", it.message ?: "Source state violates the frozen V2 schema.")
        }
        val mutationId = existing?.mutation_id ?: context.materializer.deterministicSystemMutationId(version)
        if (existing != null && (
                existing.version_id != version.versionId ||
                    existing.mapped_entity_id != candidate.entityId ||
                    existing.entity_type != candidate.entityType.wireValue
                )
        ) {
            return blocked("source_import_identity_mismatch", "A durable source is already mapped to another immutable V2 version.")
        }
        if (existing?.state == "published") {
            val stored = context.store.loadVersion(existing.version_id)
                ?: return blocked("source_import_object_missing", "A published source import is not retained locally.")
            if (stored != version) {
                return blocked("immutable_object_mismatch", "A published source import no longer matches its immutable version.")
            }
            return WorkspaceSourceCommitResultV2.Committed(WorkspaceSourceImportCommitV2(stored, false))
        }

        var commitResult: WorkspaceLocalCommitResultV2? = null
        var failure: WorkspaceSourceCommitResultV2.Blocked? = null
        localRepository.database.transaction {
            commitResult = context.store.commitLocalMutations(
                listOf(LocalWorkspaceMutationV2(
                    context.remoteProfile,
                    mutationId,
                    version,
                    candidate.authoredAt,
                )),
            )
            val committed = commitResult
            if (committed is WorkspaceLocalCommitResultV2.Rejected) {
                failure = blocked(committed.error.code.wireValue, committed.error.safeMessage)
                return@transaction
            }
            val durable = queries.selectSourceImportSystemV2(
                context.remoteProfile,
                context.syncEpochId,
                candidate.sourceProfile,
                candidate.sourceObjectId,
                candidate.sourceDigest,
            ).executeAsOneOrNull()
            if (durable == null) {
                queries.insertSourceImportSystemV2(
                    context.remoteProfile,
                    context.syncEpochId,
                    candidate.sourceProfile,
                    candidate.sourceEpoch,
                    candidate.sourceWriterId,
                    importRecordId,
                    candidate.sourceObjectId,
                    candidate.sourceDigest,
                    candidate.entityType.wireValue,
                    candidate.entityId,
                    candidate.entityId,
                    version.versionId,
                    mutationId,
                    "committed",
                    candidate.authoredAt.toEpochMilliseconds(),
                    null,
                )
            } else if (durable.version_id != version.versionId || durable.mutation_id != mutationId) {
                failure = blocked("source_import_identity_mismatch", "Source import identity changed inside its atomic commit.")
                rollback()
            }
        }
        failure?.let { return it }
        return WorkspaceSourceCommitResultV2.Committed(
            WorkspaceSourceImportCommitV2(
                version,
                commitResult is WorkspaceLocalCommitResultV2.Committed,
            ),
        )
    }

    private fun blocked(code: String, message: String) = WorkspaceSourceCommitResultV2.Blocked(
        code,
        message.safeSourceImportMessageV2(),
    )
}

private fun WorkspaceEntityVersionV2.hasExactState(
    content: WorkspaceEntityContentV2?,
    deletion: WorkspaceDeletionV2?,
): Boolean = when {
    content != null -> kind == WorkspaceEntityVersionKindV2.CONTENT && contentPayload == normalizeContentV2(content)
    deletion != null -> kind == WorkspaceEntityVersionKindV2.DELETION && deletionPayload == deletion
    else -> false
}

private fun String.safeSourceImportMessageV2(): String =
    replace(Regex("(?i)(bearer|token|password|secret|title|body|place)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .take(500)
