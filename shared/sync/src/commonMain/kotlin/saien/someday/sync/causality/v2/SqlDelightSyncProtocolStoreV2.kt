@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package saien.someday.sync.causality.v2

import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.local.db.Sync_dead_letters_v2
import saien.someday.data.local.db.Sync_epochs_v2
import saien.someday.data.local.db.Sync_run_history_v2
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SyncEpochLifecycleV2(val storageValue: String) {
    PREPARING("preparing"),
    ACTIVE("active"),
    BLOCKED("blocked"),
    ABANDONED("abandoned"),
}

enum class SyncEpochHealthV2(val storageValue: String) {
    HEALTHY("healthy"),
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
)

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

    fun persistPreparingEpoch(
        remoteProfile: String,
        descriptor: SyncEpochDescriptorV2,
        descriptorDigest: String,
        authorityBindingId: String? = null,
        localWriterDeviceId: String? = null,
    ): SyncEpochPersistResultV2 {
        require((authorityBindingId == null) == (localWriterDeviceId == null)) {
            "A prepared authority binding and its local writer must be persisted together."
        }
        authorityBindingId?.let {
            require(it.isNotBlank() && it.length <= 2_048)
            require(UUID_V4_PATTERN_SYSTEM_V2.matches(checkNotNull(localWriterDeviceId)))
        }
        var outcome: SyncEpochPersistResultV2? = null
        database.transaction {
            outcome = persistPreparingEpochInCurrentTransaction(
                remoteProfile = remoteProfile,
                descriptor = descriptor,
                descriptorDigest = descriptorDigest,
                authorityBindingId = authorityBindingId,
                localWriterDeviceId = localWriterDeviceId,
            )
            if (outcome is SyncEpochPersistResultV2.ImmutableMismatch) {
                rollback()
            }
        }
        return checkNotNull(outcome)
    }

    /** Epoch metadata and its first local authority are one crash-consistent SQLite fact. */
    private fun persistPreparingEpochInCurrentTransaction(
        remoteProfile: String,
        descriptor: SyncEpochDescriptorV2,
        descriptorDigest: String,
        authorityBindingId: String?,
        localWriterDeviceId: String?,
    ): SyncEpochPersistResultV2 {
        val existing = loadEpoch(remoteProfile, descriptor.syncEpochId)
        if (existing != null) {
            if (existing.descriptor != descriptor || existing.descriptorDigest != descriptorDigest) {
                return SyncEpochPersistResultV2.ImmutableMismatch(
                    "The same v2 epoch id already identifies another authenticated descriptor.",
                )
            }
            if (authorityBindingId != null &&
                existing.authorityBindingId?.let { it != authorityBindingId } == true
            ) {
                return SyncEpochPersistResultV2.ImmutableMismatch(
                    "The prepared epoch is already bound to another authenticated authority.",
                )
            }
            if (authorityBindingId != null && existing.authorityBindingId == null) {
                queries.updateSyncEpochAuthorityBindingV2(
                    authorityBindingId,
                    remoteProfile,
                    descriptor.syncEpochId,
                )
            }
            val bindingError = bindFirstPreparingLocalAuthority(
                remoteProfile,
                descriptor.syncEpochId,
                descriptorDigest,
                authorityBindingId,
                localWriterDeviceId,
            )
            return if (bindingError == null) {
                SyncEpochPersistResultV2.AlreadyStored(
                    checkNotNull(loadEpoch(remoteProfile, descriptor.syncEpochId)),
                )
            } else {
                SyncEpochPersistResultV2.ImmutableMismatch(
                    bindingError,
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
            created_at = descriptor.createdAt.toEpochMilliseconds(),
            activated_at = null,
            safe_error_code = null,
            safe_error_message = null,
            authority_binding_id = authorityBindingId,
        )
        bindFirstPreparingLocalAuthority(
            remoteProfile,
            descriptor.syncEpochId,
            descriptorDigest,
            authorityBindingId,
            localWriterDeviceId,
        )?.let { bindingError ->
            return SyncEpochPersistResultV2.ImmutableMismatch(bindingError)
        }
        return SyncEpochPersistResultV2.Stored(checkNotNull(loadEpoch(remoteProfile, descriptor.syncEpochId)))
    }

    /**
     * Persists an epoch obtained from an already-authenticated remote pointer.
     *
     * The only replacement this permits is the first-initialization CAS race: this device has no
     * authoritative epoch, its local singleton still names its own never-activated genesis draft,
     * and another device published a different genesis on the exact same account authority. The
     * losing draft is retained as ABANDONED for exact remote cleanup while the singleton moves to
     * the authenticated winner in the same SQLite transaction.
     */
    fun persistAuthenticatedRemotePreparingEpoch(
        remoteProfile: String,
        descriptor: SyncEpochDescriptorV2,
        descriptorDigest: String,
        authorityBindingId: String,
        localWriterDeviceId: String,
    ): SyncEpochPersistResultV2 {
        val current = loadLocalAuthority()
        if (current == null ||
            (current.remoteProfile == remoteProfile &&
                current.epochId == descriptor.syncEpochId &&
                current.pointerDigest == descriptorDigest) ||
            loadAuthoritativeEpoch() != null
        ) {
            return persistPreparingEpoch(
                remoteProfile,
                descriptor,
                descriptorDigest,
                authorityBindingId,
                localWriterDeviceId,
            )
        }

        var outcome: SyncEpochPersistResultV2? = null
        database.transaction {
            val lockedAuthority = loadLocalAuthority()
            val losingEpoch = lockedAuthority?.let { loadEpoch(it.remoteProfile, it.epochId) }
            val existingWinner = loadEpoch(remoteProfile, descriptor.syncEpochId)
            val canHandoff = loadAuthoritativeEpoch() == null &&
                descriptor.remoteProfile == remoteProfile &&
                descriptor.previousEpochId == null &&
                lockedAuthority != null &&
                lockedAuthority.remoteProfile == remoteProfile &&
                lockedAuthority.epochId != descriptor.syncEpochId &&
                lockedAuthority.authorityBindingId == authorityBindingId &&
                lockedAuthority.localWriterDeviceId == localWriterDeviceId &&
                losingEpoch != null &&
                losingEpoch.lifecycle == SyncEpochLifecycleV2.PREPARING &&
                losingEpoch.activatedAtEpochMilliseconds == null &&
                losingEpoch.descriptor.previousEpochId == null &&
                losingEpoch.descriptor.createdByDeviceId == localWriterDeviceId &&
                losingEpoch.authorityBindingId == authorityBindingId &&
                losingEpoch.descriptorDigest == lockedAuthority.pointerDigest &&
                (existingWinner == null ||
                    (existingWinner.lifecycle == SyncEpochLifecycleV2.PREPARING &&
                        existingWinner.activatedAtEpochMilliseconds == null &&
                        existingWinner.descriptor == descriptor &&
                        existingWinner.descriptorDigest == descriptorDigest &&
                        existingWinner.authorityBindingId?.let { it == authorityBindingId } != false))

            if (!canHandoff) {
                outcome = SyncEpochPersistResultV2.ImmutableMismatch(
                    "The authenticated first epoch cannot replace the locally pinned authority.",
                )
                return@transaction
            }
            val loser = checkNotNull(losingEpoch)

            queries.updateSyncEpochLifecycleV2(
                lifecycle = SyncEpochLifecycleV2.ABANDONED.storageValue,
                health = SyncEpochHealthV2.HEALTHY.storageValue,
                activated_at = null,
                safe_error_code = "epoch_pointer_compare_and_set_lost",
                safe_error_message = "Another authenticated first checkpoint won the pointer compare-and-set.",
                remote_profile = loser.remoteProfile,
                epoch_id = loser.descriptor.syncEpochId,
            )
            queries.deleteExactLocalAuthoritySystemV2(
                loser.remoteProfile,
                loser.descriptor.syncEpochId,
            )
            outcome = persistPreparingEpoch(
                remoteProfile,
                descriptor,
                descriptorDigest,
                authorityBindingId,
                localWriterDeviceId,
            ).also { persisted ->
                check(persisted !is SyncEpochPersistResultV2.ImmutableMismatch) {
                    "The authenticated first-epoch handoff could not be committed atomically."
                }
            }
        }
        return checkNotNull(outcome)
    }

    /**
     * Before the first pointer is authoritative, retain the exact account and
     * writer next to the crash-recoverable checkpoint. Successor drafts do not
     * replace the already-active singleton authority.
     */
    private fun bindFirstPreparingLocalAuthority(
        remoteProfile: String,
        epochId: String,
        pointerDigest: String,
        authorityBindingId: String?,
        localWriterDeviceId: String?,
    ): String? {
        if (authorityBindingId == null || localWriterDeviceId == null || loadAuthoritativeEpoch() != null) {
            return null
        }
        val current = loadLocalAuthority()
        if (current != null &&
            (current.remoteProfile != remoteProfile || current.epochId != epochId ||
                current.pointerDigest != pointerDigest ||
                current.authorityBindingId != authorityBindingId ||
                current.localWriterDeviceId != localWriterDeviceId)
        ) {
            return "A different first-epoch authority is already prepared on this device."
        }
        queries.upsertLocalAuthoritySystemV2(
            remote_profile = remoteProfile,
            epoch_id = epochId,
            local_writer_device_id = localWriterDeviceId,
            authority_binding_id = authorityBindingId,
            pointer_digest = pointerDigest,
            updated_at = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        return null
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
            val binding = authorityBindingId
                ?: loadLocalAuthority()?.takeIf {
                    it.remoteProfile == remoteProfile
                }?.authorityBindingId
                ?: "unbound:$remoteProfile"
            require(binding.isNotBlank() && binding.length <= 2_048)
            val currentAuthority = loadAuthoritativeEpoch()
            require(currentAuthority == null ||
                (currentAuthority.remoteProfile == remoteProfile && currentAuthority.descriptor.syncEpochId == epochId)
            ) { "A single-generation workspace cannot activate a successor epoch." }
            queries.updateSyncEpochLifecycleV2(
                lifecycle = SyncEpochLifecycleV2.ACTIVE.storageValue,
                health = SyncEpochHealthV2.HEALTHY.storageValue,
                activated_at = target.activatedAtEpochMilliseconds ?: activatedAtMillis,
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
            safe_error_code = safeErrorCode,
            safe_error_message = safeErrorMessage,
            remote_profile = remoteProfile,
            epoch_id = epochId,
        )
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
        database.transaction {
            queries.updateSyncEpochLifecycleV2(
                lifecycle = SyncEpochLifecycleV2.ABANDONED.storageValue,
                health = SyncEpochHealthV2.HEALTHY.storageValue,
                activated_at = null,
                safe_error_code = safeErrorCode,
                safe_error_message = safeErrorMessage,
                remote_profile = remoteProfile,
                epoch_id = epochId,
            )
            loadLocalAuthority()?.takeIf {
                it.remoteProfile == remoteProfile && it.epochId == epochId
            }?.let {
                queries.deleteExactLocalAuthoritySystemV2(remoteProfile, epochId)
            }
        }
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
        ) { "Only an endpoint exposing the exact authenticated authority may be rebound." }
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

    fun loadEpochs(remoteProfile: String): List<StoredSyncEpochV2> =
        queries.selectSyncEpochsV2ByProfile(remoteProfile).executeAsList().map { it.toDomain() }

    fun loadAllEpochs(): List<StoredSyncEpochV2> =
        queries.selectAllSyncEpochsV2().executeAsList().map { it.toDomain() }

    /**
     * True when this device still holds V2 protocol state encrypted under the
     * current workspace key for [remoteProfile] (or any profile when null).
     *
     * Includes preparing (checkpoint not yet activated), active, and blocked
     * state. Abandoned first-CAS loser drafts are ignored.
     */
    fun hasKeyBoundLocalV2State(remoteProfile: String? = null): Boolean {
        val epochs = if (remoteProfile == null) loadAllEpochs() else loadEpochs(remoteProfile)
        return epochs.any { epoch ->
            epoch.lifecycle in setOf(
                SyncEpochLifecycleV2.PREPARING,
                SyncEpochLifecycleV2.ACTIVE,
                SyncEpochLifecycleV2.BLOCKED,
            )
        }
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

    fun loadUnresolvedDeadLetters(remoteProfile: String, epochId: String): List<StoredSyncDeadLetterV2> =
        queries.selectUnresolvedSyncDeadLettersV2(remoteProfile, epochId).executeAsList().map { it.toDomain() }

    fun resolveDeadLetter(
        remoteProfile: String,
        epochId: String,
        streamId: String,
        unitId: String,
    ) {
        queries.deleteSyncDeadLetterV2(
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
        ),
        safeErrorCode = safe_error_code,
        safeErrorMessage = safe_error_message,
    )

}


private fun safeSubtractMilliseconds(value: Long, decrement: Long): Long =
    if (value < Long.MIN_VALUE + decrement) Long.MIN_VALUE else value - decrement

private const val RUN_HISTORY_RETENTION_MILLISECONDS_V2: Long = 30L * 24L * 60L * 60L * 1_000L
