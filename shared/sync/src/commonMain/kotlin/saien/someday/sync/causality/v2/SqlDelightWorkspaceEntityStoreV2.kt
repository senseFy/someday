@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.local.db.Note_projections_system_v2
import saien.someday.data.local.db.SelectContentNoteProjectionsByEffectiveNotebookSystemV2
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.local.db.Sync_applied_mutations_system_v2
import saien.someday.data.local.db.Sync_pending_mutations_system_v2
import saien.someday.data.local.db.Sync_remote_cursors_system_v2
import saien.someday.data.local.db.Workspace_entity_conflicts_v2
import saien.someday.data.local.db.Workspace_entity_versions_v2
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

const val RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2: String = "local:recovery-inbox"

data class PreparedWorkspaceOutboxObjectV2(
    val writerDeviceId: String,
    val encodedOuter: String,
)

fun interface WorkspaceOutboxEncoderV2 {
    fun encode(version: WorkspaceEntityVersionV2, mutationId: String): PreparedWorkspaceOutboxObjectV2
}

data class LocalWorkspaceMutationV2(
    val remoteProfile: String,
    val mutationId: String,
    val version: WorkspaceEntityVersionV2,
    val createdAt: Instant,
)

data class RemoteWorkspaceMutationV2(
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val writerDeviceId: String,
    val version: WorkspaceEntityVersionV2,
)

data class WorkspaceRemoteCursorAdvanceV2(
    val streamId: String,
    val expectedCursorValue: String?,
    val nextCursorValue: String,
    val unitId: String,
    val unitDigest: String,
)

data class RemoteWorkspaceCursorUnitV2(
    val remoteProfile: String,
    val cursor: WorkspaceRemoteCursorAdvanceV2,
    val mutations: List<RemoteWorkspaceMutationV2>,
    val appliedAt: Instant,
)

data class StoredWorkspacePendingMutationV2(
    val remoteProfile: String,
    val syncEpochId: String,
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val writerDeviceId: String,
    val encodedOuter: String,
    val createdAtEpochMilliseconds: Long,
    val lastAttemptAtEpochMilliseconds: Long?,
    val attemptCount: Long,
)

data class StoredWorkspaceAppliedMutationV2(
    val remoteProfile: String,
    val syncEpochId: String,
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val firstWriterDeviceId: String,
    val appliedAtEpochMilliseconds: Long,
)

data class StoredWorkspaceCursorV2(
    val remoteProfile: String,
    val syncEpochId: String,
    val streamId: String,
    val cursorValue: String,
    val unitId: String,
    val unitDigest: String,
    val updatedAtEpochMilliseconds: Long,
)

enum class WorkspaceProjectionStatusV2 {
    CONTENT,
    DELETION,
    CONFLICT,
}

data class WorkspaceProjectionSnapshotV2(
    val key: WorkspaceEntityKeyV2,
    val status: WorkspaceProjectionStatusV2,
    val preferredHeadVersionId: String?,
    val content: WorkspaceEntityContentV2?,
    val deletion: WorkspaceDeletionV2?,
    val referencedEntityId: String?,
    val effectiveEntityId: String?,
    val warning: String?,
    val authoredAt: Instant?,
)

/**
 * Denormalized note row for diary list/search paths.
 * Built from [note_projections_system_v2], which is rebuilt for affected entities on commit.
 */
data class StoredNoteListProjectionV2(
    val noteId: String,
    val preferredHeadVersionId: String?,
    val referencedNotebookId: String?,
    val effectiveNotebookId: String?,
    val title: String,
    val markdownBody: String,
    val noteCreatedAt: Instant,
    val timeZoneId: String?,
    val locationPlaceText: String?,
    val warning: String?,
    val authoredAt: Instant?,
)

enum class WorkspaceStoreErrorCodeV2(val wireValue: String) {
    INVALID_MUTATION("invalid_mutation"),
    MUTATION_OBJECT_MISMATCH("mutation_object_mismatch"),
    IMMUTABLE_OBJECT_MISMATCH("immutable_object_mismatch"),
    MISSING_PARENT("missing_parent"),
    NON_TOPOLOGICAL_UNIT("non_topological_unit"),
    CAUSALITY_VALIDATION_FAILED("causality_validation_failed"),
    STORED_OBJECT_INVALID("stored_object_invalid"),
    CURSOR_STATE_MISMATCH("cursor_state_mismatch"),
    OUTBOX_IDENTITY_MISMATCH("outbox_identity_mismatch"),
}

data class WorkspaceStoreErrorV2(
    val code: WorkspaceStoreErrorCodeV2,
    val safeMessage: String,
    val causalityErrors: List<WorkspaceCausalityErrorV2> = emptyList(),
)

sealed interface WorkspaceLocalCommitResultV2 {
    data class Committed(
        val plans: Map<WorkspaceEntityKeyV2, WorkspaceReconciliationPlanV2>,
        val pending: List<StoredWorkspacePendingMutationV2>,
    ) : WorkspaceLocalCommitResultV2

    data class AlreadyCommitted(val pending: List<StoredWorkspacePendingMutationV2>) : WorkspaceLocalCommitResultV2

    data class Rejected(val error: WorkspaceStoreErrorV2) : WorkspaceLocalCommitResultV2
}

sealed interface WorkspaceRemoteUnitApplyResultV2 {
    data class Applied(
        val plans: Map<WorkspaceEntityKeyV2, WorkspaceReconciliationPlanV2>,
        val replayedMutations: Int,
        val cursor: StoredWorkspaceCursorV2,
    ) : WorkspaceRemoteUnitApplyResultV2

    data class AlreadyApplied(val cursor: StoredWorkspaceCursorV2) : WorkspaceRemoteUnitApplyResultV2

    data class Rejected(val error: WorkspaceStoreErrorV2) : WorkspaceRemoteUnitApplyResultV2
}

private data class PreparedEntityPlanV2(
    val incoming: List<WorkspaceEntityVersionV2>,
    val plan: WorkspaceReconciliationPlanV2,
)

