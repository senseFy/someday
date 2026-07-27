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

sealed interface WorkspaceJoiningImportResultV2 {
    data class Captured(val importedVersions: Int) : WorkspaceJoiningImportResultV2
    data class Blocked(val safeErrorCode: String, val safeMessage: String) : WorkspaceJoiningImportResultV2
}

sealed interface WorkspacePriorEpochImportResultV2 {
    data class Imported(val importedVersions: Int, val checkpointedPendingVersions: Int) : WorkspacePriorEpochImportResultV2
    data class Blocked(val safeErrorCode: String, val safeMessage: String) : WorkspacePriorEpochImportResultV2
}

sealed interface WorkspacePriorEpochRemoteImportResultV2 {
    data class Imported(
        val importedVersions: Int,
        val appliedUnits: Int,
        val observedObjects: Int,
    ) : WorkspacePriorEpochRemoteImportResultV2

    data class Blocked(
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspacePriorEpochRemoteImportResultV2
}

sealed interface WorkspaceSourceSnapshotImportResultV2 {
    data class Imported(val importedVersions: Int) : WorkspaceSourceSnapshotImportResultV2
    data class Blocked(val safeErrorCode: String, val safeMessage: String) : WorkspaceSourceSnapshotImportResultV2
}

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

/**
 * Imports a stable source frontier after another initializer wins an empty
 * target's pointer CAS.  The winner cannot be assumed to contain the losing
 * initializer's source checkpoint, so each source head is preserved as a
 * deterministic independent root unless the winner already carries its exact
 * provenance mapping.
 */
class WorkspaceSourceSnapshotImporterV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val sourceProfile: String,
) {
    fun import(sourceHeads: List<WorkspaceCheckpointSourceHeadV2>): WorkspaceSourceSnapshotImportResultV2 {
        val active = runCatching {
            WorkspaceSystemV2ContextProvider(
                localRepository,
                { workspaceKey },
                { writerDeviceId },
                { sourceProfile },
            ).requireActive()
        }.getOrElse {
            return WorkspaceSourceSnapshotImportResultV2.Blocked(
                "active_epoch_missing",
                (it.message ?: "The checkpoint winner is not active locally.").safeSourceImportMessageV2(),
            )
        }
        val committer = WorkspaceSourceImportCommitterV2(localRepository, active)
        var imported = 0
        sourceHeads.sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2).forEach { source ->
            val alreadyMapped = active.store.loadVersions(WorkspaceEntityKeyV2(source.entityType, source.entityId))
                .any { mapped ->
                    mapped.provenance?.let { provenance ->
                        provenance.sourceProfile == source.sourceProfile &&
                            provenance.sourceEpoch == source.sourceEpoch &&
                            provenance.sourceObjectId == source.sourceObjectId &&
                            provenance.sourceDigest == source.sourceObjectDigest
                    } == true
                }
            if (alreadyMapped) return@forEach
            val candidate = WorkspaceSourceCandidateV2(
                source.entityType,
                source.entityId,
                source.content,
                source.deletion,
                source.sourceProfile,
                source.sourceEpoch,
                source.sourceWriterId,
                source.sourceMutationId,
                source.sourceObjectId,
                source.sourceObjectDigest,
                source.sourceAuthoredAt
                    ?: return WorkspaceSourceSnapshotImportResultV2.Blocked(
                        "source_snapshot_invalid",
                        "A migration source head has no authored time.",
                    ),
            )
            when (val result = committer.import(candidate, verifiedParent = null)) {
                is WorkspaceSourceCommitResultV2.Blocked -> return WorkspaceSourceSnapshotImportResultV2.Blocked(
                    result.safeErrorCode,
                    result.safeMessage,
                )
                is WorkspaceSourceCommitResultV2.Committed -> if (result.value.newlyQueued) imported++
            }
        }
        return WorkspaceSourceSnapshotImportResultV2.Imported(imported)
    }
}

/**
 * Preserves a joining device's pre-sync product snapshot after bootstrapping
 * the remote checkpoint. Exact checkpoint state is reused; unequal state is
 * imported as an independent source root without inventing ancestry.
 */
class WorkspaceJoiningDeviceImporterV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val remoteProfile: String,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun captureLocalProductState(): WorkspaceJoiningImportResultV2 {
        val context = runCatching(::activeContext).getOrElse {
            return blocked("active_epoch_missing", it.message ?: "The active V2 epoch is unavailable for local import.")
        }
        val inventory = runCatching {
            WorkspaceGenesisCheckpointServiceV2(
                localRepository,
                settingsRepository,
                workspaceKey,
                writerDeviceId,
                remoteProfile,
                clock = clock,
            ).inventory()
        }.getOrElse {
            return blocked("local_import_preflight_failed", it.message ?: "Local product state cannot be represented by V2.")
        }
        val committer = WorkspaceSourceImportCommitterV2(localRepository, context)
        var imported = 0

