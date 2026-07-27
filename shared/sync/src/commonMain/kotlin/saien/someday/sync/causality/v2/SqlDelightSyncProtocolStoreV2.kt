@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package saien.someday.sync.causality.v2

import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.local.db.Sync_dead_letters_v2
import saien.someday.data.local.db.Sync_epochs_v2
import saien.someday.data.local.db.Sync_run_history_v2
import saien.someday.data.local.db.Sync_transport_units_system_v2
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SyncEpochLifecycleV2(val storageValue: String) {
    PREPARING("preparing"),
    ACTIVE("active"),
    READ_ONLY("read_only"),
    BLOCKED("blocked"),
    ABANDONED("abandoned"),
}

enum class SyncEpochHealthV2(val storageValue: String) {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    BLOCKED("blocked"),
}

data class StoredSyncEpochV2(
    val remoteProfile: String,
    val authorityBindingId: String?,
    val descriptor: SyncEpochDescriptorV2,
    val descriptorDigest: String,
    val lifecycle: SyncEpochLifecycleV2,
    val health: SyncEpochHealthV2,
    val activatedAtEpochMilliseconds: Long?,
    val readOnlyAtEpochMilliseconds: Long?,
    val retainUntilEpochMilliseconds: Long?,
    val safeErrorCode: String?,
    val safeErrorMessage: String?,
)

data class StoredLocalAuthorityV2(
    val remoteProfile: String,
    val epochId: String,
    val localWriterDeviceId: String,
    val authorityBindingId: String,
    val pointerDigest: String,
    val updatedAtEpochMilliseconds: Long,
)

data class StoredSyncReconciliationStateV2(
    val reason: String,
    val markedAtEpochMilliseconds: Long,
)

sealed interface SyncEpochPersistResultV2 {
    data class Stored(val epoch: StoredSyncEpochV2) : SyncEpochPersistResultV2
    data class AlreadyStored(val epoch: StoredSyncEpochV2) : SyncEpochPersistResultV2
    data class ImmutableMismatch(val safeMessage: String) : SyncEpochPersistResultV2
}

enum class SyncDeadLetterFailureClassV2(val storageValue: String) {
    RETRYABLE_DEPENDENCY("retryable_dependency"),
    PERSISTENT_INTEGRITY("persistent_integrity"),
    INCOMPATIBLE_EPOCH("incompatible_epoch"),
    TRANSPORT("transport"),
}

enum class SyncDeadLetterLifecycleV2(val storageValue: String) {
    ACTIVE("active"),
    REPAIRED("repaired"),
    REBOOTSTRAP("rebootstrap"),
}

data class SyncDeadLetterInputV2(
    val remoteProfile: String,
    val epochId: String,
    val streamId: String,
    val unitId: String,
    val cursorValue: String?,
    val unitDigest: String?,
    val objectId: String?,
    val objectDigest: String?,
    val authenticatedUnit: String?,
    val failureClass: SyncDeadLetterFailureClassV2,
    val safeErrorCode: String,
    val safeErrorMessage: String,
)

data class StoredSyncDeadLetterV2(
    val input: SyncDeadLetterInputV2,
    val lifecycle: SyncDeadLetterLifecycleV2,
    val firstSeenAtEpochMilliseconds: Long,
    val lastSeenAtEpochMilliseconds: Long,
    val lastRetryAtEpochMilliseconds: Long?,
    val retryCount: Long,
)

enum class SyncRunStatusV2(val storageValue: String) {
    RUNNING("running"),
    SUCCESS("success"),
    BLOCKED("blocked"),
    FAILED("failed"),
}

data class SyncRunCountersV2(
    val pulledUnits: Long = 0,
    val pulledObjects: Long = 0,
    val pushedObjects: Long = 0,
    val autoMergedEntities: Long = 0,
    val activeConflicts: Long = 0,
    val storedVersions: Long = 0,
    val replays: Long = 0,
    val fastForwards: Long = 0,
    val equivalentMerges: Long = 0,
    val deletionMerges: Long = 0,
    val fieldMerges: Long = 0,
    val activeNoteConflicts: Long = 0,
    val activeNotebookConflicts: Long = 0,
    val activePreferenceConflicts: Long = 0,
    val supersededConflicts: Long = 0,
    val projectionWarnings: Long = 0,
    val deadLetters: Long = 0,
    val pushedMutations: Long = 0,
    val checkpointHorizonEpochMilliseconds: Long? = null,
    val repairState: SyncRunRepairStateV2 = SyncRunRepairStateV2.HEALTHY,
)

enum class SyncRunRepairStateV2(val storageValue: String) {
    HEALTHY("healthy"),
    REPAIR_REQUIRED("repair_required"),
    REBOOTSTRAP_REQUIRED("rebootstrap_required"),
}