private sealed interface PrepareEntityPlansResultV2 {
    data class Prepared(val values: Map<WorkspaceEntityKeyV2, PreparedEntityPlanV2>) : PrepareEntityPlansResultV2
    data class Rejected(val error: WorkspaceStoreErrorV2) : PrepareEntityPlansResultV2
}

/**
 * Whole-product V2 transaction boundary. Entity versions, generated joins,
 * head/conflict caches, typed projections, outbox identity, mutation replay,
 * and a remote cursor commit together or not at all.
 */
class SqlDelightWorkspaceEntityStoreV2(
    private val database: SomedayDatabase,
    private val syncEpochId: String,
    private val engine: WorkspaceEntityCausalityEngineV2,
    private val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    private val wireCodec: WorkspaceEntityWireCodecV2,
    private val outboxEncoder: WorkspaceOutboxEncoderV2,
) {
    private val queries = database.somedayQueries

    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(syncEpochId))
    }

    fun loadVersion(versionId: String): WorkspaceEntityVersionV2? =
        queries.selectWorkspaceEntityVersionV2(syncEpochId, versionId)
            .executeAsOneOrNull()
            ?.let(::decodeStoredVersion)

    fun loadVersions(key: WorkspaceEntityKeyV2): List<WorkspaceEntityVersionV2> =
        queries.selectWorkspaceEntityVersionsByEntityV2(syncEpochId, key.entityType.wireValue, key.entityId)
            .executeAsList()
            .map(::decodeStoredVersion)

    fun loadAllVersions(): List<WorkspaceEntityVersionV2> =
        queries.selectWorkspaceEntityVersionsByEpochV2(syncEpochId)
            .executeAsList()
            .map(::decodeStoredVersion)

    fun loadEntityKeys(): List<WorkspaceEntityKeyV2> =
        queries.selectWorkspaceEntityKeysV2(syncEpochId)
            .executeAsList()
            .map { row ->
                WorkspaceEntityKeyV2(
                    entityType = checkNotNull(WorkspaceEntityTypeV2.fromWire(row.entity_type)),
                    entityId = row.entity_id,
                )
            }

    fun loadProjections(entityType: WorkspaceEntityTypeV2? = null): List<WorkspaceProjectionSnapshotV2> =
        loadEntityKeys()
            .asSequence()
            .filter { entityType == null || it.entityType == entityType }
            .mapNotNull(::loadProjection)
            .toList()

    /**
     * Content note projections ordered by journal date (newest first).
     * Prefer this over [loadProjections] for product list/search surfaces.
     */
    fun loadContentNoteListProjections(): List<StoredNoteListProjectionV2> =
        queries.selectContentNoteProjectionsSystemV2(syncEpochId)
            .executeAsList()
            .mapNotNull(::mapStoredNoteListProjection)

    fun loadContentNoteListProjectionsForEffectiveNotebook(
        effectiveNotebookId: String,
    ): List<StoredNoteListProjectionV2> =
        queries.selectContentNoteProjectionsByEffectiveNotebookSystemV2(syncEpochId, effectiveNotebookId)
            .executeAsList()
            .mapNotNull(::mapStoredNoteListProjection)

    fun hasUnresolvedNotebookNoteProjections(): Boolean =
        queries.selectHasUnresolvedNotebookNoteProjectionSystemV2(syncEpochId)
            .executeAsOneOrNull() != null

    fun loadPendingObjectIds(remoteProfile: String): Set<String> =
        queries.selectPendingMutationObjectIdsSystemV2(remoteProfile, syncEpochId)
            .executeAsList()
            .toCollection(linkedSetOf())

    fun loadHeads(key: WorkspaceEntityKeyV2): List<WorkspaceEntityVersionV2> =
        queries.selectWorkspaceEntityHeadsV2(syncEpochId, key.entityType.wireValue, key.entityId)
            .executeAsList()
            .map { id -> checkNotNull(loadVersion(id)) }

    fun loadConflicts(key: WorkspaceEntityKeyV2): List<WorkspaceConflictStateV2> =
        queries.selectWorkspaceEntityConflictsV2(syncEpochId, key.entityType.wireValue, key.entityId)
            .executeAsList()
            .map(::mapConflictState)

    fun loadActiveConflicts(): List<WorkspaceConflictStateV2> =
        queries.selectActiveWorkspaceEntityConflictsV2(syncEpochId)
            .executeAsList()
            .map(::mapConflictState)

    fun loadPending(remoteProfile: String): List<StoredWorkspacePendingMutationV2> =
        queries.selectPendingMutationsSystemV2(remoteProfile, syncEpochId)
            .executeAsList()
            .map(Sync_pending_mutations_system_v2::toDomainV2)

    fun loadCursor(remoteProfile: String, streamId: String): StoredWorkspaceCursorV2? =
        queries.selectRemoteCursorSystemV2(remoteProfile, syncEpochId, streamId)
            .executeAsOneOrNull()
            ?.toDomainV2()

    fun loadCursors(remoteProfile: String): List<StoredWorkspaceCursorV2> =
        queries.selectRemoteCursorsSystemV2(remoteProfile, syncEpochId)
            .executeAsList()
            .map(Sync_remote_cursors_system_v2::toDomainV2)

    fun findApplied(remoteProfile: String, mutationId: String): StoredWorkspaceAppliedMutationV2? =
        queries.selectAppliedMutationSystemV2(remoteProfile, syncEpochId, mutationId)
            .executeAsOneOrNull()
            ?.toDomainV2()

    fun commitLocalMutations(mutations: List<LocalWorkspaceMutationV2>): WorkspaceLocalCommitResultV2 {
        if (mutations.isEmpty() || mutations.any {
                it.remoteProfile.isBlank() || !UUID_V4_PATTERN_SYSTEM_V2.matches(it.mutationId) ||
                    it.version.syncEpochId != syncEpochId || it.version.versionId != it.version.versionId.lowercase()
            } || mutations.map { it.remoteProfile }.distinct().size != 1
        ) {
            return WorkspaceLocalCommitResultV2.Rejected(invalidMutation("Local mutation batch has an invalid identity or mixed profile."))
        }
        mutations.firstOrNull { mutation ->
            materializer.expectedDeterministicMutationId(mutation.version)
                ?.let { it != mutation.mutationId } == true
        }?.let {
            return WorkspaceLocalCommitResultV2.Rejected(
                invalidMutation("A deterministic automatic/import version has a noncanonical mutation identity."),
            )
        }
        var result: WorkspaceLocalCommitResultV2? = null
        database.transaction {
            val existing = mutableListOf<StoredWorkspacePendingMutationV2>()
            val fresh = mutableListOf<LocalWorkspaceMutationV2>()
            mutations.sortedBy { it.mutationId }.forEach { mutation ->
                val byMutation = queries.selectPendingMutationSystemV2(
                    mutation.remoteProfile,
                    syncEpochId,
                    mutation.mutationId,
                ).executeAsOneOrNull()
                val byObject = queries.selectPendingMutationByObjectSystemV2(
                    mutation.remoteProfile,
                    syncEpochId,
                    mutation.version.versionId,
                ).executeAsOneOrNull()
                val pending = byMutation ?: byObject
                if (pending != null) {
                    if (pending.mutation_id != mutation.mutationId ||
                        pending.object_id != mutation.version.versionId ||
                        pending.object_digest != mutation.version.objectDigest
                    ) {
                        result = WorkspaceLocalCommitResultV2.Rejected(
                            WorkspaceStoreErrorV2(
                                WorkspaceStoreErrorCodeV2.OUTBOX_IDENTITY_MISMATCH,
                                "A durable V2 outbox identity is already bound differently.",
                            ),
                        )
                        return@transaction
                    }
                    existing += pending.toDomainV2()
                } else {
                    fresh += mutation
                }
            }
            if (result != null) return@transaction
            if (fresh.isEmpty()) {
                result = WorkspaceLocalCommitResultV2.AlreadyCommitted(existing.distinctBy { it.mutationId })
                return@transaction
            }
            val prepared = when (val value = prepareEntityPlans(fresh.map { it.version })) {
                is PrepareEntityPlansResultV2.Prepared -> value.values
                is PrepareEntityPlansResultV2.Rejected -> {
                    result = WorkspaceLocalCommitResultV2.Rejected(value.error)
                    return@transaction
                }
            }
            val encodedOriginals = runCatching {
                fresh.associate { mutation ->
                    mutation.version.versionId to outboxEncoder.encode(mutation.version, mutation.mutationId)
                }
            }.getOrElse {
                result = WorkspaceLocalCommitResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.INVALID_MUTATION, "Local V2 object encryption failed before commit."),
                )
                return@transaction
            }
            val generatedOutbox = prepareGeneratedOutbox(
                remoteProfile = mutations.first().remoteProfile,
                prepared = prepared,
            ) ?: run {
                result = WorkspaceLocalCommitResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.OUTBOX_IDENTITY_MISMATCH, "Generated V2 outbox identity mismatched durable state."),
                )
                return@transaction
            }

            persistPreparedPlans(prepared, mutations.first().createdAt.toEpochMilliseconds())
            fresh.forEach { mutation ->
                val encoded = checkNotNull(encodedOriginals[mutation.version.versionId])
                insertPending(mutation.remoteProfile, mutation.mutationId, mutation.version, encoded, mutation.createdAt)
            }
            persistGeneratedOutbox(mutations.first().remoteProfile, generatedOutbox, mutations.first().createdAt)
            rebuildAffectedProjections(prepared.keys, mutations.first().createdAt)
            val allPending = mutations.map { mutation ->
                checkNotNull(
                    queries.selectPendingMutationByObjectSystemV2(
                        mutation.remoteProfile,
                        syncEpochId,
                        mutation.version.versionId,
                    ).executeAsOneOrNull(),
                ).toDomainV2()
            }
            result = WorkspaceLocalCommitResultV2.Committed(
                prepared.mapValues { it.value.plan },
                allPending.distinctBy { it.mutationId }.sortedBy { it.mutationId },
            )
        }
        return checkNotNull(result)
    }

    fun applyRemoteCursorUnit(unit: RemoteWorkspaceCursorUnitV2): WorkspaceRemoteUnitApplyResultV2 {
        if (unit.remoteProfile.isBlank() || unit.cursor.streamId.isBlank() || unit.cursor.nextCursorValue.isBlank() ||
            unit.cursor.unitId.isBlank() || unit.cursor.unitDigest.isBlank() ||
            unit.mutations.any { !UUID_V4_PATTERN_SYSTEM_V2.matches(it.writerDeviceId) }
        ) {
            return WorkspaceRemoteUnitApplyResultV2.Rejected(invalidMutation("Remote V2 cursor unit has an invalid identity."))
        }
        var result: WorkspaceRemoteUnitApplyResultV2? = null
        database.transaction {
            val current = loadCursor(unit.remoteProfile, unit.cursor.streamId)
            if (current?.cursorValue == unit.cursor.nextCursorValue) {
                result = if (current.unitId == unit.cursor.unitId && current.unitDigest == unit.cursor.unitDigest) {
                    WorkspaceRemoteUnitApplyResultV2.AlreadyApplied(current)
                } else {
                    WorkspaceRemoteUnitApplyResultV2.Rejected(cursorMismatch("Cursor value already identifies another authenticated unit."))
                }
                return@transaction
            }
            if (current?.cursorValue != unit.cursor.expectedCursorValue) {
                result = WorkspaceRemoteUnitApplyResultV2.Rejected(cursorMismatch("Remote cursor does not match the durable expected frontier."))
                return@transaction
            }
            remoteUnitShapeError(unit)?.let {
                result = WorkspaceRemoteUnitApplyResultV2.Rejected(it)
                return@transaction
            }
            var replayed = 0
            val fresh = mutableListOf<RemoteWorkspaceMutationV2>()
            unit.mutations.forEach { mutation ->
                val applied = findApplied(unit.remoteProfile, mutation.mutationId)
                when {
                    applied == null -> fresh += mutation
                    applied.objectId == mutation.objectId && applied.objectDigest == mutation.objectDigest -> {
                        if (loadVersion(mutation.objectId) != mutation.version) {
                            result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                                WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.STORED_OBJECT_INVALID, "Applied mutation no longer has its exact immutable object."),
                            )
                            return@transaction
                        }
                        replayed++
                    }
                    else -> {
                        result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                            WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.MUTATION_OBJECT_MISMATCH, "Mutation id is already bound to another immutable object."),
                        )
                        return@transaction
                    }
                }
            }
            if (result != null) return@transaction
            val prepared = when (val value = prepareEntityPlans(fresh.map { it.version })) {
                is PrepareEntityPlansResultV2.Prepared -> value.values
                is PrepareEntityPlansResultV2.Rejected -> {
                    result = WorkspaceRemoteUnitApplyResultV2.Rejected(value.error)
                    return@transaction
                }
            }
            val generatedOutbox = prepareGeneratedOutbox(unit.remoteProfile, prepared) ?: run {
                result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.OUTBOX_IDENTITY_MISMATCH, "Generated V2 outbox identity mismatched durable state."),
                )
                return@transaction
            }
            val committedAt = unit.appliedAt.toEpochMilliseconds()
            persistPreparedPlans(prepared, committedAt)
            fresh.forEach { mutation ->
                queries.insertAppliedMutationSystemV2(
                    unit.remoteProfile,
                    syncEpochId,
                    mutation.mutationId,
                    mutation.objectId,
                    mutation.objectDigest,
                    mutation.writerDeviceId,
                    committedAt,
                )
            }
            persistGeneratedOutbox(unit.remoteProfile, generatedOutbox, unit.appliedAt)
            rebuildAffectedProjections(prepared.keys, unit.appliedAt)
            queries.upsertRemoteCursorSystemV2(
                unit.remoteProfile,
                syncEpochId,
                unit.cursor.streamId,
                unit.cursor.nextCursorValue,
                unit.cursor.unitId,
                unit.cursor.unitDigest,
                committedAt,
            )
            result = WorkspaceRemoteUnitApplyResultV2.Applied(
                prepared.mapValues { it.value.plan },
                replayed,
                checkNotNull(loadCursor(unit.remoteProfile, unit.cursor.streamId)),
            )
        }
        return checkNotNull(result)
    }

    fun acknowledgePending(
        remoteProfile: String,
        mutationId: String,
        objectId: String,
        objectDigest: String,
    ): Boolean {
        var removed = false
        database.transaction {
            val row = queries.selectPendingMutationSystemV2(remoteProfile, syncEpochId, mutationId)
                .executeAsOneOrNull() ?: return@transaction
            require(row.object_id == objectId && row.object_digest == objectDigest) {
                "V2 acknowledgement must match mutation, object id, and object digest exactly."
            }
            removed = queries.deletePendingMutationSystemV2(
                remoteProfile,
                syncEpochId,
                mutationId,
                objectId,
                objectDigest,
            ).value == 1L
            if (removed) {
                queries.selectSourceImportByMutationSystemV2(remoteProfile, syncEpochId, mutationId)
                    .executeAsOneOrNull()
                    ?.let { imported ->
                        queries.updateSourceImportStateSystemV2(
                            "published",
                            kotlin.time.Clock.System.now().toEpochMilliseconds(),
                            remoteProfile,
                            syncEpochId,
                            imported.source_profile,
                            imported.source_object_id,
                            imported.source_digest,
                        )
                        val sourceEpoch = imported.source_epoch
                        val sourceMutationId = imported.source_mutation_id
                        if (sourceEpoch != null && sourceMutationId != null) {
                            queries.deletePendingMutationSystemV2(
                                remoteProfile,
                                sourceEpoch,
                                sourceMutationId,
                                imported.source_object_id,
                                imported.source_digest,
                            )
                        }
                    }
            }
        }
        return removed
    }

    fun rebuildProjections(rebuiltAt: Instant) {
        database.transaction { rebuildAllProjections(rebuiltAt) }
    }

    fun loadProjection(key: WorkspaceEntityKeyV2): WorkspaceProjectionSnapshotV2? {
        val heads = loadHeads(key)
        if (heads.isEmpty()) return null
        if (heads.size != 1) {
            return WorkspaceProjectionSnapshotV2(
                key,
                WorkspaceProjectionStatusV2.CONFLICT,
                null,
                null,
                null,
                null,
                null,
                "conflict",
                null,
            )
        }
        val head = heads.single()
        if (head.kind == WorkspaceEntityVersionKindV2.DELETION) {
            return WorkspaceProjectionSnapshotV2(
                key,
                WorkspaceProjectionStatusV2.DELETION,
                head.versionId,
                null,
                head.deletionPayload,
                null,
                null,
                null,
                head.authoredAt,
            )
        }
        val content = checkNotNull(head.contentPayload)
        val reference = when (content) {
            is NoteContentV2 -> content.notebookId
            is WorkspacePreferencesV2 -> content.defaultNotebookId
            is NotebookContentV2 -> null
        }
        val targetLive = reference?.let(::isNotebookLive) ?: true
        return WorkspaceProjectionSnapshotV2(
            key,
            WorkspaceProjectionStatusV2.CONTENT,
            head.versionId,
            content,
            null,
            reference,
            when (key.entityType) {
                WorkspaceEntityTypeV2.NOTE -> if (targetLive) reference else RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES -> reference?.takeIf { targetLive }
                WorkspaceEntityTypeV2.NOTEBOOK -> null
            },
            when {
                reference != null && !targetLive && key.entityType == WorkspaceEntityTypeV2.NOTE -> "unresolved_notebook_reference"
                reference != null && !targetLive -> "unresolved_default_notebook_reference"
                content is NoteContentV2 && content.timeZoneId != null && runCatching { TimeZone.of(content.timeZoneId) }.isFailure -> "time_zone_fallback"
                else -> null
            },
            head.authoredAt,
        )
    }

    private fun prepareEntityPlans(incoming: List<WorkspaceEntityVersionV2>): PrepareEntityPlansResultV2 {
        if (incoming.isEmpty()) return PrepareEntityPlansResultV2.Prepared(emptyMap())
        incoming.groupBy { it.versionId }.values.forEach { copies ->
            if (copies.any { it != copies.first() }) {
                return PrepareEntityPlansResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH, "One incoming version id has unequal envelopes."),
                )
            }
        }
        incoming.forEach { version ->
            val stored = queries.selectWorkspaceEntityVersionV2(syncEpochId, version.versionId)
                .executeAsOneOrNull()
                ?.let(::decodeStoredVersion)
            if (stored != null && stored != version) {
                return PrepareEntityPlansResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH, "Version id is already bound to another immutable envelope."),
                )
            }
        }
        val output = linkedMapOf<WorkspaceEntityKeyV2, PreparedEntityPlanV2>()
        val grouped = incoming.distinctBy { it.versionId }.groupBy { it.key }
        grouped.keys.sortedWith(compareBy({ it.entityType.wireValue }, { it.entityId })).forEach { key ->
            val values = checkNotNull(grouped[key])
            val stored = loadVersions(key)
            val all = (stored + values).distinctBy { it.versionId }
            val known = loadConflicts(key).map { it.descriptor }
            when (val reconciled = engine.reconcile(syncEpochId, key, all, known)) {
                is WorkspaceReconciliationResultV2.InvalidGraph -> return PrepareEntityPlansResultV2.Rejected(
                    WorkspaceStoreErrorV2(
                        WorkspaceStoreErrorCodeV2.CAUSALITY_VALIDATION_FAILED,
                        "Entity DAG failed V2 reconciliation.",
                        reconciled.errors,
                    ),
                )
                is WorkspaceReconciliationResultV2.Reconciled -> output[key] = PreparedEntityPlanV2(
                    incoming = values.filter { candidate -> stored.none { it.versionId == candidate.versionId } },
                    plan = reconciled.plan,
                )
            }
        }
        return PrepareEntityPlansResultV2.Prepared(output)
    }

    private fun persistPreparedPlans(
        prepared: Map<WorkspaceEntityKeyV2, PreparedEntityPlanV2>,
        committedAtEpochMilliseconds: Long,
    ) {
        prepared.values
            .flatMap { it.incoming }
            .distinctBy { it.versionId }
            .sortedWith(compareBy({ it.generation }, { it.entityType.wireValue }, { it.entityId }, { it.versionId }))
            .forEach(::insertVersion)
        prepared.values
            .flatMap { it.plan.generatedVersions }
            .distinctBy { it.versionId }
            .sortedWith(compareBy({ it.generation }, { it.entityType.wireValue }, { it.entityId }, { it.versionId }))
            .forEach(::insertVersion)
        prepared.values.sortedWith(compareBy({ it.plan.key.entityType.wireValue }, { it.plan.key.entityId })).forEach { value ->
            persistHeads(value.plan)
            persistConflicts(value.plan, committedAtEpochMilliseconds)
        }
    }

    private fun insertVersion(version: WorkspaceEntityVersionV2) {
        val existing = queries.selectWorkspaceEntityVersionV2(syncEpochId, version.versionId).executeAsOneOrNull()
        if (existing != null) {
            require(decodeStoredVersion(existing) == version) { "Immutable V2 version mismatch during replay." }
            return
        }
        val deletion = version.deletionPayload
        val provenance = version.provenance
        queries.insertWorkspaceEntityVersionV2(
            epoch_id = version.syncEpochId,
            version_id = version.versionId,
            contract_id = version.contractId,
            schema_set_version = version.schemaSetVersion,
            envelope_schema_version = version.envelopeSchemaVersion.toLong(),
            entity_type = version.entityType.wireValue,
            entity_schema_version = version.entitySchemaVersion.toLong(),
            entity_id = version.entityId,
            kind = version.kind.wireValue,
            canonical_payload = wireCodec.encode(version),
            deleted_at_seconds = deletion?.deletedAt?.epochSeconds,
            deleted_at_nanos = deletion?.deletedAt?.nanosecondsOfSecond?.toLong(),
            provenance_type = provenance?.type?.wireValue,
            provenance_source_profile = provenance?.sourceProfile,
            provenance_source_epoch = provenance?.sourceEpoch,
            provenance_source_writer_id = provenance?.sourceWriterId,
            provenance_source_mutation_id = provenance?.sourceMutationId,
            provenance_source_object_id = provenance?.sourceObjectId,
            provenance_source_digest = provenance?.sourceDigest,
            author_actor_id = version.authorActorId,
            authored_at_seconds = version.authoredAt.epochSeconds,
            authored_at_nanos = version.authoredAt.nanosecondsOfSecond.toLong(),
            generation = version.generation,
            payload_digest = version.payloadDigest,
            object_digest = version.objectDigest,
            merge_algorithm_version = version.mergeAlgorithmVersion,
        )
        version.parentVersionIds.forEach { parent ->
            queries.insertWorkspaceEntityParentV2(
                version.syncEpochId,
                version.entityType.wireValue,
                version.entityId,
                version.versionId,
                parent,
            )
        }
    }

    private fun persistHeads(plan: WorkspaceReconciliationPlanV2) {
        queries.deleteWorkspaceEntityHeadsV2(syncEpochId, plan.key.entityType.wireValue, plan.key.entityId)
        plan.finalHeadVersionIds.sorted().forEach { versionId ->
            queries.insertWorkspaceEntityHeadV2(syncEpochId, plan.key.entityType.wireValue, plan.key.entityId, versionId)
        }
    }

    private fun persistConflicts(plan: WorkspaceReconciliationPlanV2, detectedAt: Long) {
        val existing = queries.selectWorkspaceEntityConflictsV2(
            syncEpochId,
            plan.key.entityType.wireValue,
            plan.key.entityId,
        ).executeAsList().associateBy { it.conflict_id }
        plan.conflictStates.filter { it.lifecycle != WorkspaceConflictLifecycleV2.ACTIVE }.forEach { state ->
            val row = existing[state.descriptor.conflictId]
            if (row == null) insertConflict(state, detectedAt) else queries.updateWorkspaceEntityConflictV2(
                state.lifecycle.wireValue,
                state.supersededByConflictId,
                state.resolvedByVersionId,
                syncEpochId,
                state.descriptor.conflictId,
            )
        }
        plan.conflictStates.filter { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }.forEach { state ->
            val row = existing[state.descriptor.conflictId]
            if (row == null) insertConflict(state, detectedAt) else queries.updateWorkspaceEntityConflictV2(
                state.lifecycle.wireValue,
                null,
                null,
                syncEpochId,
                state.descriptor.conflictId,
            )
        }
    }

    private fun insertConflict(state: WorkspaceConflictStateV2, detectedAt: Long) {
        val descriptor = state.descriptor
        queries.insertWorkspaceEntityConflictV2(
            syncEpochId,
            descriptor.conflictId,
            descriptor.entityType.wireValue,
            descriptor.entityId,
            descriptor.baseVersionId,
            descriptor.reason.wireValue,
            descriptor.conflictingFields.sorted().joinToString(","),
            detectedAt,
            state.lifecycle.wireValue,
            state.supersededByConflictId,
            state.resolvedByVersionId,
        )
        descriptor.headVersionIds.forEach { head ->
            queries.insertWorkspaceEntityConflictHeadV2(syncEpochId, descriptor.conflictId, head)
        }
    }

    private data class GeneratedOutboxV2(
        val mutationId: String,
        val version: WorkspaceEntityVersionV2,
        val encoded: PreparedWorkspaceOutboxObjectV2,
    )

    private fun prepareGeneratedOutbox(
        remoteProfile: String,
        prepared: Map<WorkspaceEntityKeyV2, PreparedEntityPlanV2>,
    ): List<GeneratedOutboxV2>? {
        val output = mutableListOf<GeneratedOutboxV2>()
        prepared.values.flatMap { it.plan.generatedVersions }.distinctBy { it.versionId }.forEach { version ->
            val mutationId = materializer.deterministicSystemMutationId(version)
            val existing = queries.selectPendingMutationByObjectSystemV2(remoteProfile, syncEpochId, version.versionId)
                .executeAsOneOrNull()
            if (existing != null) {
                if (existing.mutation_id != mutationId || existing.object_digest != version.objectDigest) return null
            } else {
                val encoded = runCatching { outboxEncoder.encode(version, mutationId) }.getOrNull() ?: return null
                output += GeneratedOutboxV2(mutationId, version, encoded)
            }
        }
        return output
    }

    private fun persistGeneratedOutbox(
        remoteProfile: String,
        values: List<GeneratedOutboxV2>,
        createdAt: Instant,
    ) {
        values.forEach { insertPending(remoteProfile, it.mutationId, it.version, it.encoded, createdAt) }
    }

    private fun insertPending(
        remoteProfile: String,
        mutationId: String,
        version: WorkspaceEntityVersionV2,
        encoded: PreparedWorkspaceOutboxObjectV2,
        createdAt: Instant,
    ) {
        queries.insertPendingMutationSystemV2(
            remoteProfile,
            syncEpochId,
            mutationId,
            version.versionId,
            version.objectDigest,
            encoded.writerDeviceId,
            encoded.encodedOuter,
            createdAt.toEpochMilliseconds(),
        )
    }

    private fun rebuildAllProjections(rebuiltAt: Instant) {
        queries.deleteNoteProjectionsSystemV2(syncEpochId)
        queries.deleteNotebookProjectionsV2(syncEpochId)
        queries.deleteWorkspacePreferencesProjectionV2(syncEpochId)
        val keys = queries.selectWorkspaceEntityKeysV2(syncEpochId).executeAsList().map { row ->
            WorkspaceEntityKeyV2(checkNotNull(WorkspaceEntityTypeV2.fromWire(row.entity_type)), row.entity_id)
        }
        keys.forEach { key -> queries.deleteProjectionWarningsForEntityV2(syncEpochId, key.entityType.wireValue, key.entityId) }
        persistProjectionKeys(keys, rebuiltAt)
    }

    private fun rebuildAffectedProjections(
        changedKeys: Set<WorkspaceEntityKeyV2>,
        rebuiltAt: Instant,
    ) {
        if (changedKeys.isEmpty()) {
            return
        }
        val keys = linkedSetOf<WorkspaceEntityKeyV2>()
        changedKeys.forEach { key ->
            keys += key
            if (key.entityType == WorkspaceEntityTypeV2.NOTEBOOK) {
                queries.selectNoteIdsReferencingNotebookSystemV2(syncEpochId, key.entityId)
                    .executeAsList()
                    .forEach { noteId ->
                        keys += WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)
                    }
                keys += WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                )
            }
        }
        persistProjectionKeys(keys, rebuiltAt)
    }

    private fun persistProjectionKeys(
        keys: Collection<WorkspaceEntityKeyV2>,
        rebuiltAt: Instant,
    ) {
        keys.forEach { key ->
            queries.deleteProjectionWarningsForEntityV2(syncEpochId, key.entityType.wireValue, key.entityId)
        }
        keys.filter { it.entityType == WorkspaceEntityTypeV2.NOTEBOOK }.forEach { key ->
            persistNotebookProjection(loadProjection(key) ?: return@forEach)
        }
        keys.filter { it.entityType == WorkspaceEntityTypeV2.NOTE }.forEach { key ->
            persistNoteProjection(loadProjection(key) ?: return@forEach, rebuiltAt)
        }
        keys.filter { it.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES }.forEach { key ->
            persistPreferencesProjection(loadProjection(key) ?: return@forEach, rebuiltAt)
        }
    }

    private fun persistNotebookProjection(snapshot: WorkspaceProjectionSnapshotV2) {
        val content = snapshot.content as? NotebookContentV2
        val deletion = snapshot.deletion
        queries.upsertNotebookProjectionV2(
            syncEpochId,
            snapshot.key.entityId,
            snapshot.preferredHeadVersionId,
            snapshot.status.name.lowercase(),
            content?.title,
            content?.sortOrder,
            content?.notebookCreatedAt?.epochSeconds,
            content?.notebookCreatedAt?.nanosecondsOfSecond?.toLong(),
            deletion?.deletedAt?.epochSeconds,
            deletion?.deletedAt?.nanosecondsOfSecond?.toLong(),
            snapshot.warning,
            snapshot.authoredAt?.epochSeconds,
            snapshot.authoredAt?.nanosecondsOfSecond?.toLong(),
        )
    }

    private fun persistNoteProjection(snapshot: WorkspaceProjectionSnapshotV2, at: Instant) {
        val content = snapshot.content as? NoteContentV2
        val location = content?.location
        val deletion = snapshot.deletion
        queries.upsertNoteProjectionSystemV2(
            syncEpochId,
            snapshot.key.entityId,
            snapshot.preferredHeadVersionId,
            snapshot.status.name.lowercase(),
            snapshot.referencedEntityId,
            snapshot.effectiveEntityId,
            content?.title,
            content?.markdownBody,
            content?.noteCreatedAt?.epochSeconds,
            content?.noteCreatedAt?.nanosecondsOfSecond?.toLong(),
            content?.timeZoneId,
            location?.latitude,
            location?.longitude,
            location?.placeText,
            location?.accuracyMeters,
            location?.altitudeMeters,
            location?.capturedAt?.epochSeconds,
            location?.capturedAt?.nanosecondsOfSecond?.toLong(),
            deletion?.deletedAt?.epochSeconds,
            deletion?.deletedAt?.nanosecondsOfSecond?.toLong(),
            snapshot.warning,
            snapshot.authoredAt?.epochSeconds,
            snapshot.authoredAt?.nanosecondsOfSecond?.toLong(),
        )
        snapshot.warning?.let { warning ->
            queries.insertProjectionWarningV2(
                syncEpochId,
                snapshot.key.entityType.wireValue,
                snapshot.key.entityId,
                warning,
                snapshot.referencedEntityId?.let { WorkspaceEntityTypeV2.NOTEBOOK.wireValue },
                snapshot.referencedEntityId,
                at.toEpochMilliseconds(),
            )
        }
    }

    private fun persistPreferencesProjection(snapshot: WorkspaceProjectionSnapshotV2, at: Instant) {
        val content = snapshot.content as? WorkspacePreferencesV2
        queries.upsertWorkspacePreferencesProjectionV2(
            syncEpochId,
            snapshot.preferredHeadVersionId,
            snapshot.status.name.lowercase(),
            content?.theme?.wireValue,
            content?.previewByDefault?.toDbBooleanV2(),
            content?.markdownToolbarVisible?.toDbBooleanV2(),
            snapshot.referencedEntityId,
            snapshot.effectiveEntityId,
            snapshot.warning,
            snapshot.authoredAt?.epochSeconds,
            snapshot.authoredAt?.nanosecondsOfSecond?.toLong(),
        )
        snapshot.warning?.let { warning ->
            queries.insertProjectionWarningV2(
                syncEpochId,
                snapshot.key.entityType.wireValue,
                snapshot.key.entityId,
                warning,
                snapshot.referencedEntityId?.let { WorkspaceEntityTypeV2.NOTEBOOK.wireValue },
                snapshot.referencedEntityId,
                at.toEpochMilliseconds(),
            )
        }
    }

    private fun isNotebookLive(notebookId: String): Boolean {
        val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, notebookId)
        val heads = loadHeads(key)
        return heads.size == 1 && heads.single().kind == WorkspaceEntityVersionKindV2.CONTENT
    }

    private fun mapStoredNoteListProjection(row: Note_projections_system_v2): StoredNoteListProjectionV2? =
        mapStoredNoteListProjection(
            noteId = row.note_id,
            preferredHeadVersionId = row.preferred_head_version_id,
            referencedNotebookId = row.referenced_notebook_id,
            effectiveNotebookId = row.effective_notebook_id,
            title = row.title,
            markdownBody = row.markdown_body,
            createdSeconds = row.note_created_at_seconds,
            createdNanos = row.note_created_at_nanos,
            timeZoneId = row.time_zone_id,
            locationPlaceText = row.location_place_text,
            warning = row.warning,
            authoredSeconds = row.authored_at_seconds,
            authoredNanos = row.authored_at_nanos,
        )

    private fun mapStoredNoteListProjection(
        row: SelectContentNoteProjectionsByEffectiveNotebookSystemV2,
    ): StoredNoteListProjectionV2? =
        mapStoredNoteListProjection(
            noteId = row.note_id,
            preferredHeadVersionId = row.preferred_head_version_id,
            referencedNotebookId = row.referenced_notebook_id,
            effectiveNotebookId = row.effective_notebook_id,
            title = row.title,
            markdownBody = row.markdown_body,
            createdSeconds = row.note_created_at_seconds,
            createdNanos = row.note_created_at_nanos,
            timeZoneId = row.time_zone_id,
            locationPlaceText = row.location_place_text,
            warning = row.warning,
            authoredSeconds = row.authored_at_seconds,
            authoredNanos = row.authored_at_nanos,
        )

    private fun mapStoredNoteListProjection(
        noteId: String,
        preferredHeadVersionId: String?,
        referencedNotebookId: String?,
        effectiveNotebookId: String?,
        title: String?,
        markdownBody: String?,
        createdSeconds: Long?,
        createdNanos: Long?,
        timeZoneId: String?,
        locationPlaceText: String?,
        warning: String?,
        authoredSeconds: Long?,
        authoredNanos: Long?,
    ): StoredNoteListProjectionV2? {
        val createdAtSeconds = createdSeconds ?: return null
        val createdAtNanos = createdNanos ?: 0L
        val listTitle = title ?: return null
        val listBody = markdownBody ?: return null
        return StoredNoteListProjectionV2(
            noteId = noteId,
            preferredHeadVersionId = preferredHeadVersionId,
            referencedNotebookId = referencedNotebookId,
            effectiveNotebookId = effectiveNotebookId,
            title = listTitle,
            markdownBody = listBody,
            noteCreatedAt = Instant.fromEpochSeconds(createdAtSeconds, createdAtNanos),
            timeZoneId = timeZoneId,
            locationPlaceText = locationPlaceText,
            warning = warning,
            authoredAt = authoredSeconds?.let { seconds ->
                Instant.fromEpochSeconds(seconds, authoredNanos ?: 0L)
            },
        )
    }

    private fun decodeStoredVersion(row: Workspace_entity_versions_v2): WorkspaceEntityVersionV2 {
        val decoded = wireCodec.decode(
            row.canonical_payload,
            WorkspaceVersionOuterMetadataV2(row.epoch_id, row.version_id, row.object_digest),
        )
        val version = (decoded as? WorkspaceEntityWireDecodeResultV2.Decoded)?.version
            ?: error("Stored V2 entity envelope failed canonical validation.")
        val parentRows = queries.selectWorkspaceEntityParentsV2(row.epoch_id, row.version_id).executeAsList()
        check(version.parentVersionIds == parentRows)
        check(version.contractId == row.contract_id)
        check(version.schemaSetVersion == row.schema_set_version)
        check(version.entityType.wireValue == row.entity_type && version.entityId == row.entity_id)
        check(version.kind.wireValue == row.kind && version.payloadDigest == row.payload_digest)
        return version
    }

    private fun mapConflictState(row: Workspace_entity_conflicts_v2): WorkspaceConflictStateV2 {
        val heads = queries.selectWorkspaceEntityConflictHeadsV2(row.epoch_id, row.conflict_id).executeAsList()
        val descriptor = WorkspaceConflictDescriptorV2(
            row.conflict_id,
            row.epoch_id,
            checkNotNull(WorkspaceEntityTypeV2.fromWire(row.entity_type)),
            row.entity_id,
            heads,
            row.base_version_id,
            WorkspaceConflictReasonV2.entries.single { it.wireValue == row.reason },
            row.conflicting_fields.split(',').filter(String::isNotEmpty).toSet(),
        )
        return WorkspaceConflictStateV2(
            descriptor,
            WorkspaceConflictLifecycleV2.entries.single { it.wireValue == row.lifecycle },
            row.superseded_by_conflict_id,
            row.resolved_by_version_id,
        )
    }

    private fun remoteUnitShapeError(unit: RemoteWorkspaceCursorUnitV2): WorkspaceStoreErrorV2? {
        val seenMutations = mutableMapOf<String, RemoteWorkspaceMutationV2>()
        val available = loadAllVersions().mapTo(mutableSetOf()) { it.versionId }
        unit.mutations.forEach { mutation ->
            if (!UUID_V4_PATTERN_SYSTEM_V2.matches(mutation.mutationId) ||
                mutation.objectId != mutation.version.versionId ||
                mutation.objectDigest != mutation.version.objectDigest ||
                mutation.version.syncEpochId != syncEpochId ||
                materializer.expectedDeterministicMutationId(mutation.version)
                    ?.let { it != mutation.mutationId } == true
            ) return invalidMutation("Remote mutation outer identity does not match its immutable version.")
            val prior = seenMutations.put(mutation.mutationId, mutation)
            if (prior != null && prior != mutation) return WorkspaceStoreErrorV2(
                WorkspaceStoreErrorCodeV2.MUTATION_OBJECT_MISMATCH,
                "One cursor unit reuses a mutation id for unequal objects.",
            )
            mutation.version.parentVersionIds.forEach { parent ->
                if (parent !in available) {
                    val later = unit.mutations.any { it.objectId == parent }
                    return WorkspaceStoreErrorV2(
                        if (later) WorkspaceStoreErrorCodeV2.NON_TOPOLOGICAL_UNIT else WorkspaceStoreErrorCodeV2.MISSING_PARENT,
                        if (later) "A cursor unit places a child before its parent." else "A cursor unit is missing a required same-entity parent.",
                    )
                }
            }
            available += mutation.objectId
        }
        return null
    }

    private fun invalidMutation(message: String) =
        WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.INVALID_MUTATION, message)

    private fun cursorMismatch(message: String) =
        WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.CURSOR_STATE_MISMATCH, message)
}

private fun Sync_pending_mutations_system_v2.toDomainV2() = StoredWorkspacePendingMutationV2(
    remote_profile,
    epoch_id,
    mutation_id,
    object_id,
    object_digest,
    writer_device_id,
    encoded_outer,
    created_at,
    last_attempt_at,
    attempt_count,
)

private fun Sync_applied_mutations_system_v2.toDomainV2() = StoredWorkspaceAppliedMutationV2(
    remote_profile,
    epoch_id,
    mutation_id,
    object_id,
    object_digest,
    first_writer_device_id,
    applied_at,
)

private fun Sync_remote_cursors_system_v2.toDomainV2() = StoredWorkspaceCursorV2(
    remote_profile,
    epoch_id,
    stream_id,
    cursor_value,
    unit_id,
    unit_digest,
    updated_at,
)

private fun Boolean.toDbBooleanV2(): Long = if (this) 1L else 0L