        inventory.sourceHeads.forEach { source ->
            val key = WorkspaceEntityKeyV2(source.entityType, source.entityId)
            val exactCheckpointState = context.store.loadVersions(key)
                .asSequence()
                .filter { it.provenance?.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT }
                .filter { it.hasExactState(source.content, source.deletion) }
                .sortedBy { it.versionId }
                .firstOrNull()
            if (exactCheckpointState != null) return@forEach
            when (val result = committer.import(
                WorkspaceSourceCandidateV2(
                    source.entityType,
                    source.entityId,
                    source.content,
                    source.deletion,
                    source.sourceProfile,
                    source.sourceEpoch,
                    source.sourceWriterId ?: writerDeviceId,
                    source.sourceMutationId,
                    source.sourceObjectId,
                    source.sourceObjectDigest,
                    source.sourceAuthoredAt ?: clock(),
                ),
                verifiedParent = null,
            )) {
                is WorkspaceSourceCommitResultV2.Blocked -> return blocked(result.safeErrorCode, result.safeMessage)
                is WorkspaceSourceCommitResultV2.Committed -> if (result.value.newlyQueued) imported++
            }
        }
        return WorkspaceJoiningImportResultV2.Captured(imported)
    }

    private fun activeContext(): ActiveWorkspaceSystemV2 = WorkspaceSystemV2ContextProvider(
        localRepository,
        { workspaceKey },
        { writerDeviceId },
        { remoteProfile },
    ).requireActive()

    private fun blocked(code: String, message: String) = WorkspaceJoiningImportResultV2.Blocked(
        code,
        message.safeSourceImportMessageV2(),
    )
}

/**
 * V2 local export/restore boundary. It reads normalized DAG projections and
 * restores snapshots as deterministic independent source imports.
 */