data class StoredSyncRunV2(
    val runId: String,
    val remoteProfile: String,
    val contractId: String,
    val epochId: String?,
    val startedAtEpochMilliseconds: Long,
    val finishedAtEpochMilliseconds: Long?,
    val status: SyncRunStatusV2,
    val counters: SyncRunCountersV2,
    val safeErrorCode: String?,
    val safeErrorMessage: String?,
)

data class SyncRetentionPlanV2(
    val retainedEpochIds: List<String>,
    val eligibleReadOnlyEpochIds: List<String>,
    val blockedEpochIds: List<String>,
)

data class StoredWorkspaceTransportUnitV2(
    val remoteProfile: String,
    val epochId: String,
    val streamId: String,
    val unitId: String,
    val unitDigest: String,
    val previousUnitDigest: String?,
    val ordinal: Long,
    val encodedUnitOuter: String,
    val orderedMutationTuples: String,
    val state: String,
    val createdAtEpochMilliseconds: Long,
    val publishedAtEpochMilliseconds: Long?,
)

sealed interface SealWorkspaceTransportUnitResultV2 {
    data class Sealed(val value: StoredWorkspaceTransportUnitV2, val replayed: Boolean) : SealWorkspaceTransportUnitResultV2
    data class ImmutableMismatch(val safeMessage: String) : SealWorkspaceTransportUnitResultV2
}

