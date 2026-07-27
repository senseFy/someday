@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface WorkspaceRepairResultV2 {
    data class Repaired(val repairedObjectIds: List<String>) : WorkspaceRepairResultV2
    data class RebootstrapRequired(val safeErrorCode: String, val safeMessage: String) : WorkspaceRepairResultV2
    data class StillBlocked(val safeErrorCode: String, val safeMessage: String) : WorkspaceRepairResultV2
}

/**
 * Revalidates a complete blocked cursor unit and commits the original cursor
 * only after every object is healthy.  Repair replicas are ciphertext caches:
 * they never acknowledge an outbox tuple or create a remote cursor change.
 */
class WorkspaceImmutableObjectRepairServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val localWriterDeviceId: String,
    private val remote: WorkspaceSyncRemoteV2,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun repair(deadLetter: StoredSyncDeadLetterV2): WorkspaceRepairResultV2 {
        val input = deadLetter.input
        if (deadLetter.lifecycle != SyncDeadLetterLifecycleV2.ACTIVE) {
            return blocked("repair_not_applicable", "The selected V2 cursor blocker is no longer active.")
        }
        val epoch = protocolStore.loadEpoch(input.remoteProfile, input.epochId)
            ?: return rebootstrap("repair_epoch_missing", "The blocked V2 epoch is not retained locally.")
        if (epoch.descriptor.remoteProfile != remote.remoteProfile) {
            return blocked("remote_profile_mismatch", "Repair remote does not match the blocked epoch.")
        }
        val unit = input.authenticatedUnit?.let { encoded ->
            runCatching { JSON.decodeFromString<WorkspaceEncryptedCursorUnitV2>(encoded) }.getOrNull()
        } ?: return rebootstrap(
            "repair_requires_rebootstrap",
            "The blocker predates complete cursor-unit retention; an authorized new checkpoint is required.",
        )
        if (unit.syncEpochId != input.epochId || unit.streamId != input.streamId ||
            unit.unitId != input.unitId || unit.unitDigest != input.unitDigest ||
            unit.expectedCursorValue != input.cursorValue
        ) {
            return rebootstrap("repair_unit_mismatch", "Stored blocker metadata does not match its authenticated cursor unit.")
        }

        val components = components(input.epochId)
        val store = store(input.epochId, components)
        val repairedIds = mutableListOf<String>()
        val replacements = unit.objects.map { original ->
            when (validate(original, input.epochId, components)) {
                is WorkspaceRepairCandidateV2.Valid -> original
                is WorkspaceRepairCandidateV2.Invalid -> {
                    val replacement = runCatching {
                        findReplacement(original, input.epochId, components, store)
                    }.getOrElse { failure ->
                        if (failure.message == "repair_replica_set_invalid") {
                            return blocked(
                                "repair_replica_set_invalid",
                                "The remote repair replica set violates its authenticated writer bound or uniqueness contract.",
                            )
                        }
                        return blocked(
                            "repair_source_unavailable",
                            "Repair candidates could not be retrieved safely.",
                        )
                    }
                        ?: return blocked(
                            "repair_source_unavailable",
                            "No authenticated replica currently reconstructs the expected immutable V2 object.",
                        )
                    repairedIds += original.objectId
                    replacement
                }
            }
        }
        val mutations = mutableListOf<RemoteWorkspaceMutationV2>()
        val mutationIdentities = mutableMapOf<String, Pair<String, String>>()
        replacements.forEach { outer ->
            val valid = validate(outer, input.epochId, components)
            if (valid !is WorkspaceRepairCandidateV2.Valid) {
                return blocked("repair_candidate_invalid", "A selected V2 repair replica failed final validation.")
            }
            val mutationId = outer.mutationId
                ?: return blocked("transport_metadata_mismatch", "A repaired entity object has no mutation identity.")
            val prior = mutationIdentities.put(mutationId, outer.objectId to outer.objectDigest)
            if (prior != null && prior != outer.objectId to outer.objectDigest) {
                return blocked("mutation_reuse_mismatch", "The repaired cursor unit reuses a mutation identity.")
            }
            mutations += RemoteWorkspaceMutationV2(
                mutationId,
                outer.objectId,
                outer.objectDigest,
                outer.writerDeviceId,
                valid.version,
            )
        }
        val applied = store.applyRemoteCursorUnit(
            RemoteWorkspaceCursorUnitV2(
                input.remoteProfile,
                WorkspaceRemoteCursorAdvanceV2(
                    unit.streamId,
                    unit.expectedCursorValue,
                    unit.nextCursorValue,
                    unit.unitId,
                    unit.unitDigest,
                ),
                mutations,
                clock(),
            ),
        )
        if (applied is WorkspaceRemoteUnitApplyResultV2.Rejected) {
            return when (applied.error.code) {
                WorkspaceStoreErrorCodeV2.MISSING_PARENT -> blocked(
                    "missing_parent",
                    "The repaired cursor unit is authentic but still waits for a retained parent.",
                )
                else -> blocked(applied.error.code.wireValue, applied.error.safeMessage)
            }
        }

        val now = clock()
        localRepository.database.transaction {
            repairedIds.distinct().forEach { objectId ->
                localRepository.database.somedayQueries.updateQuarantinedObjectLifecycleV2(
                    "repaired",
                    now.toEpochMilliseconds(),
                    input.remoteProfile,
                    input.epochId,
                    input.streamId,
                    input.unitId,
                    objectId,
                )
            }
            protocolStore.resolveDeadLetter(
                input.remoteProfile,
                input.epochId,
                input.streamId,
                input.unitId,
                SyncDeadLetterLifecycleV2.REPAIRED,
                now,
            )
            if (protocolStore.loadActiveDeadLetters(input.remoteProfile, input.epochId).none {
                    it.input.failureClass == SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY ||
                        it.input.failureClass == SyncDeadLetterFailureClassV2.INCOMPATIBLE_EPOCH
                }
            ) {
                val stored = protocolStore.loadEpoch(input.remoteProfile, input.epochId)
                if (stored?.lifecycle == SyncEpochLifecycleV2.BLOCKED) {
                    protocolStore.resumeBlockedEpochAfterRepair(input.remoteProfile, input.epochId)
                }
            }
        }
        return WorkspaceRepairResultV2.Repaired(repairedIds.distinct().sorted())
    }

    private fun findReplacement(
        original: EncryptedWorkspaceObjectV2,
        epochId: String,
        components: WorkspaceRepairComponentsV2,
        store: SqlDelightWorkspaceEntityStoreV2,
    ): EncryptedWorkspaceObjectV2? {
        val candidates = runCatching {
            remote.fetchRepairReplicas(epochId, original.objectId, original.objectDigest)
        }.getOrElse { return null }
        if (candidates.size > MAX_REPAIR_REPLICAS_PER_OBJECT_V2 ||
            candidates.map { it.writerDeviceId }.distinct().size != candidates.size
        ) {
            throw IllegalStateException("repair_replica_set_invalid")
        }
        val valid = candidates.sortedBy { it.writerDeviceId }.mapNotNull { candidate ->
            val result = if (candidate.objectId != original.objectId ||
                candidate.objectDigest != original.objectDigest ||
                candidate.objectType != original.objectType ||
                candidate.mutationId != original.mutationId ||
                candidate.syncEpochId != original.syncEpochId
            ) {
                WorkspaceRepairCandidateV2.Invalid("repair_object_mismatch")
            } else {
                validate(candidate, epochId, components)
            }
            rememberReplica(candidate, result is WorkspaceRepairCandidateV2.Valid)
            (result as? WorkspaceRepairCandidateV2.Valid)?.let { candidate }
        }
        if (valid.isNotEmpty()) return valid.first()

        val retained = store.loadVersion(original.objectId)
            ?.takeIf { it.objectDigest == original.objectDigest }
            ?: return null
        val plaintext = runCatching { components.wire.encode(retained) }.getOrNull() ?: return null
        val localReplica = runCatching {
            components.cipher.reencryptReplica(original, localWriterDeviceId, plaintext)
        }.getOrNull() ?: return null
        if (validate(localReplica, epochId, components) !is WorkspaceRepairCandidateV2.Valid) return null
        return when (remote.publishRepairReplica(localReplica)) {
            is WorkspaceImmutablePutResultV2.Rejected -> null
            is WorkspaceImmutablePutResultV2.Stored -> {
                rememberReplica(localReplica, true)
                localReplica
            }
        }
    }

    private fun rememberReplica(outer: EncryptedWorkspaceObjectV2, valid: Boolean) {
        localRepository.database.somedayQueries.insertRepairReplicaV2(
            remote.remoteProfile,
            outer.syncEpochId,
            outer.objectId,
            outer.objectDigest,
            outer.writerDeviceId,
            outer.ciphertextDigest,
            components(outer.syncEpochId).cipher.encodeJson(outer),
            if (valid) "valid" else "invalid",
            clock().toEpochMilliseconds(),
        )
    }

    private fun validate(
        outer: EncryptedWorkspaceObjectV2,
        epochId: String,
        components: WorkspaceRepairComponentsV2,
    ): WorkspaceRepairCandidateV2 {
        if (outer.syncEpochId != epochId || outer.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 ||
            outer.mutationId == null
        ) return WorkspaceRepairCandidateV2.Invalid("transport_metadata_mismatch")
        val plaintext = when (val decrypted = components.cipher.decrypt(outer)) {
            is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decrypted.plaintext
            is EncryptedWorkspaceObjectDecodeResultV2.Rejected ->
                return WorkspaceRepairCandidateV2.Invalid(decrypted.error.code.wireValue)
        }
        return when (val decoded = components.wire.decode(
            plaintext,
            WorkspaceVersionOuterMetadataV2(epochId, outer.objectId, outer.objectDigest),
        )) {
            is WorkspaceEntityWireDecodeResultV2.Decoded -> WorkspaceRepairCandidateV2.Valid(decoded.version)
            is WorkspaceEntityWireDecodeResultV2.Rejected -> WorkspaceRepairCandidateV2.Invalid(decoded.error.code.wireValue)
        }
    }

    private fun components(epochId: String): WorkspaceRepairComponentsV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        return WorkspaceRepairComponentsV2(
            materializer,
            validator,
            WorkspaceEntityWireCodecV2(materializer, validator),
            WorkspaceObjectCipherV2(workspaceKey, materializer),
        )
    }

    private fun store(
        epochId: String,
        components: WorkspaceRepairComponentsV2,
    ) = SqlDelightWorkspaceEntityStoreV2(
        localRepository.database,
        epochId,
        WorkspaceEntityCausalityEngineV2(components.materializer, components.validator),
        components.materializer,
        components.wire,
        WorkspaceOutboxEncoderV2 { version, mutationId ->
            PreparedWorkspaceOutboxObjectV2(
                localWriterDeviceId,
                components.cipher.encodeJson(
                    components.cipher.encryptEntity(
                        version,
                        mutationId,
                        localWriterDeviceId,
                        components.wire.encode(version),
                    ),
                ),
            )
        },
    )

    private fun blocked(code: String, message: String) = WorkspaceRepairResultV2.StillBlocked(
        code,
        message.safeRepairMessageV2(),
    )

    private fun rebootstrap(code: String, message: String) = WorkspaceRepairResultV2.RebootstrapRequired(
        code,
        message.safeRepairMessageV2(),
    )

    private companion object {
        const val MAX_REPAIR_REPLICAS_PER_OBJECT_V2 = 64
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
    }
}

private data class WorkspaceRepairComponentsV2(
    val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    val validator: WorkspaceEntityValidatorV2,
    val wire: WorkspaceEntityWireCodecV2,
    val cipher: WorkspaceObjectCipherV2,
)

private sealed interface WorkspaceRepairCandidateV2 {
    data class Valid(val version: WorkspaceEntityVersionV2) : WorkspaceRepairCandidateV2
    data class Invalid(val code: String) : WorkspaceRepairCandidateV2
}

private fun String.safeRepairMessageV2(): String =
    replace(Regex("(?i)(bearer|token|password|secret|title|body|place)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .take(500)