class WorkspaceLocalDataTransferV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKeyProvider: () -> WorkspaceMasterKey?,
    private val writerDeviceIdProvider: () -> String,
    private val remoteProfileProvider: () -> String,
) {
    fun exportDocument(exportedAt: Instant): LocalDataExportDocument? {
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

    fun importDocument(document: LocalDataExportDocument): LocalDataImportSummary? {
        require(document.format == "someday.local-export.v2")
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
    ).requireActive()

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

/** Preserves every old-epoch outbox object that raced a V2 pointer commit. */
class WorkspacePriorEpochImporterV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val targetRemoteProfile: String,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun importUncheckpointed(
        sourceEpochId: String,
        sourceRemoteProfile: String = targetRemoteProfile,
    ): WorkspacePriorEpochImportResultV2 {
        val active = protocolStore.loadAuthoritativeEpoch()
            ?: return blocked("active_epoch_missing", "An active V2 epoch is required for prior-epoch import.")
        if (active.remoteProfile != targetRemoteProfile) {
            return blocked("target_authority_mismatch", "Prior-epoch import targets another active V2 authority.")
        }
        if (active.descriptor.syncEpochId == sourceEpochId) {
            return blocked("prior_epoch_not_read_only", "Prior-epoch import cannot target the active epoch itself.")
        }
        val activeContext = context(active.descriptor.syncEpochId)
        val sourceStore = store(sourceEpochId)
        val pending = sourceStore.loadPending(sourceRemoteProfile)
        if (pending.isEmpty()) return WorkspacePriorEpochImportResultV2.Imported(0, 0)
        val committer = WorkspaceSourceImportCommitterV2(localRepository, activeContext)
        var imported = 0
        var checkpointed = 0
        pending.sortedWith(compareBy({ it.createdAtEpochMilliseconds }, { it.objectId })).forEach { item ->
            val source = sourceStore.loadVersion(item.objectId)
                ?: return blocked("prior_epoch_object_missing", "A prior-epoch outbox object is no longer retained locally.")
            if (source.objectDigest != item.objectDigest) {
                return blocked("immutable_object_mismatch", "A prior-epoch outbox tuple no longer matches its immutable version.")
            }
            val exactCheckpointRoot = activeContext.store.loadVersions(source.key).firstOrNull { candidate ->
                candidate.provenance?.let { provenance ->
                    provenance.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT &&
                        provenance.sourceProfile == sourceRemoteProfile &&
                        provenance.sourceEpoch == sourceEpochId &&
                        provenance.sourceObjectId == source.versionId &&
                        provenance.sourceDigest == source.objectDigest
                } == true
            }
            if (exactCheckpointRoot != null) {
                if (!sourceStore.acknowledgePending(
                        sourceRemoteProfile, item.mutationId, item.objectId, item.objectDigest,
                    )
                ) {
                    return blocked("prior_epoch_ack_failed", "A checkpointed prior-epoch tuple could not be retired exactly.")
                }
                checkpointed++
                return@forEach
            }
            val parent = source.parentVersionIds.asSequence()
                .mapNotNull(sourceStore::loadVersion)
                .mapNotNull { oldParent ->
                    activeContext.store.loadVersions(source.key).firstOrNull { candidate ->
                        candidate.provenance?.let { provenance ->
                            provenance.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT &&
                            provenance.sourceProfile == sourceRemoteProfile &&
                                provenance.sourceEpoch == sourceEpochId &&
                                provenance.sourceObjectId == oldParent.versionId &&
                                provenance.sourceDigest == oldParent.objectDigest
                        } == true
                    }
                }
                .sortedBy { it.versionId }
                .firstOrNull()
            val candidate = WorkspaceSourceCandidateV2(
                source.entityType,
                source.entityId,
                source.contentPayload,
                source.deletionPayload,
                sourceRemoteProfile,
                sourceEpochId,
                item.writerDeviceId,
                item.mutationId,
                source.versionId,
                source.objectDigest,
                source.authoredAt,
            )
            when (val result = committer.import(candidate, parent)) {
                is WorkspaceSourceCommitResultV2.Blocked -> return blocked(result.safeErrorCode, result.safeMessage)
                is WorkspaceSourceCommitResultV2.Committed -> if (result.value.newlyQueued) imported++
            }
        }
        return WorkspacePriorEpochImportResultV2.Imported(imported, checkpointed)
    }

    private fun context(epochId: String): ActiveWorkspaceSystemV2 {
        val keys = SyncEpochKeyDerivationV2().derive(workspaceKey, epochId)
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return ActiveWorkspaceSystemV2(
            targetRemoteProfile,
            writerDeviceId,
            epochId,
            checkNotNull(protocolStore.loadEpoch(targetRemoteProfile, epochId)).descriptor,
            checkNotNull(protocolStore.loadEpoch(targetRemoteProfile, epochId)).lifecycle,
            checkNotNull(protocolStore.loadEpoch(targetRemoteProfile, epochId)).health,
            SqlDelightWorkspaceEntityStoreV2(
                localRepository.database,
                epochId,
                WorkspaceEntityCausalityEngineV2(materializer, validator),
                materializer,
                wire,
                WorkspaceOutboxEncoderV2 { version, mutationId ->
                    PreparedWorkspaceOutboxObjectV2(
                        writerDeviceId,
                        cipher.encodeJson(cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version))),
                    )
                },
            ),
            WorkspaceEntityVersionFactoryV2(epochId, materializer),
            materializer,
            wire,
            cipher,
        )
    }

    private fun store(epochId: String): SqlDelightWorkspaceEntityStoreV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return SqlDelightWorkspaceEntityStoreV2(
            localRepository.database,
            epochId,
            WorkspaceEntityCausalityEngineV2(materializer, validator),
            materializer,
            wire,
            WorkspaceOutboxEncoderV2 { version, mutationId ->
                PreparedWorkspaceOutboxObjectV2(
                    writerDeviceId,
                    cipher.encodeJson(cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version))),
                )
            },
        )
    }

    private fun blocked(code: String, message: String) = WorkspacePriorEpochImportResultV2.Blocked(
        code,
        message.safeSourceImportMessageV2(),
    )
}

/**
 * Monitors a retained V2 epoch for writes that crossed its checkpoint
 * frontier. Import and old-stream cursor advancement share one transaction, so
 * a crash can replay a unit but cannot forget a late branch.
 */
class WorkspacePriorEpochRemoteImporterV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val remote: WorkspaceSyncRemoteV2,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun importUntilStable(sourceEpochId: String): WorkspacePriorEpochRemoteImportResultV2 {
        val active = protocolStore.loadAuthoritativeEpoch()
            ?: return blocked("active_epoch_missing", "An active V2 epoch is required for old-writer monitoring.")
        if (active.descriptor.syncEpochId == sourceEpochId) {
            return blocked("prior_epoch_not_read_only", "Old-writer monitoring cannot target the active epoch.")
        }
        val activeContext = WorkspaceSystemV2ContextProvider(
            localRepository,
            { workspaceKey },
            { writerDeviceId },
            { active.remoteProfile },
        ).requireActive()
        val source = sourceComponents(sourceEpochId)
        val committer = WorkspaceSourceImportCommitterV2(localRepository, activeContext)
        var importedCount = 0
        var appliedUnitCount = 0
        var observedObjectCount = 0

