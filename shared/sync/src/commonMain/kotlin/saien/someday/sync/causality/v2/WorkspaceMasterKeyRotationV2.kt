@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import kotlin.time.Clock
import kotlin.time.Instant

data class WorkspaceMasterKeyRotationAuthorizationV2(
    /** The new key is already recoverable by every device that remains authorized. */
    val newKeyDistributedToRetainedDevices: Boolean,
    /** Removed devices have lost both remote credentials and access to the new key. */
    val removedDevicesRevoked: Boolean,
    /** The caller durably staged the new key before pointer publication. */
    val newKeyDurablyStagedLocally: Boolean,
)

sealed interface WorkspaceMasterKeyRotationResultV2 {
    data class Rotated(
        val previousEpochId: String,
        val newEpochId: String,
        val previousPointerDigest: String,
        val newPointerDigest: String,
        val checkpointRootCount: Int,
        val idempotentResume: Boolean,
    ) : WorkspaceMasterKeyRotationResultV2

    data class Blocked(
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspaceMasterKeyRotationResultV2
}

/**
 * Performs a true workspace-master-key rotation without changing semantic
 * history in place. The old key authenticates and drains the source epoch;
 * the new key encrypts a complete successor checkpoint. The sole authority
 * transition is the remote pointer compare-and-set from the exact old digest.
 *
 * Key distribution and secure-store staging are deliberately caller-owned:
 * this service never serializes either key or recovery material. A crash-safe
 * prepared checkpoint is persisted before any remote pointer change, so a
 * retry with the same new key reuses the exact epoch, object ids, nonces, and
 * ciphertext.
 */
class WorkspaceMasterKeyRotationServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val oldWorkspaceKey: WorkspaceMasterKey,
    private val newWorkspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val oldKeyRemote: WorkspaceSyncRemoteV2,
    private val newKeyRemote: WorkspaceSyncRemoteV2,
    private val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(writerDeviceId))
        require(oldWorkspaceKey.fingerprint != newWorkspaceKey.fingerprint) {
            "A true master-key rotation requires different key material."
        }
        require(oldKeyRemote.remoteProfile == newKeyRemote.remoteProfile) {
            "Master-key rotation cannot also change the remote profile."
        }
        require(oldKeyRemote.authorityBindingId == newKeyRemote.authorityBindingId) {
            "Master-key rotation cannot also change the remote authority binding."
        }
    }

    fun rotate(
        authorization: WorkspaceMasterKeyRotationAuthorizationV2,
    ): WorkspaceMasterKeyRotationResultV2 {
        if (!authorization.newKeyDurablyStagedLocally) {
            return blocked(
                "new_workspace_key_not_staged",
                "Durably stage the new workspace key before publishing a key-rotation pointer.",
            )
        }
        if (!authorization.newKeyDistributedToRetainedDevices) {
            return blocked(
                "new_workspace_key_not_distributed",
                "Distribute the new workspace key to every retained device before rotation.",
            )
        }
        if (!authorization.removedDevicesRevoked) {
            return blocked(
                "removed_devices_not_revoked",
                "Revoke removed-device remote credentials before rotating the workspace key.",
            )
        }

        val sourceEpoch = protocolStore.loadAuthoritativeEpoch()
            ?: return blocked("source_authority_missing", "No authenticated V2 authority exists to rotate.")
        if (sourceEpoch.remoteProfile != oldKeyRemote.remoteProfile ||
            sourceEpoch.authorityBindingId != oldKeyRemote.authorityBindingId
        ) {
            return blocked(
                "source_authority_mismatch",
                "The old-key connection does not match the exact authenticated V2 authority.",
            )
        }

        // A prior attempt may have committed the successor locally before its
        // caller observed the result. Never create a second rotation epoch.
        if (sourceEpoch.descriptor.previousEpochId != null &&
            sourceEpoch.lifecycle == SyncEpochLifecycleV2.ACTIVE &&
            canDecodePointerWith(newWorkspaceKey, sourceEpoch) &&
            !canDecodePointerWith(oldWorkspaceKey, sourceEpoch)
        ) {
            val predecessor = checkNotNull(sourceEpoch.descriptor.previousEpochId)
            val predecessorDigest = checkNotNull(sourceEpoch.descriptor.previousEpochPointerDigest)
            return WorkspaceMasterKeyRotationResultV2.Rotated(
                previousEpochId = predecessor,
                newEpochId = sourceEpoch.descriptor.syncEpochId,
                previousPointerDigest = predecessorDigest,
                newPointerDigest = sourceEpoch.descriptorDigest,
                checkpointRootCount = checkpointRootCount(sourceEpoch),
                idempotentResume = true,
            )
        }
        if (sourceEpoch.lifecycle != SyncEpochLifecycleV2.ACTIVE ||
            sourceEpoch.health != SyncEpochHealthV2.HEALTHY
        ) {
            return blocked(
                "source_authority_unhealthy",
                "Repair and fully synchronize the current V2 epoch before master-key rotation.",
            )
        }

        val capabilities = runCatching(newKeyRemote::capabilities).getOrElse {
            return blocked("remote_unavailable", it.safeKeyRotationMessageV2())
        }
        capabilities.incompatibility()?.let {
            return blocked(it, "The remote does not implement the frozen whole-product V2 contract.")
        }
        if (capabilities.profile != sourceEpoch.remoteProfile) {
            return blocked("remote_profile_mismatch", "The remote capability profile is inconsistent.")
        }

        val currentOuter = runCatching(oldKeyRemote::loadEpochPointer).getOrElse {
            return blocked("source_pointer_unavailable", it.safeKeyRotationMessageV2())
        } ?: return blocked("source_pointer_missing", "The authenticated V2 pointer is missing.")

        // First resume a crash-safe new-key checkpoint. This also covers a
        // crash after remote CAS but before the local activation transaction.
        when (val recovered = WorkspacePreparedCheckpointRecoveryV2(
            localRepository,
            newWorkspaceKey,
            protocolStore,
        ).loadCompatible(sourceEpoch.remoteProfile, currentOuter)) {
            is WorkspacePreparedCheckpointLoadResultV2.Rejected -> return blocked(
                recovered.safeErrorCode,
                recovered.safeMessage,
            )
            is WorkspacePreparedCheckpointLoadResultV2.Loaded -> {
                if (recovered.prepared.descriptor.previousEpochId != sourceEpoch.descriptor.syncEpochId ||
                    recovered.prepared.pointer.previousPointerDigest != sourceEpoch.descriptorDigest
                ) {
                    return blocked(
                        "unrelated_prepared_checkpoint",
                        "Another prepared epoch must be completed or explicitly abandoned before key rotation.",
                    )
                }
                return publish(recovered.prepared, sourceEpoch, idempotentResume = true)
            }
            WorkspacePreparedCheckpointLoadResultV2.None -> Unit
        }

        if (currentOuter.syncEpochId != sourceEpoch.descriptor.syncEpochId ||
            currentOuter.objectDigest != sourceEpoch.descriptorDigest
        ) {
            return blocked(
                "source_pointer_mismatch",
                "The remote pointer changed; authenticate and synchronize it before retrying rotation.",
            )
        }
        if (!canDecodeExactPointer(oldWorkspaceKey, currentOuter, sourceEpoch)) {
            return blocked(
                "source_pointer_authentication_failed",
                "The old workspace key cannot authenticate the exact current pointer.",
            )
        }

        val sourceContext = WorkspaceSystemV2ContextProvider(
            localRepository,
            { oldWorkspaceKey },
            { writerDeviceId },
            { sourceEpoch.remoteProfile },
        ).requireActive()
        if (sourceContext.store.loadPending(sourceEpoch.remoteProfile).isNotEmpty()) {
            return blocked(
                "source_outbox_not_drained",
                "Synchronize and acknowledge the current V2 outbox before key rotation.",
            )
        }
        val firstFrontier = runCatching {
            oldKeyRemote.epochFrontiers(sourceEpoch.descriptor.syncEpochId).sortedBy { it.streamId }
        }.getOrElse { return blocked("source_frontier_unavailable", it.safeKeyRotationMessageV2()) }
        if (!firstFrontier.all { frontier ->
                sourceContext.store.loadCursor(sourceEpoch.remoteProfile, frontier.streamId)?.cursorValue ==
                    frontier.cursorValue
            }
        ) {
            return blocked(
                "source_frontier_not_applied",
                "The current device has not applied the complete authenticated remote frontier.",
            )
        }

        val sourceHeads = sourceContext.store.loadEntityKeys()
            .flatMap(sourceContext.store::loadHeads)
            .map { head ->
                WorkspaceCheckpointSourceHeadV2(
                    entityType = head.entityType,
                    entityId = head.entityId,
                    content = head.contentPayload,
                    deletion = head.deletionPayload,
                    sourceProfile = sourceEpoch.remoteProfile,
                    sourceEpoch = sourceEpoch.descriptor.syncEpochId,
                    sourceWriterId = null,
                    sourceMutationId = null,
                    sourceObjectId = head.versionId,
                    sourceObjectDigest = head.objectDigest,
                    sourceAuthoredAt = head.authoredAt,
                )
            }
            .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
        if (sourceHeads.none {
                it.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES &&
                    it.entityId == WORKSPACE_PREFERENCES_ENTITY_ID_V2
            }
        ) {
            return blocked(
                "source_checkpoint_incomplete",
                "The current epoch lacks the required workspace-preferences root.",
            )
        }

        val secondFrontier = runCatching {
            oldKeyRemote.epochFrontiers(sourceEpoch.descriptor.syncEpochId).sortedBy { it.streamId }
        }.getOrElse { return blocked("source_frontier_unavailable", it.safeKeyRotationMessageV2()) }
        if (firstFrontier != secondFrontier || sourceContext.store.loadPending(sourceEpoch.remoteProfile).isNotEmpty()) {
            return blocked(
                "source_frontier_changed",
                "The source changed during key-rotation preparation; synchronize and retry.",
            )
        }

        val prepared = runCatching {
            WorkspaceCheckpointBuilderV2(newWorkspaceKey, writerDeviceId).build(
                remoteProfile = sourceEpoch.remoteProfile,
                sourceHeads = sourceHeads,
                createdAt = clock(),
                previousPointerDigest = sourceEpoch.descriptorDigest,
                previousEpochId = sourceEpoch.descriptor.syncEpochId,
                previousEpochPointerDigest = sourceEpoch.descriptorDigest,
                previousEpochFrontiers = firstFrontier,
            )
        }.getOrElse { return blocked("checkpoint_build_failed", it.safeKeyRotationMessageV2()) }
        when (val persisted = WorkspaceCheckpointPersistenceV2(
            localRepository,
            newWorkspaceKey,
            writerDeviceId,
            protocolStore,
        ).persist(prepared)) {
            is WorkspaceCheckpointPersistResultV2.Rejected -> return blocked(
                persisted.safeErrorCode,
                persisted.safeMessage,
            )
            is WorkspaceCheckpointPersistResultV2.Ready -> Unit
        }
        return publish(prepared, sourceEpoch, idempotentResume = false)
    }

    private fun publish(
        prepared: PreparedWorkspaceEpochCheckpointV2,
        sourceEpoch: StoredSyncEpochV2,
        idempotentResume: Boolean,
    ): WorkspaceMasterKeyRotationResultV2 {
        return when (val published = WorkspaceCheckpointPublisherV2(
            localRepository,
            newKeyRemote,
            protocolStore,
            localSnapshotStillMatches = {
                sourceStateStillMatches(prepared, sourceEpoch)
            },
            commitPointerBarrier = authorityMutationCoordinator::productAccess,
        ).publish(prepared)) {
            is WorkspaceCheckpointPublishResultV2.LostRace -> blocked(
                "key_rotation_pointer_race",
                "Another pointer transition won. No branch was discarded; authenticate the winner before retrying.",
            )
            is WorkspaceCheckpointPublishResultV2.Rejected -> {
                if (published.safeErrorCode == "prepared_checkpoint_stale") {
                    protocolStore.abandonPreparingEpoch(
                        prepared.remoteProfile,
                        prepared.descriptor.syncEpochId,
                        published.safeErrorCode,
                        published.safeMessage,
                    )
                }
                blocked(published.safeErrorCode, published.safeMessage)
            }
            is WorkspaceCheckpointPublishResultV2.Published -> {
                val active = published.epoch
                if (active.descriptor.previousEpochId != sourceEpoch.descriptor.syncEpochId ||
                    active.descriptor.previousEpochPointerDigest != sourceEpoch.descriptorDigest ||
                    !canDecodePointerWith(newWorkspaceKey, active)
                ) {
                    return blocked(
                        "key_rotation_postcondition_failed",
                        "The committed successor did not satisfy the authenticated key-rotation postcondition.",
                    )
                }
                WorkspaceMasterKeyRotationResultV2.Rotated(
                    previousEpochId = sourceEpoch.descriptor.syncEpochId,
                    newEpochId = active.descriptor.syncEpochId,
                    previousPointerDigest = sourceEpoch.descriptorDigest,
                    newPointerDigest = active.descriptorDigest,
                    checkpointRootCount = prepared.entities.size,
                    idempotentResume = idempotentResume || published.idempotentReplay,
                )
            }
        }
    }

    private fun sourceStateStillMatches(
        prepared: PreparedWorkspaceEpochCheckpointV2,
        sourceEpoch: StoredSyncEpochV2,
    ): Boolean = runCatching {
        if (protocolStore.loadAuthoritativeEpoch()?.let {
                it.remoteProfile == sourceEpoch.remoteProfile &&
                    it.descriptor.syncEpochId == sourceEpoch.descriptor.syncEpochId &&
                    it.descriptorDigest == sourceEpoch.descriptorDigest &&
                    it.authorityBindingId == sourceEpoch.authorityBindingId
            } != true
        ) {
            return@runCatching false
        }
        val sourceContext = WorkspaceSystemV2ContextProvider(
            localRepository,
            { oldWorkspaceKey },
            { writerDeviceId },
            { sourceEpoch.remoteProfile },
        ).requireActive()
        checkpointSourceStateStillMatchesV2(
            sourceContext.store,
            sourceEpoch.remoteProfile,
            prepared.checkpointSourceIdentitiesV2(),
            requireNoPending = true,
        )
    }.getOrDefault(false)

    private fun canDecodePointerWith(key: WorkspaceMasterKey, epoch: StoredSyncEpochV2): Boolean {
        val outer = runCatching(newKeyRemote::loadEpochPointer).getOrNull() ?: return false
        return canDecodeExactPointer(key, outer, epoch)
    }

    private fun canDecodeExactPointer(
        key: WorkspaceMasterKey,
        outer: EncryptedWorkspaceObjectV2,
        epoch: StoredSyncEpochV2,
    ): Boolean {
        if (outer.syncEpochId != epoch.descriptor.syncEpochId || outer.objectDigest != epoch.descriptorDigest) {
            return false
        }
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(key, epoch.descriptor.syncEpochId),
        )
        val decoded = WorkspaceSyncControlCodecV2(
            WorkspaceObjectCipherV2(key, materializer),
        ).decodeEpochPointer(outer)
        return (decoded as? WorkspaceControlDecodeResultV2.Decoded)?.value?.descriptor == epoch.descriptor
    }

    private fun checkpointRootCount(epoch: StoredSyncEpochV2): Int =
        localRepository.database.somedayQueries.selectCheckpointObjectsSystemV2(
            epoch.remoteProfile,
            epoch.descriptor.syncEpochId,
            epoch.descriptor.checkpointId,
        ).executeAsList().size

    private fun blocked(code: String, message: String) =
        WorkspaceMasterKeyRotationResultV2.Blocked(code, message.take(500))
}

private fun Throwable.safeKeyRotationMessageV2(): String =
    (message ?: "V2 master-key rotation stopped safely.")
        .replace(Regex("(?i)(password|token|credential|authorization|key)\\s*[:=]\\s*[^\\s,;]+"), "$1=redacted")
        .take(500)
