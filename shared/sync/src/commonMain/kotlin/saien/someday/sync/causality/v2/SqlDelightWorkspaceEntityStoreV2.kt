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

/** Lightweight, causally ordered identity used to plan one outbox drain. */
data class WorkspacePendingPublicationPlanItemV2(
    val mutationId: String,
    val objectId: String,
)

/** Exact durable outbox tuple paired with its already-validated immutable version. */
data class StoredWorkspacePendingPublicationV2(
    val pending: StoredWorkspacePendingMutationV2,
    val version: WorkspaceEntityVersionV2,
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

data class WorkspaceStoreStateCountsV2(
    val activeConflictsByEntityType: Map<WorkspaceEntityTypeV2, Int>,
    val projectionWarnings: Int,
) {
    val activeConflicts: Int = activeConflictsByEntityType.values.sum()
}

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

internal const val MAX_REMOTE_APPLY_BATCH_UNITS_V2: Int = 200
internal const val MAX_REMOTE_APPLY_BATCH_MUTATIONS_V2: Int = 200

sealed interface WorkspaceRemoteUnitApplyResultV2 {
    sealed interface Success : WorkspaceRemoteUnitApplyResultV2

    data class Applied(
        val plans: Map<WorkspaceEntityKeyV2, WorkspaceReconciliationPlanV2>,
        val replayedMutations: Int,
        val cursor: StoredWorkspaceCursorV2,
    ) : Success

    data class AlreadyApplied(val cursor: StoredWorkspaceCursorV2) : Success

    data class Rejected(val error: WorkspaceStoreErrorV2) : WorkspaceRemoteUnitApplyResultV2
}

internal data class WorkspaceRemoteBatchCommittedUnitV2(
    val inputIndex: Int,
    val result: WorkspaceRemoteUnitApplyResultV2.Success,
)

internal sealed interface WorkspaceRemoteBatchApplyResultV2 {
    data class Committed(
        val units: List<WorkspaceRemoteBatchCommittedUnitV2>,
    ) : WorkspaceRemoteBatchApplyResultV2

    data class Rejected(
        val failedUnitIndex: Int,
        val error: WorkspaceStoreErrorV2,
    ) : WorkspaceRemoteBatchApplyResultV2
}

private data class PreparedEntityPlanV2(
    val incoming: List<WorkspaceEntityVersionV2>,
    val plan: WorkspaceReconciliationPlanV2,
    val versionsById: Map<String, WorkspaceEntityVersionV2>,
    val existingConflictIds: Set<String>,
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

    /** Aggregate sync-run telemetry without reconstructing entity DAGs or projections. */
    fun loadStateCounts(): WorkspaceStoreStateCountsV2 = WorkspaceStoreStateCountsV2(
        activeConflictsByEntityType = queries.selectActiveWorkspaceEntityConflictCountsV2(syncEpochId)
            .executeAsList()
            .associate { row ->
                checkNotNull(WorkspaceEntityTypeV2.fromWire(row.entity_type)) to row.conflict_count.toInt()
            },
        projectionWarnings = queries.selectProjectionWarningCountV2(syncEpochId).executeAsOne().toInt(),
    )

    fun loadPending(remoteProfile: String): List<StoredWorkspacePendingMutationV2> =
        queries.selectPendingMutationsSystemV2(remoteProfile, syncEpochId)
            .executeAsList()
            .map(Sync_pending_mutations_system_v2::toDomainV2)

    /**
     * Takes one lightweight snapshot of the current outbox in causal publication order.
     * Callers may drain it in bounded transport batches, then request another plan to
     * include product mutations committed concurrently with that drain.
     */
    fun loadPendingPublicationPlan(remoteProfile: String): List<WorkspacePendingPublicationPlanItemV2> =
        queries.selectPendingPublicationPlanSystemV2(remoteProfile, syncEpochId)
            .executeAsList()
            .map { WorkspacePendingPublicationPlanItemV2(it.mutation_id, it.object_id) }

    /** Loads only the exact durable tuples named by a bounded publication-plan slice. */
    fun loadPendingPublicationBatch(
        remoteProfile: String,
        plan: List<WorkspacePendingPublicationPlanItemV2>,
    ): List<StoredWorkspacePendingPublicationV2> {
        if (plan.isEmpty()) return emptyList()
        val planByMutation = plan.associateBy { it.mutationId }
        require(planByMutation.size == plan.size) { "A V2 publication plan repeated a mutation identity." }
        val pendingByMutation = queries.selectPendingMutationsByIdsSystemV2(
            remoteProfile,
            syncEpochId,
            planByMutation.keys,
        ).executeAsList().associateBy { it.mutation_id }
        val versionsById = loadStoredVersionsByIds(pendingByMutation.values.map { it.object_id }).associateBy { it.versionId }
        return plan.mapNotNull { item ->
            // An earlier acknowledgement in this same drain may retire a source
            // tuple that was present when the lightweight plan was captured.
            val pending = pendingByMutation[item.mutationId] ?: return@mapNotNull null
            require(pending.object_id == item.objectId) { "A planned V2 outbox mutation changed its immutable object identity." }
            val version = checkNotNull(versionsById[item.objectId]) { "A durable V2 outbox entity is missing." }
            require(version.objectDigest == pending.object_digest) {
                "A durable V2 outbox tuple no longer matches its immutable entity."
            }
            StoredWorkspacePendingPublicationV2(pending.toDomainV2(), version)
        }
    }

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
            rebuildAffectedProjections(prepared, mutations.first().createdAt)
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
            val mutationIds = unit.mutations.map { it.mutationId }.distinct()
            val objectIds = unit.mutations.map { it.objectId }.distinct()
            val appliedByMutation = if (mutationIds.isEmpty()) emptyMap() else {
                queries.selectAppliedMutationsByIdsSystemV2(unit.remoteProfile, syncEpochId, mutationIds)
                    .executeAsList()
                    .associateBy { it.mutation_id }
            }
            val storedByObject = loadStoredVersionsByIds(objectIds).associateBy { it.versionId }
            var replayed = 0
            val unapplied = mutableListOf<RemoteWorkspaceMutationV2>()
            val toMaterialize = mutableListOf<RemoteWorkspaceMutationV2>()
            unit.mutations.forEach { mutation ->
                val applied = appliedByMutation[mutation.mutationId]
                val stored = storedByObject[mutation.objectId]
                when {
                    applied == null -> {
                        if (stored != null && stored != mutation.version) {
                            result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                                WorkspaceStoreErrorV2(
                                    WorkspaceStoreErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH,
                                    "Version id is already bound to another immutable envelope.",
                                ),
                            )
                            return@transaction
                        }
                        unapplied += mutation
                        if (stored == null) {
                            toMaterialize += mutation
                        } else {
                            // The immutable entity and its projection were committed atomically
                            // by a local write. This remote unit only establishes replay identity.
                            replayed++
                        }
                    }
                    applied.object_id == mutation.objectId && applied.object_digest == mutation.objectDigest -> {
                        if (stored != mutation.version) {
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
            val remotelyAcknowledgedPending = mutableListOf<RemoteWorkspaceMutationV2>()
            val pendingRows = if (unapplied.isEmpty()) emptyList() else {
                queries.selectPendingMutationsByIdentitiesSystemV2(
                    unit.remoteProfile,
                    syncEpochId,
                    unapplied.map { it.mutationId }.distinct(),
                    unapplied.map { it.objectId }.distinct(),
                ).executeAsList()
            }
            val pendingByMutation = pendingRows.associateBy { it.mutation_id }
            val pendingByObject = pendingRows.associateBy { it.object_id }
            unapplied.forEach { mutation ->
                val byMutation = pendingByMutation[mutation.mutationId]
                val byObject = pendingByObject[mutation.objectId]
                if (byMutation != null && byObject != null && byMutation.mutation_id != byObject.mutation_id) {
                    result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                        WorkspaceStoreErrorV2(
                            WorkspaceStoreErrorCodeV2.OUTBOX_IDENTITY_MISMATCH,
                            "A remote mutation intersects two different durable V2 outbox identities.",
                        ),
                    )
                    return@transaction
                }
                val pending = byMutation ?: byObject
                if (pending != null) {
                    if (pending.mutation_id != mutation.mutationId ||
                        pending.object_id != mutation.objectId ||
                        pending.object_digest != mutation.objectDigest
                    ) {
                        result = WorkspaceRemoteUnitApplyResultV2.Rejected(
                            WorkspaceStoreErrorV2(
                                WorkspaceStoreErrorCodeV2.OUTBOX_IDENTITY_MISMATCH,
                                "A remote mutation conflicts with a durable V2 outbox identity.",
                            ),
                        )
                        return@transaction
                    }
                    remotelyAcknowledgedPending += mutation
                }
            }
            if (result != null) return@transaction
            val prepared = when (val value = prepareEntityPlans(toMaterialize.map { it.version })) {
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
            unapplied.forEach { mutation ->
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
            remotelyAcknowledgedPending.forEach { mutation ->
                check(
                    removePendingPublication(
                        unit.remoteProfile,
                        mutation.mutationId,
                        mutation.objectId,
                        mutation.objectDigest,
                        committedAt,
                    ),
                ) { "An exact remote mutation did not acknowledge its durable V2 outbox tuple." }
            }
            persistGeneratedOutbox(unit.remoteProfile, generatedOutbox, unit.appliedAt)
            rebuildAffectedProjections(prepared, unit.appliedAt)
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

    /**
     * Co-commits a bounded set of already ordered protocol units without merging their identities.
     * Every nested unit still advances its own authenticated cursor; only the local durability
     * boundary is shared. A rejected unit rolls the complete batch back.
     */
    internal fun applyRemoteCursorUnitsAtomically(
        units: List<RemoteWorkspaceCursorUnitV2>,
    ): WorkspaceRemoteBatchApplyResultV2 {
        require(units.isNotEmpty()) { "A remote apply batch must contain at least one cursor unit." }
        require(units.size <= MAX_REMOTE_APPLY_BATCH_UNITS_V2) {
            "A remote apply batch exceeds its cursor-unit bound."
        }
        require(units.sumOf { it.mutations.size } <= MAX_REMOTE_APPLY_BATCH_MUTATIONS_V2) {
            "A remote apply batch exceeds its mutation bound."
        }
        return database.transactionWithResult {
            val committed = ArrayList<WorkspaceRemoteBatchCommittedUnitV2>(units.size)
            units.forEachIndexed { index, unit ->
                when (val result = applyRemoteCursorUnit(unit)) {
                    is WorkspaceRemoteUnitApplyResultV2.Applied ->
                        committed += WorkspaceRemoteBatchCommittedUnitV2(index, result)
                    is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied ->
                        committed += WorkspaceRemoteBatchCommittedUnitV2(index, result)
                    is WorkspaceRemoteUnitApplyResultV2.Rejected -> rollback(
                        WorkspaceRemoteBatchApplyResultV2.Rejected(index, result.error),
                    )
                }
            }
            WorkspaceRemoteBatchApplyResultV2.Committed(committed)
        }
    }

    fun acknowledgePending(
        remoteProfile: String,
        mutationId: String,
        objectId: String,
        objectDigest: String,
    ): Boolean {
        var removed = false
        database.transaction {
            removed = removePendingPublication(
                remoteProfile,
                mutationId,
                objectId,
                objectDigest,
                kotlin.time.Clock.System.now().toEpochMilliseconds(),
            )
        }
        return removed
    }

    private fun removePendingPublication(
        remoteProfile: String,
        mutationId: String,
        objectId: String,
        objectDigest: String,
        publishedAtEpochMilliseconds: Long,
    ): Boolean {
        val row = queries.selectPendingMutationSystemV2(remoteProfile, syncEpochId, mutationId)
            .executeAsOneOrNull() ?: return false
        require(row.object_id == objectId && row.object_digest == objectDigest) {
            "V2 acknowledgement must match mutation, object id, and object digest exactly."
        }
        val removed = queries.deletePendingMutationSystemV2(
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
                        publishedAtEpochMilliseconds,
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
                content is NoteContentV2 &&
                    content.timeZoneId != null &&
                    runCatching { TimeZone.of(content.timeZoneId) }.isFailure -> "time_zone_fallback"
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
        val distinctIncoming = incoming.distinctBy { it.versionId }
        val grouped = distinctIncoming.groupBy { it.key }
        val exactStoredById = loadStoredVersionsByIds(distinctIncoming.map { it.versionId })
            .associateBy { it.versionId }
        val storedByKey = loadStoredVersionsByKeys(grouped.keys)
        distinctIncoming.forEach { version ->
            val stored = exactStoredById[version.versionId]
            if (stored != null && stored != version) {
                return PrepareEntityPlansResultV2.Rejected(
                    WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH, "Version id is already bound to another immutable envelope."),
                )
            }
        }
        val conflictsByKey = loadStoredConflictsByKeys(grouped.keys)
        val output = linkedMapOf<WorkspaceEntityKeyV2, PreparedEntityPlanV2>()
        grouped.keys.sortedWith(compareBy({ it.entityType.wireValue }, { it.entityId })).forEach { key ->
            val values = checkNotNull(grouped[key])
            val stored = storedByKey[key].orEmpty()
            val all = (stored + values).distinctBy { it.versionId }
            val storedConflicts = conflictsByKey[key].orEmpty()
            val known = storedConflicts.map { it.descriptor }
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
                    versionsById = (all + reconciled.plan.generatedVersions).associateBy { it.versionId },
                    existingConflictIds = storedConflicts.mapTo(mutableSetOf()) { it.descriptor.conflictId },
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
            .forEach(::insertPreparedVersion)
        prepared.values
            .flatMap { it.plan.generatedVersions }
            .distinctBy { it.versionId }
            .sortedWith(compareBy({ it.generation }, { it.entityType.wireValue }, { it.entityId }, { it.versionId }))
            .forEach(::insertPreparedVersion)
        prepared.values.sortedWith(compareBy({ it.plan.key.entityType.wireValue }, { it.plan.key.entityId })).forEach { value ->
            persistHeads(value.plan)
            persistConflicts(value.plan, value.existingConflictIds, committedAtEpochMilliseconds)
        }
    }

    /** [prepareEntityPlans] proved these immutable ids absent in the same write transaction. */
    private fun insertPreparedVersion(version: WorkspaceEntityVersionV2) {
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

    private fun persistConflicts(
        plan: WorkspaceReconciliationPlanV2,
        existingConflictIds: Set<String>,
        detectedAt: Long,
    ) {
        plan.conflictStates.filter { it.lifecycle != WorkspaceConflictLifecycleV2.ACTIVE }.forEach { state ->
            if (state.descriptor.conflictId !in existingConflictIds) {
                insertConflict(state, detectedAt)
            } else {
                queries.updateWorkspaceEntityConflictV2(
                    state.lifecycle.wireValue,
                    state.supersededByConflictId,
                    state.resolvedByVersionId,
                    syncEpochId,
                    state.descriptor.conflictId,
                )
            }
        }
        plan.conflictStates.filter { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }.forEach { state ->
            if (state.descriptor.conflictId !in existingConflictIds) {
                insertConflict(state, detectedAt)
            } else {
                queries.updateWorkspaceEntityConflictV2(
                    state.lifecycle.wireValue,
                    null,
                    null,
                    syncEpochId,
                    state.descriptor.conflictId,
                )
            }
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
        prepared: Map<WorkspaceEntityKeyV2, PreparedEntityPlanV2>,
        rebuiltAt: Instant,
    ) {
        if (prepared.isEmpty()) return

        val referencedNotebookIds = prepared.values.mapNotNullTo(mutableSetOf()) { value ->
            val headId = value.plan.finalHeadVersionIds.singleOrNull() ?: return@mapNotNullTo null
            when (val content = value.versionsById[headId]?.contentPayload) {
                is NoteContentV2 -> content.notebookId
                is WorkspacePreferencesV2 -> content.defaultNotebookId
                is NotebookContentV2, null -> null
            }
        }
        val liveNotebookIds = referencedNotebookIds.chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2)
            .flatMapTo(mutableSetOf()) { notebookIds ->
                queries.selectNotebookProjectionsByIdsV2(syncEpochId, notebookIds)
                    .executeAsList()
                    .filter { it.state == WorkspaceProjectionStatusV2.CONTENT.name.lowercase() }
                    .map { it.notebook_id }
            }
        prepared.values.filter { it.plan.key.entityType == WorkspaceEntityTypeV2.NOTEBOOK }.forEach { value ->
            val notebookId = value.plan.key.entityId
            val head = value.plan.finalHeadVersionIds.singleOrNull()?.let(value.versionsById::get)
            if (head?.kind == WorkspaceEntityVersionKindV2.CONTENT) {
                liveNotebookIds += notebookId
            } else {
                liveNotebookIds -= notebookId
            }
        }

        val preparedSnapshots = prepared.values.map { value ->
            projectionSnapshot(value.plan, value.versionsById, liveNotebookIds)
        }
        preparedSnapshots.forEach { snapshot ->
            queries.deleteProjectionWarningsForEntityV2(
                syncEpochId,
                snapshot.key.entityType.wireValue,
                snapshot.key.entityId,
            )
        }
        preparedSnapshots.filter { it.key.entityType == WorkspaceEntityTypeV2.NOTEBOOK }
            .forEach(::persistNotebookProjection)
        preparedSnapshots.filter { it.key.entityType == WorkspaceEntityTypeV2.NOTE }
            .forEach { persistNoteProjection(it, rebuiltAt) }
        preparedSnapshots.filter { it.key.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES }
            .forEach { persistPreferencesProjection(it, rebuiltAt) }

        val secondaryKeys = linkedSetOf<WorkspaceEntityKeyV2>()
        prepared.keys.forEach { key ->
            if (key.entityType == WorkspaceEntityTypeV2.NOTEBOOK) {
                queries.selectNoteIdsReferencingNotebookSystemV2(syncEpochId, key.entityId)
                    .executeAsList()
                    .forEach { noteId ->
                        secondaryKeys += WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)
                    }
                secondaryKeys += WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                )
            }
        }
        secondaryKeys.removeAll(prepared.keys)
        persistProjectionKeys(secondaryKeys, rebuiltAt)
    }

    private fun projectionSnapshot(
        plan: WorkspaceReconciliationPlanV2,
        versionsById: Map<String, WorkspaceEntityVersionV2>,
        liveNotebookIds: Set<String>,
    ): WorkspaceProjectionSnapshotV2 {
        if (plan.finalHeadVersionIds.size != 1) {
            return WorkspaceProjectionSnapshotV2(
                plan.key,
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
        val head = checkNotNull(versionsById[plan.finalHeadVersionIds.single()]) {
            "A prepared V2 reconciliation plan does not contain its final immutable head."
        }
        if (head.kind == WorkspaceEntityVersionKindV2.DELETION) {
            return WorkspaceProjectionSnapshotV2(
                plan.key,
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
        val targetLive = reference == null || reference in liveNotebookIds
        return WorkspaceProjectionSnapshotV2(
            plan.key,
            WorkspaceProjectionStatusV2.CONTENT,
            head.versionId,
            content,
            null,
            reference,
            when (plan.key.entityType) {
                WorkspaceEntityTypeV2.NOTE -> if (targetLive) reference else RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES -> reference?.takeIf { targetLive }
                WorkspaceEntityTypeV2.NOTEBOOK -> null
            },
            when {
                reference != null && !targetLive && plan.key.entityType == WorkspaceEntityTypeV2.NOTE -> "unresolved_notebook_reference"
                reference != null && !targetLive -> "unresolved_default_notebook_reference"
                content is NoteContentV2 && content.timeZoneId != null && runCatching { TimeZone.of(content.timeZoneId) }.isFailure -> "time_zone_fallback"
                else -> null
            },
            head.authoredAt,
        )
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

    private fun loadStoredVersionsByIds(versionIds: Collection<String>): List<WorkspaceEntityVersionV2> {
        if (versionIds.isEmpty()) return emptyList()
        val rows = versionIds.distinct().chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2).flatMap { ids ->
            queries.selectWorkspaceEntityVersionsByIdsV2(syncEpochId, ids).executeAsList()
        }
        return decodeStoredVersions(rows)
    }

    private fun loadStoredVersionsByKeys(
        keys: Collection<WorkspaceEntityKeyV2>,
    ): Map<WorkspaceEntityKeyV2, List<WorkspaceEntityVersionV2>> {
        if (keys.isEmpty()) return emptyMap()
        val rows = keys.groupBy { it.entityType }.flatMap { (entityType, typedKeys) ->
            typedKeys.map { it.entityId }.distinct().chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2).flatMap { entityIds ->
                queries.selectWorkspaceEntityVersionsByEntitiesV2(
                    syncEpochId,
                    entityType.wireValue,
                    entityIds,
                ).executeAsList()
            }
        }
        return decodeStoredVersions(rows).groupBy { it.key }
    }

    private fun decodeStoredVersions(
        rows: List<Workspace_entity_versions_v2>,
    ): List<WorkspaceEntityVersionV2> {
        if (rows.isEmpty()) return emptyList()
        val parentsByVersion = rows.map { it.version_id }.distinct()
            .chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2)
            .flatMap { versionIds ->
                queries.selectWorkspaceEntityParentsByVersionsV2(syncEpochId, versionIds).executeAsList()
            }
            .groupBy({ it.version_id }, { it.parent_version_id })
        return rows.map { row -> decodeStoredVersion(row, parentsByVersion[row.version_id].orEmpty()) }
    }

    private fun decodeStoredVersion(row: Workspace_entity_versions_v2): WorkspaceEntityVersionV2 =
        decodeStoredVersion(
            row,
            queries.selectWorkspaceEntityParentsV2(row.epoch_id, row.version_id).executeAsList(),
        )

    private fun decodeStoredVersion(
        row: Workspace_entity_versions_v2,
        parentRows: List<String>,
    ): WorkspaceEntityVersionV2 {
        val decoded = wireCodec.decode(
            row.canonical_payload,
            WorkspaceVersionOuterMetadataV2(row.epoch_id, row.version_id, row.object_digest),
        )
        val version = (decoded as? WorkspaceEntityWireDecodeResultV2.Decoded)?.version
            ?: error("Stored V2 entity envelope failed canonical validation.")
        check(version.parentVersionIds == parentRows)
        check(version.contractId == row.contract_id)
        check(version.schemaSetVersion == row.schema_set_version)
        check(version.entityType.wireValue == row.entity_type && version.entityId == row.entity_id)
        check(version.kind.wireValue == row.kind && version.payloadDigest == row.payload_digest)
        return version
    }

    private fun loadStoredConflictsByKeys(
        keys: Collection<WorkspaceEntityKeyV2>,
    ): Map<WorkspaceEntityKeyV2, List<WorkspaceConflictStateV2>> {
        if (keys.isEmpty()) return emptyMap()
        val rows = keys.groupBy { it.entityType }.flatMap { (entityType, typedKeys) ->
            typedKeys.map { it.entityId }.distinct().chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2).flatMap { entityIds ->
                queries.selectWorkspaceEntityConflictsByEntitiesV2(
                    syncEpochId,
                    entityType.wireValue,
                    entityIds,
                ).executeAsList()
            }
        }
        val headsByConflict = rows.map { it.conflict_id }.distinct()
            .chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2)
            .flatMap { conflictIds ->
                queries.selectWorkspaceEntityConflictHeadsByConflictsV2(syncEpochId, conflictIds).executeAsList()
            }
            .groupBy({ it.conflict_id }, { it.head_version_id })
        return rows.map { row -> mapConflictState(row, headsByConflict[row.conflict_id].orEmpty()) }
            .groupBy { state ->
                WorkspaceEntityKeyV2(state.descriptor.entityType, state.descriptor.entityId)
            }
    }

    private fun mapConflictState(row: Workspace_entity_conflicts_v2): WorkspaceConflictStateV2 {
        val heads = queries.selectWorkspaceEntityConflictHeadsV2(row.epoch_id, row.conflict_id).executeAsList()
        return mapConflictState(row, heads)
    }

    private fun mapConflictState(
        row: Workspace_entity_conflicts_v2,
        heads: List<String>,
    ): WorkspaceConflictStateV2 {
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
        val unitObjectIds = unit.mutations.mapTo(mutableSetOf()) { it.objectId }
        val earlierObjectIds = mutableSetOf<String>()
        val externallyRequiredParents = mutableSetOf<String>()
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
            mutation.version.parentVersionIds.forEach parent@ { parentVersionId ->
                if (parentVersionId !in earlierObjectIds) {
                    val later = parentVersionId in unitObjectIds
                    if (!later) {
                        externallyRequiredParents += parentVersionId
                        return@parent
                    }
                    return WorkspaceStoreErrorV2(
                        WorkspaceStoreErrorCodeV2.NON_TOPOLOGICAL_UNIT,
                        "A cursor unit places a child before its parent.",
                    )
                }
            }
            earlierObjectIds += mutation.objectId
        }
        if (externallyRequiredParents.isNotEmpty()) {
            val existing = externallyRequiredParents.chunked(SQLITE_COLLECTION_QUERY_LIMIT_V2)
                .flatMapTo(mutableSetOf()) { ids ->
                    queries.selectExistingWorkspaceEntityVersionIdsV2(syncEpochId, ids).executeAsList()
                }
            if (!existing.containsAll(externallyRequiredParents)) {
                return WorkspaceStoreErrorV2(
                    WorkspaceStoreErrorCodeV2.MISSING_PARENT,
                    "A cursor unit is missing a required same-entity parent.",
                )
            }
        }
        return null
    }

    private fun invalidMutation(message: String) =
        WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.INVALID_MUTATION, message)

    private fun cursorMismatch(message: String) =
        WorkspaceStoreErrorV2(WorkspaceStoreErrorCodeV2.CURSOR_STATE_MISMATCH, message)

    private companion object {
        // Remains below the 999-variable ceiling of older Android SQLite builds.
        const val SQLITE_COLLECTION_QUERY_LIMIT_V2 = 500
    }
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