        repeat(MAX_PRIOR_EPOCH_PULL_ROUNDS_SYSTEM_V2) {
            val cursors = source.store.loadCursors(remote.remoteProfile)
                .associate { it.streamId to it.cursorValue }
            val pulled = runCatching {
                remote.pull(sourceEpochId, cursors, MAX_PRIOR_EPOCH_PULL_UNITS_SYSTEM_V2)
            }.getOrElse {
                return blocked(
                    "prior_epoch_pull_failed",
                    it.message ?: "A retained V2 epoch could not be checked safely.",
                )
            }
            if (pulled.rebootstrapRequired) {
                return blocked(
                    "prior_epoch_history_unavailable",
                    "The retained remote no longer covers the old-epoch cursor; current-epoch push remains paused.",
                )
            }
            for (unit in pulled.units) {
                if (unit.syncEpochId != sourceEpochId || unit.objects.isEmpty()) {
                    return blocked("transport_metadata_mismatch", "An old-epoch cursor unit has invalid framing.")
                }
                val decoded = decodeUnit(source, unit)
                if (decoded is PriorEpochDecodedUnitV2.Rejected) {
                    return blocked(decoded.safeErrorCode, decoded.safeMessage)
                }
                decoded as PriorEpochDecodedUnitV2.Decoded
                val fresh = decoded.mutations.filter { mutation ->
                    source.store.findApplied(remote.remoteProfile, mutation.mutationId) == null
                }.sortedWith(compareBy({ it.version.generation }, { it.version.versionId }))
                var unitFailure: WorkspacePriorEpochRemoteImportResultV2.Blocked? = null
                var unitImported = 0
                localRepository.database.transaction {
                    for ((mutation, outer) in fresh.map { mutation ->
                        mutation to unit.objects.single { it.mutationId == mutation.mutationId }
                    }) {
                        val exactExisting = findSourceMapping(activeContext, mutation.version)
                        if (exactExisting != null) continue
                        val parent = mutation.version.parentVersionIds.singleOrNull()
                            ?.let(source.store::loadVersion)
                            ?.let { findSourceMapping(activeContext, it) }
                        val candidate = WorkspaceSourceCandidateV2(
                            mutation.version.entityType,
                            mutation.version.entityId,
                            mutation.version.contentPayload,
                            mutation.version.deletionPayload,
                            "${remote.remoteProfile}:prior-epoch",
                            sourceEpochId,
                            outer.writerDeviceId,
                            mutation.mutationId,
                            mutation.version.versionId,
                            mutation.version.objectDigest,
                            mutation.version.authoredAt,
                        )
                        when (val result = committer.import(candidate, parent)) {
                            is WorkspaceSourceCommitResultV2.Blocked -> {
                                unitFailure = blocked(result.safeErrorCode, result.safeMessage)
                                rollback()
                                return@transaction
                            }
                            is WorkspaceSourceCommitResultV2.Committed -> if (result.value.newlyQueued) unitImported++
                        }
                    }
                    when (val applied = source.store.applyRemoteCursorUnit(
                        RemoteWorkspaceCursorUnitV2(
                            remote.remoteProfile,
                            WorkspaceRemoteCursorAdvanceV2(
                                unit.streamId,
                                unit.expectedCursorValue,
                                unit.nextCursorValue,
                                unit.unitId,
                                unit.unitDigest,
                            ),
                            decoded.mutations,
                            clock(),
                        ),
                    )) {
                        is WorkspaceRemoteUnitApplyResultV2.Rejected -> {
                            unitFailure = blocked(applied.error.code.wireValue, applied.error.safeMessage)
                            rollback()
                        }
                        is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied -> Unit
                        is WorkspaceRemoteUnitApplyResultV2.Applied -> appliedUnitCount++
                    }
                }
                unitFailure?.let { return it }
                importedCount += unitImported
                observedObjectCount += unit.objects.size
            }
            if (pulled.frontierStable) {
                return WorkspacePriorEpochRemoteImportResultV2.Imported(
                    importedCount,
                    appliedUnitCount,
                    observedObjectCount,
                )
            }
        }
        return blocked(
            "prior_epoch_import_round_limit",
            "Old-epoch activity exceeded the bounded monitoring round limit.",
        )
    }

    private fun sourceComponents(epochId: String): PriorEpochSourceComponentsV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return PriorEpochSourceComponentsV2(
            wire,
            cipher,
            SqlDelightWorkspaceEntityStoreV2(
                localRepository.database,
                epochId,
                WorkspaceEntityCausalityEngineV2(materializer, validator),
                materializer,
                wire,
                WorkspaceOutboxEncoderV2 { version, mutationId ->
                    PreparedWorkspaceOutboxObjectV2(
                        writerDeviceId,
                        cipher.encodeJson(cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version))),
                    )
                },
            ),
        )
    }

    private fun decodeUnit(
        source: PriorEpochSourceComponentsV2,
        unit: WorkspaceEncryptedCursorUnitV2,
    ): PriorEpochDecodedUnitV2 {
        val mutations = mutableListOf<RemoteWorkspaceMutationV2>()
        val identities = mutableMapOf<String, Pair<String, String>>()
        unit.objects.forEach { outer ->
            if (outer.syncEpochId != unit.syncEpochId ||
                outer.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 || outer.mutationId == null
            ) return rejectedUnit("transport_metadata_mismatch", "Old-epoch object metadata is invalid.")
            val identity = outer.objectId to outer.objectDigest
            val prior = identities.put(outer.mutationId, identity)
            if (prior != null && prior != identity) {
                return rejectedUnit("mutation_reuse_mismatch", "An old-epoch unit reuses a mutation identity.")
            }
            val plaintext = when (val decrypted = source.cipher.decrypt(outer)) {
                is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decrypted.plaintext
                is EncryptedWorkspaceObjectDecodeResultV2.Rejected -> return rejectedUnit(
                    decrypted.error.code.wireValue,
                    decrypted.error.safeMessage,
                )
            }
            val version = when (val decoded = source.wire.decode(
                plaintext,
                WorkspaceVersionOuterMetadataV2(outer.syncEpochId, outer.objectId, outer.objectDigest),
            )) {
                is WorkspaceEntityWireDecodeResultV2.Decoded -> decoded.version
                is WorkspaceEntityWireDecodeResultV2.Rejected -> return rejectedUnit(
                    decoded.error.code.wireValue,
                    decoded.error.safeMessage,
                )
            }
            mutations += RemoteWorkspaceMutationV2(
                outer.mutationId,
                outer.objectId,
                outer.objectDigest,
                outer.writerDeviceId,
                version,
            )
        }
        return PriorEpochDecodedUnitV2.Decoded(mutations)
    }

    private fun findSourceMapping(
        active: ActiveWorkspaceSystemV2,
        source: WorkspaceEntityVersionV2,
    ): WorkspaceEntityVersionV2? = active.store.loadVersions(source.key)
        .asSequence()
        .filter { mapped ->
            mapped.provenance?.let { provenance ->
                provenance.type in setOf(
                    WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT,
                    WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT,
                ) && provenance.sourceEpoch == source.syncEpochId &&
                    provenance.sourceObjectId == source.versionId &&
                    provenance.sourceDigest == source.objectDigest
            } == true
        }
        .sortedBy { it.versionId }
        .firstOrNull()

    private fun rejectedUnit(code: String, message: String) = PriorEpochDecodedUnitV2.Rejected(
        code,
        message.safeSourceImportMessageV2(),
    )

    private fun blocked(code: String, message: String) = WorkspacePriorEpochRemoteImportResultV2.Blocked(
        code,
        message.safeSourceImportMessageV2(),
    )

    private companion object {
        const val MAX_PRIOR_EPOCH_PULL_ROUNDS_SYSTEM_V2 = 1_024
        const val MAX_PRIOR_EPOCH_PULL_UNITS_SYSTEM_V2 = 256
    }
}

private data class PriorEpochSourceComponentsV2(
    val wire: WorkspaceEntityWireCodecV2,
    val cipher: WorkspaceObjectCipherV2,
    val store: SqlDelightWorkspaceEntityStoreV2,
)

private sealed interface PriorEpochDecodedUnitV2 {
    data class Decoded(val mutations: List<RemoteWorkspaceMutationV2>) : PriorEpochDecodedUnitV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : PriorEpochDecodedUnitV2
}

private sealed interface WorkspaceSourceCommitResultV2 {
    data class Committed(val value: WorkspaceSourceImportCommitV2) : WorkspaceSourceCommitResultV2
    data class Blocked(val safeErrorCode: String, val safeMessage: String) : WorkspaceSourceCommitResultV2
}

/** Import record, version, generated joins, projection, and outbox commit atomically. */
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