/** Durable control-plane/outbox/dead-letter state shared by every v2 remote profile. */
class SqlDelightSyncProtocolStoreV2(
    private val database: SomedayDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    private val queries = database.somedayQueries

    fun sealWorkspaceTransportUnit(value: StoredWorkspaceTransportUnitV2): SealWorkspaceTransportUnitResultV2 {
        require(value.state == "sealed" && value.ordinal >= 1)
        val atOrdinal = queries.selectTransportUnitsSystemV2(value.remoteProfile, value.epochId, value.streamId)
            .executeAsList()
            .firstOrNull { it.ordinal_value == value.ordinal }
            ?.toWorkspaceDomainV2()
        val byId = queries.selectTransportUnitSystemV2(
            value.remoteProfile, value.epochId, value.streamId, value.unitId,
        ).executeAsOneOrNull()?.toWorkspaceDomainV2()
        val existing = byId ?: atOrdinal
        if (existing != null) {
            return if (existing.sameSealedIdentityV2(value)) {
                SealWorkspaceTransportUnitResultV2.Sealed(existing, replayed = true)
            } else {
                SealWorkspaceTransportUnitResultV2.ImmutableMismatch(
                    "A durable V2 transport ordinal or unit id is already sealed differently.",
                )
            }
        }
        queries.insertTransportUnitSystemV2(
            value.remoteProfile, value.epochId, value.streamId, value.unitId, value.unitDigest,
            value.previousUnitDigest, value.ordinal, value.encodedUnitOuter,
            value.orderedMutationTuples, value.state, value.createdAtEpochMilliseconds,
            value.publishedAtEpochMilliseconds,
        )
        return SealWorkspaceTransportUnitResultV2.Sealed(
            checkNotNull(queries.selectTransportUnitSystemV2(
                value.remoteProfile, value.epochId, value.streamId, value.unitId,
            ).executeAsOneOrNull()?.toWorkspaceDomainV2()),
            replayed = false,
        )
    }

    fun loadOpenWorkspaceTransportUnits(
        remoteProfile: String,
        epochId: String,
        streamId: String,
    ): List<StoredWorkspaceTransportUnitV2> = queries.selectOpenTransportUnitsSystemV2(
        remoteProfile, epochId, streamId,
    ).executeAsList().map { it.toWorkspaceDomainV2() }

    fun loadWorkspaceTransportUnits(
        remoteProfile: String,
        epochId: String,
        streamId: String,
    ): List<StoredWorkspaceTransportUnitV2> = queries.selectTransportUnitsSystemV2(
        remoteProfile, epochId, streamId,
    ).executeAsList().map { it.toWorkspaceDomainV2() }

    fun updateWorkspaceTransportUnitState(
        remoteProfile: String,
        epochId: String,
        streamId: String,
        unitId: String,
        state: String,
        publishedAt: Instant?,
    ) {
        require(state in setOf("sealed", "object_published", "committed", "superseded"))
        queries.updateTransportUnitStateSystemV2(
            state, publishedAt?.toEpochMilliseconds(), remoteProfile, epochId, streamId, unitId,
        )
    }

    fun persistPreparingEpoch(
        remoteProfile: String,
        descriptor: SyncEpochDescriptorV2,
        descriptorDigest: String,
    ): SyncEpochPersistResultV2 {
        val existing = loadEpoch(remoteProfile, descriptor.syncEpochId)
        if (existing != null) {
            return if (existing.descriptor == descriptor && existing.descriptorDigest == descriptorDigest) {
                SyncEpochPersistResultV2.AlreadyStored(existing)
            } else {
                SyncEpochPersistResultV2.ImmutableMismatch(
                    "The same v2 epoch id already identifies another authenticated descriptor.",
                )
            }
        }
        queries.insertSyncEpochV2(
            remote_profile = remoteProfile,
            epoch_id = descriptor.syncEpochId,
            lifecycle = SyncEpochLifecycleV2.PREPARING.storageValue,
            health = SyncEpochHealthV2.HEALTHY.storageValue,
            semantic_protocol_version = descriptor.semanticProtocolVersion.toLong(),
            minimum_writer_version = descriptor.minimumWriterProtocolVersion.toLong(),
            key_set_version = descriptor.keySetVersion,
            supported_offline_window_seconds = descriptor.supportedOfflineWindowSeconds,
            descriptor_json = json.encodeToString(descriptor),
            descriptor_digest = descriptorDigest,
            checkpoint_id = descriptor.checkpointId,
            checkpoint_digest = descriptor.checkpointDigest,
            previous_epoch_id = descriptor.previousEpochId,
            created_at = descriptor.createdAt.toEpochMilliseconds(),
            activated_at = null,
            read_only_at = null,
            retain_until = null,
            safe_error_code = null,
            safe_error_message = null,
        )
        return SyncEpochPersistResultV2.Stored(checkNotNull(loadEpoch(remoteProfile, descriptor.syncEpochId)))
    }

    fun activateEpoch(
        remoteProfile: String,
        epochId: String,
        activatedAt: Instant,
        localWriterDeviceId: String? = null,
        authorityBindingId: String? = null,
    ): StoredSyncEpochV2 {
        database.transaction {
            val target = requireNotNull(loadEpoch(remoteProfile, epochId)) { "Cannot activate an unknown v2 epoch." }
            require(target.lifecycle == SyncEpochLifecycleV2.PREPARING || target.lifecycle == SyncEpochLifecycleV2.ACTIVE) {
                "Only a prepared v2 epoch can become active."
            }
            val activatedAtMillis = activatedAt.toEpochMilliseconds()
            // A late device must not restart the archive window on the day it
            // finally observes a rollover.  The authenticated successor
            // checkpoint time is the durable publication evidence shared by
            // every device, so all devices derive the same retention horizon.
            val successorCreatedAtMillis = target.descriptor.createdAt.toEpochMilliseconds()
            val binding = authorityBindingId
                ?: loadLocalAuthority()?.takeIf {
                    it.remoteProfile == remoteProfile
                }?.authorityBindingId
                ?: "unbound:$remoteProfile"
            require(binding.isNotBlank() && binding.length <= 2_048)
            val previousCandidates = loadAllEpochs().filter { previous ->
                !(previous.remoteProfile == remoteProfile && previous.descriptor.syncEpochId == epochId) &&
                    previous.activatedAtEpochMilliseconds != null &&
                    previous.lifecycle != SyncEpochLifecycleV2.READ_ONLY &&
                    previous.lifecycle != SyncEpochLifecycleV2.ABANDONED &&
                    (
                        previous.lifecycle == SyncEpochLifecycleV2.ACTIVE ||
                            previous.lifecycle == SyncEpochLifecycleV2.BLOCKED ||
                            target.descriptor.previousEpochId == previous.descriptor.syncEpochId
                    )
            }
            previousCandidates.forEach { previous ->
                val retainUntil = safeAddMilliseconds(
                    successorCreatedAtMillis,
                    previous.descriptor.supportedOfflineWindowSeconds * 1_000L,
                )
                queries.updateSyncEpochLifecycleV2(
                    lifecycle = SyncEpochLifecycleV2.READ_ONLY.storageValue,
                    health = SyncEpochHealthV2.HEALTHY.storageValue,
                    activated_at = previous.activatedAtEpochMilliseconds,
                    read_only_at = activatedAtMillis,
                    retain_until = retainUntil,
                    safe_error_code = null,
                    safe_error_message = null,
                    remote_profile = previous.remoteProfile,
                    epoch_id = previous.descriptor.syncEpochId,
                )
            }
            queries.updateSyncEpochLifecycleV2(
                lifecycle = SyncEpochLifecycleV2.ACTIVE.storageValue,
                health = SyncEpochHealthV2.HEALTHY.storageValue,
                activated_at = target.activatedAtEpochMilliseconds ?: activatedAtMillis,
                read_only_at = null,
                retain_until = null,
                safe_error_code = null,
                safe_error_message = null,
                remote_profile = remoteProfile,
                epoch_id = epochId,
            )
            queries.updateSyncEpochAuthorityBindingV2(binding, remoteProfile, epochId)
            val writer = localWriterDeviceId
                ?: loadLocalAuthority()?.takeIf {
                    it.remoteProfile == remoteProfile && it.epochId == epochId
                }?.localWriterDeviceId
                ?: target.descriptor.createdByDeviceId
            require(UUID_V4_PATTERN_SYSTEM_V2.matches(writer))
            queries.upsertLocalAuthoritySystemV2(
                remote_profile = remoteProfile,
                epoch_id = epochId,
                local_writer_device_id = writer,
                authority_binding_id = binding,
                pointer_digest = target.descriptorDigest,
                updated_at = activatedAtMillis,
            )
        }
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    fun blockEpoch(
        remoteProfile: String,
        epochId: String,
        safeErrorCode: String,
        safeErrorMessage: String,
    ): StoredSyncEpochV2 {
        require(safeErrorCode.isNotBlank() && safeErrorMessage.isNotBlank())
        val epoch = requireNotNull(loadEpoch(remoteProfile, epochId))
        queries.updateSyncEpochLifecycleV2(
            lifecycle = SyncEpochLifecycleV2.BLOCKED.storageValue,
            health = SyncEpochHealthV2.BLOCKED.storageValue,
            activated_at = epoch.activatedAtEpochMilliseconds,
            read_only_at = epoch.readOnlyAtEpochMilliseconds,
            retain_until = epoch.retainUntilEpochMilliseconds,
            safe_error_code = safeErrorCode,
            safe_error_message = safeErrorMessage,
            remote_profile = remoteProfile,
            epoch_id = epochId,
        )
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    fun setEpochHealth(
        remoteProfile: String,
        epochId: String,
        health: SyncEpochHealthV2,
        safeErrorCode: String? = null,
        safeErrorMessage: String? = null,
    ): StoredSyncEpochV2 {
        require(health != SyncEpochHealthV2.BLOCKED) { "Use blockEpoch for a blocking v2 integrity failure." }
        require((safeErrorCode == null) == (safeErrorMessage == null))
        val epoch = requireNotNull(loadEpoch(remoteProfile, epochId))
        require(epoch.lifecycle == SyncEpochLifecycleV2.ACTIVE || epoch.lifecycle == SyncEpochLifecycleV2.PREPARING)
        queries.updateSyncEpochLifecycleV2(
            lifecycle = epoch.lifecycle.storageValue,
            health = health.storageValue,
            activated_at = epoch.activatedAtEpochMilliseconds,
            read_only_at = epoch.readOnlyAtEpochMilliseconds,
            retain_until = epoch.retainUntilEpochMilliseconds,
            safe_error_code = safeErrorCode,
            safe_error_message = safeErrorMessage,
            remote_profile = remoteProfile,
            epoch_id = epochId,
        )
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    fun resumeBlockedEpochAfterRepair(
        remoteProfile: String,
        epochId: String,
    ): StoredSyncEpochV2 {
        require(loadActiveDeadLetters(remoteProfile, epochId).none {
            it.input.failureClass == SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY ||
                it.input.failureClass == SyncDeadLetterFailureClassV2.INCOMPATIBLE_EPOCH
        }) {
            "Cannot resume a v2 epoch while a blocking integrity dead letter remains."
        }
        val epoch = requireNotNull(loadEpoch(remoteProfile, epochId))
        require(epoch.lifecycle == SyncEpochLifecycleV2.BLOCKED)
        queries.updateSyncEpochLifecycleV2(
            lifecycle = if (epoch.activatedAtEpochMilliseconds == null) {
                SyncEpochLifecycleV2.PREPARING.storageValue
            } else {
                SyncEpochLifecycleV2.ACTIVE.storageValue
            },
            health = SyncEpochHealthV2.HEALTHY.storageValue,
            activated_at = epoch.activatedAtEpochMilliseconds,
            read_only_at = epoch.readOnlyAtEpochMilliseconds,
            retain_until = epoch.retainUntilEpochMilliseconds,
            safe_error_code = null,
            safe_error_message = null,
            remote_profile = remoteProfile,
            epoch_id = epochId,
        )
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    /**
     * Releases protocol-work pins only after an explicitly authorized
     * successor checkpoint has committed every locally verified head. The old
     * semantic DAG remains read-only until its normal retention horizon.
     */
    fun archiveAfterAuthorizedRebootstrap(
        remoteProfile: String,
        epochId: String,
        archivedAt: Instant,
    ): StoredSyncEpochV2 {
        val epoch = requireNotNull(loadEpoch(remoteProfile, epochId))
        require(epoch.lifecycle == SyncEpochLifecycleV2.READ_ONLY) {
            "Authorized recovery cleanup requires a committed successor epoch."
        }
        val at = archivedAt.toEpochMilliseconds()
        database.transaction {
            queries.markEpochDeadLettersRebootstrapV2(at, remoteProfile, epochId)
            queries.markEpochQuarantinesRebootstrapV2(at, remoteProfile, epochId)
            queries.markEpochSourceImportsPublishedV2(at, remoteProfile, epochId)
            // These are retry/cache state, not semantic history. Every current
            // head they could publish was included in the committed successor.
            queries.deleteEpochPendingMutationsSystemV2(remoteProfile, epochId)
            queries.deleteEpochTransportUnitsSystemV2(remoteProfile, epochId)
            queries.deleteEpochRepairReplicasSystemV2(remoteProfile, epochId)
            queries.updateSyncEpochLifecycleV2(
                lifecycle = SyncEpochLifecycleV2.READ_ONLY.storageValue,
                health = SyncEpochHealthV2.BLOCKED.storageValue,
                activated_at = epoch.activatedAtEpochMilliseconds,
                read_only_at = epoch.readOnlyAtEpochMilliseconds ?: at,
                retain_until = epoch.retainUntilEpochMilliseconds,
                safe_error_code = "authorized_rebootstrap_archive",
                safe_error_message = "This prior epoch is retained as a read-only recovery archive and is not monitored for new writes.",
                remote_profile = remoteProfile,
                epoch_id = epochId,
            )
        }
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    fun abandonPreparingEpoch(
        remoteProfile: String,
        epochId: String,
        safeErrorCode: String,
        safeErrorMessage: String,
    ): StoredSyncEpochV2 {
        require(safeErrorCode.isNotBlank() && safeErrorMessage.isNotBlank())
        val epoch = requireNotNull(loadEpoch(remoteProfile, epochId))
        require(epoch.lifecycle == SyncEpochLifecycleV2.PREPARING || epoch.lifecycle == SyncEpochLifecycleV2.ABANDONED) {
            "Only a non-authoritative prepared V2 epoch may be abandoned."
        }
        queries.updateSyncEpochLifecycleV2(
            lifecycle = SyncEpochLifecycleV2.ABANDONED.storageValue,
            health = SyncEpochHealthV2.HEALTHY.storageValue,
            activated_at = null,
            read_only_at = epoch.readOnlyAtEpochMilliseconds,
            retain_until = epoch.retainUntilEpochMilliseconds,
            safe_error_code = safeErrorCode,
            safe_error_message = safeErrorMessage,
            remote_profile = remoteProfile,
            epoch_id = epochId,
        )
        return checkNotNull(loadEpoch(remoteProfile, epochId))
    }

    fun loadEpoch(remoteProfile: String, epochId: String): StoredSyncEpochV2? =
        queries.selectSyncEpochV2(remoteProfile, epochId).executeAsOneOrNull()?.toDomain()

    fun loadActiveEpoch(remoteProfile: String): StoredSyncEpochV2? =
        queries.selectActiveSyncEpochV2(remoteProfile).executeAsOneOrNull()?.toDomain()

    fun loadAuthoritativeEpoch(): StoredSyncEpochV2? =
        queries.selectAuthoritativeSyncEpochV2().executeAsOneOrNull()?.toDomain()

    fun loadLocalAuthority(): StoredLocalAuthorityV2? =
        queries.selectLocalAuthoritySystemV2().executeAsOneOrNull()?.let { row ->
            StoredLocalAuthorityV2(
                row.remote_profile,
                row.epoch_id,
                row.local_writer_device_id,
                row.authority_binding_id,
                row.pointer_digest,
                row.updated_at,
            )
        }

    fun rebindExactAuthority(
        remoteProfile: String,
        epochId: String,
        pointerDigest: String,
        authorityBindingId: String,
        updatedAt: Instant,
    ): StoredLocalAuthorityV2 {
        require(authorityBindingId.isNotBlank() && authorityBindingId.length <= 2_048)
        val current = requireNotNull(loadLocalAuthority())
        require(current.remoteProfile == remoteProfile && current.epochId == epochId &&
            current.pointerDigest == pointerDigest
        ) { "Only an endpoint exposing the exact authenticated authority may be rebound without migration." }
        queries.upsertLocalAuthoritySystemV2(
            remote_profile = current.remoteProfile,
            epoch_id = current.epochId,
            local_writer_device_id = current.localWriterDeviceId,
            authority_binding_id = authorityBindingId,
            pointer_digest = current.pointerDigest,
            updated_at = updatedAt.toEpochMilliseconds(),
        )
        return checkNotNull(loadLocalAuthority())
    }

    fun markBackupReconciliationPending(markedAt: Instant) {
        queries.markBackupReconciliationPendingSystemV2(markedAt.toEpochMilliseconds())
    }

    fun loadReconciliationState(): StoredSyncReconciliationStateV2? =
        queries.selectReconciliationStateSystemV2().executeAsOneOrNull()?.let { row ->
            StoredSyncReconciliationStateV2(row.reason, row.marked_at)
        }

    fun clearBackupReconciliation() {
        queries.clearBackupReconciliationSystemV2()
    }

    fun loadEpochs(remoteProfile: String): List<StoredSyncEpochV2> =
        queries.selectSyncEpochsV2ByProfile(remoteProfile).executeAsList().map { it.toDomain() }

    fun loadAllEpochs(): List<StoredSyncEpochV2> =
        queries.selectAllSyncEpochsV2().executeAsList().map { it.toDomain() }

    /**
     * True when this device still holds V2 protocol state encrypted under the
     * current workspace key for [remoteProfile] (or any profile when null).
     *
     * Includes preparing (checkpoint not yet activated), active, blocked, and
     * read-only retained epochs. Abandoned rows are ignored.
     */
    fun hasKeyBoundLocalV2State(remoteProfile: String? = null): Boolean {
        val epochs = if (remoteProfile == null) loadAllEpochs() else loadEpochs(remoteProfile)
        return epochs.any { epoch ->
            epoch.lifecycle in setOf(
                SyncEpochLifecycleV2.PREPARING,
                SyncEpochLifecycleV2.ACTIVE,
                SyncEpochLifecycleV2.BLOCKED,
                SyncEpochLifecycleV2.READ_ONLY,
            )
        }
    }

    fun retentionPlan(remoteProfile: String, now: Instant): SyncRetentionPlanV2 {
        val nowMillis = now.toEpochMilliseconds()
        val epochs = loadEpochs(remoteProfile)
        return SyncRetentionPlanV2(
            retainedEpochIds = epochs.filterNot { epoch ->
                epoch.lifecycle == SyncEpochLifecycleV2.READ_ONLY &&
                    epoch.retainUntilEpochMilliseconds?.let { it <= nowMillis } == true
            }.map { it.descriptor.syncEpochId }.sorted(),
            eligibleReadOnlyEpochIds = epochs.filter { epoch ->
                epoch.lifecycle == SyncEpochLifecycleV2.READ_ONLY &&
                    epoch.retainUntilEpochMilliseconds?.let { it <= nowMillis } == true
            }.map { it.descriptor.syncEpochId }.sorted(),
            blockedEpochIds = epochs.filter { it.lifecycle == SyncEpochLifecycleV2.BLOCKED }
                .map { it.descriptor.syncEpochId }
                .sorted(),
        )
    }

    fun recordDeadLetter(input: SyncDeadLetterInputV2, observedAt: Instant): StoredSyncDeadLetterV2 {
        val existing = queries.selectSyncDeadLetterV2(
            input.remoteProfile,
            input.epochId,
            input.streamId,
            input.unitId,
        ).executeAsOneOrNull()?.toDomain()
        val now = observedAt.toEpochMilliseconds()
        queries.insertOrReplaceSyncDeadLetterV2(
            remote_profile = input.remoteProfile,
            epoch_id = input.epochId,
            stream_id = input.streamId,
            unit_id = input.unitId,
            cursor_value = input.cursorValue,
            unit_digest = input.unitDigest,
            object_id = input.objectId,
            object_digest = input.objectDigest,
            authenticated_unit = input.authenticatedUnit,
            failure_class = input.failureClass.storageValue,
            lifecycle = SyncDeadLetterLifecycleV2.ACTIVE.storageValue,
            safe_error_code = input.safeErrorCode,
            safe_error_message = input.safeErrorMessage,
            first_seen_at = existing?.firstSeenAtEpochMilliseconds ?: now,
            last_seen_at = now,
            last_retry_at = existing?.lastSeenAtEpochMilliseconds,
            retry_count = (existing?.retryCount ?: -1L) + 1L,
        )
        if (input.failureClass == SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY ||
            input.failureClass == SyncDeadLetterFailureClassV2.INCOMPATIBLE_EPOCH
        ) {
            blockEpoch(input.remoteProfile, input.epochId, input.safeErrorCode, input.safeErrorMessage)
        }
        return checkNotNull(
            queries.selectSyncDeadLetterV2(input.remoteProfile, input.epochId, input.streamId, input.unitId)
                .executeAsOneOrNull()
                ?.toDomain(),
        )
    }

    fun loadActiveDeadLetters(remoteProfile: String, epochId: String): List<StoredSyncDeadLetterV2> =
        queries.selectActiveSyncDeadLettersV2(remoteProfile, epochId).executeAsList().map { it.toDomain() }

    fun resolveDeadLetter(
        remoteProfile: String,
        epochId: String,
        streamId: String,
        unitId: String,
        lifecycle: SyncDeadLetterLifecycleV2,
        resolvedAt: Instant,
    ) {
        require(lifecycle != SyncDeadLetterLifecycleV2.ACTIVE)
        queries.updateSyncDeadLetterLifecycleV2(
            lifecycle.storageValue,
            resolvedAt.toEpochMilliseconds(),
            remoteProfile,
            epochId,
            streamId,
            unitId,
        )
    }

    fun startRun(remoteProfile: String, epochId: String?, startedAt: Instant): StoredSyncRunV2 {
        val runId = Uuid.random().toString()
        val run = StoredSyncRunV2(
            runId = runId,
            remoteProfile = remoteProfile,
            contractId = SYNC_V2_CONTRACT_ID,
            epochId = epochId,
            startedAtEpochMilliseconds = startedAt.toEpochMilliseconds(),
            finishedAtEpochMilliseconds = null,
            status = SyncRunStatusV2.RUNNING,
            counters = SyncRunCountersV2(),
            safeErrorCode = null,
            safeErrorMessage = null,
        )
        queries.insertSyncRunHistoryV2(
            run_id = runId,
            remote_profile = remoteProfile,
            epoch_id = epochId,
            started_at = run.startedAtEpochMilliseconds,
            finished_at = null,
            status = SyncRunStatusV2.RUNNING.storageValue,
            pulled_units = 0,
            pulled_objects = 0,
            pushed_objects = 0,
            auto_merged_entities = 0,
            active_conflicts = 0,
            safe_error_code = null,
            safe_error_message = null,
            contract_id = SYNC_V2_CONTRACT_ID,
            stored_versions = 0,
            replays = 0,
            fast_forwards = 0,
            equivalent_merges = 0,
            deletion_merges = 0,
            field_merges = 0,
            active_note_conflicts = 0,
            active_notebook_conflicts = 0,
            active_preference_conflicts = 0,
            superseded_conflicts = 0,
            projection_warnings = 0,
            dead_letters = 0,
            pushed_mutations = 0,
            checkpoint_horizon = null,
            repair_state = SyncRunRepairStateV2.HEALTHY.storageValue,
        )
        return run
    }

    fun finishRun(
        runId: String,
        status: SyncRunStatusV2,
        counters: SyncRunCountersV2,
        finishedAt: Instant,
        epochId: String? = null,
        safeErrorCode: String? = null,
        safeErrorMessage: String? = null,
    ) {
        require(status != SyncRunStatusV2.RUNNING)
        require((safeErrorCode == null) == (safeErrorMessage == null))
        queries.updateSyncRunHistoryV2(
            finished_at = finishedAt.toEpochMilliseconds(),
            epoch_id = epochId,
            status = status.storageValue,
            pulled_units = counters.pulledUnits,
            pulled_objects = counters.pulledObjects,
            pushed_objects = counters.pushedObjects,
            auto_merged_entities = counters.autoMergedEntities,
            active_conflicts = counters.activeConflicts,
            safe_error_code = safeErrorCode,
            safe_error_message = safeErrorMessage,
            stored_versions = counters.storedVersions,
            replays = counters.replays,
            fast_forwards = counters.fastForwards,
            equivalent_merges = counters.equivalentMerges,
            deletion_merges = counters.deletionMerges,
            field_merges = counters.fieldMerges,
            active_note_conflicts = counters.activeNoteConflicts,
            active_notebook_conflicts = counters.activeNotebookConflicts,
            active_preference_conflicts = counters.activePreferenceConflicts,
            superseded_conflicts = counters.supersededConflicts,
            projection_warnings = counters.projectionWarnings,
            dead_letters = counters.deadLetters,
            pushed_mutations = counters.pushedMutations,
            checkpoint_horizon = counters.checkpointHorizonEpochMilliseconds,
            repair_state = counters.repairState.storageValue,
            run_id = runId,
        )
        queries.pruneSyncRunHistoryV2(
            safeSubtractMilliseconds(finishedAt.toEpochMilliseconds(), RUN_HISTORY_RETENTION_MILLISECONDS_V2),
        )
    }

    fun loadRuns(remoteProfile: String, limit: Int = 100): List<StoredSyncRunV2> =
        queries.selectSyncRunHistoryV2(remoteProfile, limit.coerceIn(1, 500).toLong())
            .executeAsList()
            .map { it.toDomain() }

    private fun Sync_epochs_v2.toDomain(): StoredSyncEpochV2 = StoredSyncEpochV2(
        remoteProfile = remote_profile,
        authorityBindingId = authority_binding_id,
        descriptor = json.decodeFromString(descriptor_json),
        descriptorDigest = descriptor_digest,
        lifecycle = enumValues<SyncEpochLifecycleV2>().first { it.storageValue == lifecycle },
        health = enumValues<SyncEpochHealthV2>().first { it.storageValue == health },
        activatedAtEpochMilliseconds = activated_at,
        readOnlyAtEpochMilliseconds = read_only_at,
        retainUntilEpochMilliseconds = retain_until,
        safeErrorCode = safe_error_code,
        safeErrorMessage = safe_error_message,
    )

    private fun Sync_dead_letters_v2.toDomain(): StoredSyncDeadLetterV2 = StoredSyncDeadLetterV2(
        input = SyncDeadLetterInputV2(
            remoteProfile = remote_profile,
            epochId = epoch_id,
            streamId = stream_id,
            unitId = unit_id,
            cursorValue = cursor_value,
            unitDigest = unit_digest,
            objectId = object_id,
            objectDigest = object_digest,
            authenticatedUnit = authenticated_unit,
            failureClass = enumValues<SyncDeadLetterFailureClassV2>().first { it.storageValue == failure_class },
            safeErrorCode = safe_error_code,
            safeErrorMessage = safe_error_message,
        ),
        lifecycle = enumValues<SyncDeadLetterLifecycleV2>().first { it.storageValue == lifecycle },
        firstSeenAtEpochMilliseconds = first_seen_at,
        lastSeenAtEpochMilliseconds = last_seen_at,
        lastRetryAtEpochMilliseconds = last_retry_at,
        retryCount = retry_count,
    )

    private fun Sync_run_history_v2.toDomain(): StoredSyncRunV2 = StoredSyncRunV2(
        runId = run_id,
        remoteProfile = remote_profile,
        contractId = contract_id,
        epochId = epoch_id,
        startedAtEpochMilliseconds = started_at,
        finishedAtEpochMilliseconds = finished_at,
        status = enumValues<SyncRunStatusV2>().first { it.storageValue == status },
        counters = SyncRunCountersV2(
            pulledUnits = pulled_units,
            pulledObjects = pulled_objects,
            pushedObjects = pushed_objects,
            autoMergedEntities = auto_merged_entities,
            activeConflicts = active_conflicts,
            storedVersions = stored_versions,
            replays = replays,
            fastForwards = fast_forwards,
            equivalentMerges = equivalent_merges,
            deletionMerges = deletion_merges,
            fieldMerges = field_merges,
            activeNoteConflicts = active_note_conflicts,
            activeNotebookConflicts = active_notebook_conflicts,
            activePreferenceConflicts = active_preference_conflicts,
            supersededConflicts = superseded_conflicts,
            projectionWarnings = projection_warnings,
            deadLetters = dead_letters,
            pushedMutations = pushed_mutations,
            checkpointHorizonEpochMilliseconds = checkpoint_horizon,
            repairState = enumValues<SyncRunRepairStateV2>().first { it.storageValue == repair_state },
        ),
        safeErrorCode = safe_error_code,
        safeErrorMessage = safe_error_message,
    )

    private fun Sync_transport_units_system_v2.toWorkspaceDomainV2() = StoredWorkspaceTransportUnitV2(
        remote_profile, epoch_id, stream_id, unit_id, unit_digest, previous_unit_digest,
        ordinal_value, encoded_unit_outer, ordered_mutation_tuples, state, created_at, published_at,
    )
}

private fun safeAddMilliseconds(value: Long, increment: Long): Long =
    if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

private fun safeSubtractMilliseconds(value: Long, decrement: Long): Long =
    if (value < Long.MIN_VALUE + decrement) Long.MIN_VALUE else value - decrement

private const val RUN_HISTORY_RETENTION_MILLISECONDS_V2: Long = 30L * 24L * 60L * 60L * 1_000L

private fun StoredWorkspaceTransportUnitV2.sameSealedIdentityV2(other: StoredWorkspaceTransportUnitV2): Boolean =
    remoteProfile == other.remoteProfile && epochId == other.epochId && streamId == other.streamId &&
        unitId == other.unitId && unitDigest == other.unitDigest && previousUnitDigest == other.previousUnitDigest &&
        ordinal == other.ordinal && encodedUnitOuter == other.encodedUnitOuter &&
        orderedMutationTuples == other.orderedMutationTuples && createdAtEpochMilliseconds == other.createdAtEpochMilliseconds
