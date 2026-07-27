@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.crypto

import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class PreparedWorkspaceKeyRotation(
    val token: String,
    val sourceEpochId: String,
    val sourceKeyFingerprint: String,
    val targetKeyFingerprint: String,
    val targetMetadataJson: String,
    val recoveryMaterial: RecoveryMaterial,
)

data class PendingWorkspaceKeyRotation(
    val token: String,
    val sourceEpochId: String,
    val sourceKeyFingerprint: String,
    val targetKeyFingerprint: String,
)

sealed interface WorkspaceKeyRotationStageResult {
    data class Staged(val pending: PendingWorkspaceKeyRotation) : WorkspaceKeyRotationStageResult
    data class Failed(val reason: WorkspaceUnlockFailure) : WorkspaceKeyRotationStageResult
}

class WorkspaceKeyRepository(
    private val localRepository: SqlDelightLocalDataRepository,
    private val secureKeyStore: SecureWorkspaceKeyStore,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
    private val clock: () -> Instant = { Clock.System.now() },
    private val aliasGenerator: SecureStorageAliasGenerator = TimeBasedSecureStorageAliasGenerator(),
) {
    private var unlockedKey: WorkspaceMasterKey? = null

    fun startupState(): WorkspaceUnlockState {
        val metadata = loadMetadata() ?: return WorkspaceUnlockState.Uninitialized
        val current = unlockedKey
        return if (current != null) {
            WorkspaceUnlockState.Unlocked(metadata.workspaceId, current.fingerprint)
        } else {
            WorkspaceUnlockState.Locked(
                workspaceId = metadata.workspaceId,
                secureStorageAvailable = secureKeyStore.contains(metadata.secureStorageAlias),
            )
        }
    }

    fun createFirstRunWorkspace(
        deviceName: String,
        platform: String,
    ): FirstRunWorkspaceSetup {
        require(loadMetadata() == null) { "Workspace already exists." }

        val workspaceId = "workspace-${crypto.randomBytes(16).hex()}"
        val workspaceKey = crypto.generateWorkspaceKey()
        val recoveryMaterial = crypto.generateRecoveryMaterial()
        val secureStorageAlias = aliasGenerator.newAlias(workspaceId)
        val metadata = createMetadata(
            workspaceId = workspaceId,
            workspaceKey = workspaceKey,
            recoveryMaterial = recoveryMaterial,
            secureStorageAlias = secureStorageAlias,
        )
        val metadataJson = encodeMetadata(metadata)

        secureKeyStore.put(secureStorageAlias, workspaceKey)
        persistMetadata(metadataJson, deviceName, platform)
        unlockedKey = workspaceKey.copy()

        return FirstRunWorkspaceSetup(
            state = WorkspaceUnlockState.Unlocked(workspaceId, workspaceKey.fingerprint),
            recoveryMaterial = recoveryMaterial,
            secureStorageAlias = secureStorageAlias,
            metadataJson = metadataJson,
        )
    }

    fun lock() {
        unlockedKey = null
    }

    fun unlockedKeyOrNull(): WorkspaceMasterKey? = unlockedKey?.copy()

    /**
     * Stages a different workspace key in secure storage without changing the
     * active key. The returned recovery material is intentionally one-shot;
     * it is never persisted in settings or the sync graph.
     */
    fun prepareWorkspaceKeyRotation(sourceEpochId: String): PreparedWorkspaceKeyRotation {
        require(sourceEpochId.isNotBlank() && sourceEpochId.length <= 128)
        require(loadRotationState() == null) { "A workspace-key rotation is already pending." }
        val sourceMetadata = requireNotNull(loadMetadata()) { "No workspace exists." }
        val sourceKey = unlockedKeyOrNull()
            ?: secureKeyStore.get(sourceMetadata.secureStorageAlias)
            ?: error("The active workspace key is unavailable.")
        require(verifyWorkspaceKey(sourceMetadata, sourceKey)) { "The active workspace key failed authentication." }

        val targetKey = crypto.generateWorkspaceKey()
        require(targetKey.fingerprint != sourceKey.fingerprint)
        val recovery = crypto.generateRecoveryMaterial()
        val targetAlias = aliasGenerator.newAlias("${sourceMetadata.workspaceId}-rotation")
        val targetMetadata = createMetadata(
            workspaceId = sourceMetadata.workspaceId,
            workspaceKey = targetKey,
            recoveryMaterial = recovery,
            secureStorageAlias = targetAlias,
        )
        val targetMetadataJson = encodeMetadata(targetMetadata)
        val token = aliasGenerator.newAlias("workspace-key-rotation")
        val state = PersistedWorkspaceKeyRotationState(
            token = token,
            sourceEpochId = sourceEpochId,
            sourceSecureStorageAlias = sourceMetadata.secureStorageAlias,
            sourceKeyFingerprint = sourceKey.fingerprint,
            targetSecureStorageAlias = targetAlias,
            targetKeyFingerprint = targetKey.fingerprint,
            targetMetadataJson = targetMetadataJson,
        )
        secureKeyStore.put(targetAlias, targetKey)
        try {
            persistRotationState(state)
        } catch (failure: Exception) {
            secureKeyStore.remove(targetAlias)
            throw failure
        }
        return PreparedWorkspaceKeyRotation(
            token,
            sourceEpochId,
            sourceKey.fingerprint,
            targetKey.fingerprint,
            targetMetadataJson,
            recovery,
        )
    }

    /** Stages a rotation package received out of band on a retained device. */
    fun stageWorkspaceKeyRotation(
        sourceEpochId: String,
        targetMetadataJson: String,
        recoveryMaterial: String,
    ): WorkspaceKeyRotationStageResult {
        if (loadRotationState() != null) {
            return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.WORKSPACE_ALREADY_EXISTS)
        }
        val sourceMetadata = loadMetadata()
            ?: return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.NO_WORKSPACE)
        val imported = decodeMetadata(targetMetadataJson)
            ?: return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        if (imported.workspaceId != sourceMetadata.workspaceId) {
            return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }
        val targetKey = unwrapRecoveryKey(imported, recoveryMaterial)
            ?: return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        if (!verifyWorkspaceKey(imported, targetKey)) {
            return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }
        val sourceKey = unlockedKeyOrNull()
            ?: secureKeyStore.get(sourceMetadata.secureStorageAlias)
            ?: return WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE)
        val targetAlias = aliasGenerator.newAlias("${sourceMetadata.workspaceId}-rotation")
        val localTargetMetadata = imported.copy(secureStorageAlias = targetAlias)
        val localTargetJson = encodeMetadata(localTargetMetadata)
        val state = PersistedWorkspaceKeyRotationState(
            token = aliasGenerator.newAlias("workspace-key-rotation"),
            sourceEpochId = sourceEpochId,
            sourceSecureStorageAlias = sourceMetadata.secureStorageAlias,
            sourceKeyFingerprint = sourceKey.fingerprint,
            targetSecureStorageAlias = targetAlias,
            targetKeyFingerprint = targetKey.fingerprint,
            targetMetadataJson = localTargetJson,
        )
        secureKeyStore.put(targetAlias, targetKey)
        return try {
            persistRotationState(state)
            WorkspaceKeyRotationStageResult.Staged(state.toPublic())
        } catch (_: Exception) {
            secureKeyStore.remove(targetAlias)
            WorkspaceKeyRotationStageResult.Failed(WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE)
        }
    }

    fun pendingWorkspaceKeyRotation(): PendingWorkspaceKeyRotation? =
        loadRotationState()?.toPublic()

    fun pendingWorkspaceKeyOrNull(token: String): WorkspaceMasterKey? {
        val pending = loadRotationState()?.takeIf { it.token == token } ?: return null
        return secureKeyStore.get(pending.targetSecureStorageAlias)
            ?.takeIf { it.fingerprint == pending.targetKeyFingerprint }
    }

    /**
     * Commits the already-published successor key locally. The old key stays
     * in secure storage and is indexed only by its source epoch for bounded
     * archive/late-writer verification.
     */
    fun commitWorkspaceKeyRotation(token: String, targetEpochId: String): WorkspaceUnlockResult {
        require(targetEpochId.isNotBlank() && targetEpochId.length <= 128)
        val pending = loadRotationState()?.takeIf { it.token == token }
            ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        val targetMetadata = decodeMetadata(pending.targetMetadataJson)
            ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        val targetKey = secureKeyStore.get(pending.targetSecureStorageAlias)
            ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE)
        if (targetKey.fingerprint != pending.targetKeyFingerprint || !verifyWorkspaceKey(targetMetadata, targetKey)) {
            return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }

        // Persist the target verifier first. If the process stops before the
        // rotation state is cleared, a retry is idempotent and both aliases
        // remain available.
        persistMetadata(
            pending.targetMetadataJson,
            localRepository.getDevice(localRepository.localDeviceId)?.name ?: "Someday device",
            localRepository.getDevice(localRepository.localDeviceId)?.platform ?: "unknown",
        )
        val archive = loadKeyArchives().copy(
            entries = (loadKeyArchives().entries.filterNot { it.epochId == pending.sourceEpochId } +
                PersistedWorkspaceKeyArchiveEntry(
                    pending.sourceEpochId,
                    pending.sourceSecureStorageAlias,
                    pending.sourceKeyFingerprint,
                )).sortedBy { it.epochId },
            currentEpochId = targetEpochId,
        )
        localRepository.putLocalOnlySetting(WORKSPACE_KEY_ARCHIVES_SETTING_KEY, json.encodeToString(archive))
        localRepository.deleteLocalOnlySetting(WORKSPACE_KEY_ROTATION_SETTING_KEY)
        unlockedKey = targetKey.copy()
        return WorkspaceUnlockResult.Unlocked(
            WorkspaceUnlockState.Unlocked(targetMetadata.workspaceId, targetKey.fingerprint),
        )
    }

    /** Returns the active, staged, or retained key for an exact local epoch. */
    fun workspaceKeyForEpochOrNull(epochId: String): WorkspaceMasterKey? {
        val pending = loadRotationState()
        if (pending?.sourceEpochId == epochId) {
            return secureKeyStore.get(pending.sourceSecureStorageAlias)
        }
        val archives = loadKeyArchives()
        archives.entries.firstOrNull { it.epochId == epochId }?.let { archived ->
            return secureKeyStore.get(archived.secureStorageAlias)
                ?.takeIf { it.fingerprint == archived.keyFingerprint }
        }
        if (archives.currentEpochId != null && archives.currentEpochId != epochId) return null
        return unlockedKeyOrNull() ?: loadMetadata()?.let { secureKeyStore.get(it.secureStorageAlias) }
    }

    /** Releases only a key already indexed to an expired read-only epoch. */
    fun releaseWorkspaceKeyForEpoch(epochId: String): Boolean {
        val archives = loadKeyArchives()
        if (archives.currentEpochId == epochId) return false
        val archived = archives.entries.singleOrNull { it.epochId == epochId } ?: return false
        val remaining = archives.copy(entries = archives.entries.filterNot { it.epochId == epochId })
        localRepository.putLocalOnlySetting(WORKSPACE_KEY_ARCHIVES_SETTING_KEY, json.encodeToString(remaining))
        secureKeyStore.remove(archived.secureStorageAlias)
        return true
    }

    /** Abort is allowed only after the caller proves the old pointer remains authoritative. */
    fun abortWorkspaceKeyRotation(token: String, oldPointerStillAuthoritative: Boolean): Boolean {
        if (!oldPointerStillAuthoritative) return false
        val pending = loadRotationState()?.takeIf { it.token == token } ?: return false
        localRepository.deleteLocalOnlySetting(WORKSPACE_KEY_ROTATION_SETTING_KEY)
        secureKeyStore.remove(pending.targetSecureStorageAlias)
        return true
    }

    fun exportRecoveryMetadataJson(): String? =
        localRepository.getSetting(WORKSPACE_KEY_METADATA_SETTING_KEY)?.value

    fun ensureLocalDeviceRegistered(
        deviceName: String,
        platform: String,
    ) {
        val metadataJson = exportRecoveryMetadataJson() ?: return
        if (localRepository.getDevice(localRepository.localDeviceId) != null) {
            return
        }
        localRepository.registerDevice(
            name = deviceName,
            platform = platform,
            workspaceKeyMetadata = metadataJson,
        )
    }

    fun createWorkspaceJoinPackage(): WorkspaceJoinPackageResult {
        val metadata = loadMetadata() ?: return WorkspaceJoinPackageResult.Failed(WorkspaceUnlockFailure.NO_WORKSPACE)
        val workspaceKey = unlockedKeyOrNull()
            ?: secureKeyStore.get(metadata.secureStorageAlias)
            ?: return WorkspaceJoinPackageResult.Failed(WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE)
        if (!verifyWorkspaceKey(metadata, workspaceKey)) {
            return WorkspaceJoinPackageResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }

        val recoveryMaterial = crypto.generateRecoveryMaterial()
        val joinMetadata = createMetadata(
            workspaceId = metadata.workspaceId,
            workspaceKey = workspaceKey,
            recoveryMaterial = recoveryMaterial,
            secureStorageAlias = metadata.secureStorageAlias,
        )
        return WorkspaceJoinPackageResult.Created(
            metadataJson = encodeMetadata(joinMetadata),
            recoveryMaterial = recoveryMaterial,
            workspaceId = metadata.workspaceId,
            keyFingerprint = workspaceKey.fingerprint,
        )
    }

    fun unlockWithSecureStorage(): WorkspaceUnlockResult {
        val metadata = loadMetadata() ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.NO_WORKSPACE)
        val storedKey = secureKeyStore.get(metadata.secureStorageAlias)
            ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE)

        return unlockIfVerified(metadata, storedKey)
    }

    fun unlockWithRecoveryMaterial(recoveryMaterial: String): WorkspaceUnlockResult {
        val metadata = loadMetadata() ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.NO_WORKSPACE)
        val workspaceKey = unwrapRecoveryKey(metadata, recoveryMaterial)
            ?: return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)

        secureKeyStore.put(metadata.secureStorageAlias, workspaceKey)
        return unlockIfVerified(metadata, workspaceKey)
    }

    fun verifyRecoveryMaterial(recoveryMaterial: String): Boolean {
        val metadata = loadMetadata() ?: return false
        val workspaceKey = unwrapRecoveryKey(metadata, recoveryMaterial) ?: return false
        return verifyWorkspaceKey(metadata, workspaceKey)
    }

    fun restoreWorkspaceFromRecovery(
        metadataJson: String,
        recoveryMaterial: String,
        deviceName: String,
        platform: String,
        replaceExistingWorkspace: Boolean = false,
        expectedWorkspaceId: String? = null,
        expectedKeyFingerprint: String? = null,
    ): WorkspaceRestoreResult {
        val existingMetadata = loadMetadata()
        if (existingMetadata != null && !replaceExistingWorkspace) {
            return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.WORKSPACE_ALREADY_EXISTS)
        }
        val importedMetadata = decodeMetadata(metadataJson)
            ?: return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        val workspaceKey = unwrapRecoveryKey(importedMetadata, recoveryMaterial)
            ?: return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        if (!verifyWorkspaceKey(importedMetadata, workspaceKey)) {
            return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }
        if (expectedWorkspaceId != null && importedMetadata.workspaceId != expectedWorkspaceId) {
            return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        }
        if (expectedKeyFingerprint != null && workspaceKey.fingerprint != expectedKeyFingerprint) {
            return WorkspaceRestoreResult.Failed(WorkspaceUnlockFailure.INVALID_METADATA)
        }

        val secureStorageAlias = aliasGenerator.newAlias(importedMetadata.workspaceId)
        val restoredMetadata = importedMetadata.copy(
            secureStorageAlias = secureStorageAlias,
            restoredAt = clock().toString(),
        )
        val restoredMetadataJson = encodeMetadata(restoredMetadata)

        secureKeyStore.put(secureStorageAlias, workspaceKey)
        try {
            persistMetadata(restoredMetadataJson, deviceName, platform)
        } catch (failure: Throwable) {
            runCatching { secureKeyStore.remove(secureStorageAlias) }
            throw failure
        }
        unlockedKey = workspaceKey.copy()
        existingMetadata
            ?.secureStorageAlias
            ?.takeUnless { it == secureStorageAlias }
            ?.let { oldAlias -> runCatching { secureKeyStore.remove(oldAlias) } }

        return WorkspaceRestoreResult.Restored(
            state = WorkspaceUnlockState.Unlocked(importedMetadata.workspaceId, workspaceKey.fingerprint),
            secureStorageAlias = secureStorageAlias,
        )
    }

    private fun unlockIfVerified(
        metadata: PersistedWorkspaceKeyMetadata,
        workspaceKey: WorkspaceMasterKey,
    ): WorkspaceUnlockResult {
        if (!verifyWorkspaceKey(metadata, workspaceKey)) {
            unlockedKey = null
            return WorkspaceUnlockResult.Failed(WorkspaceUnlockFailure.AUTHENTICATION_FAILED)
        }

        unlockedKey = workspaceKey.copy()
        return WorkspaceUnlockResult.Unlocked(
            WorkspaceUnlockState.Unlocked(metadata.workspaceId, workspaceKey.fingerprint),
        )
    }

    private fun createMetadata(
        workspaceId: String,
        workspaceKey: WorkspaceMasterKey,
        recoveryMaterial: RecoveryMaterial,
        secureStorageAlias: String,
    ): PersistedWorkspaceKeyMetadata {
        val verifier = createVerifier(workspaceId, workspaceKey)
        val salt = crypto.randomBytes(RECOVERY_SALT_BYTES)
        val recoveryKey = crypto.deriveRecoveryWrappingKey(
            recoveryMaterial = recoveryMaterial.revealForUserConfirmation(),
            salt = salt,
            policy = crypto.recoveryKdfPolicy,
        )
        val wrappedKey = crypto.encryptAead(
            key = recoveryKey,
            associatedData = recoveryAssociatedData(workspaceId),
            plaintext = workspaceKey.rawBytesCopy(),
        )

        return PersistedWorkspaceKeyMetadata(
            workspaceId = workspaceId,
            createdAt = clock().toString(),
            secureStorageAlias = secureStorageAlias,
            verifier = PersistedAeadCiphertext(
                nonce = verifier.nonce.base64(),
                ciphertext = verifier.ciphertext.base64(),
            ),
            recovery = PersistedRecoveryWrapper(
                salt = salt.base64(),
                nonce = wrappedKey.nonce.base64(),
                ciphertext = wrappedKey.ciphertext.base64(),
                opsLimit = crypto.recoveryKdfPolicy.opsLimit.toString(),
                memLimit = crypto.recoveryKdfPolicy.memLimit,
                algorithm = crypto.recoveryKdfPolicy.algorithm,
            ),
        )
    }

    private fun createVerifier(
        workspaceId: String,
        workspaceKey: WorkspaceMasterKey,
    ): AeadCiphertext =
        crypto.encryptAead(
            key = crypto.deriveSubkey(workspaceKey, WorkspaceSubkey.AUTHENTICATION),
            associatedData = verifierAssociatedData(workspaceId),
            plaintext = verifierPlaintext(workspaceId),
        )

    private fun verifyWorkspaceKey(
        metadata: PersistedWorkspaceKeyMetadata,
        workspaceKey: WorkspaceMasterKey,
    ): Boolean {
        val verifier = metadata.verifier.toAeadCiphertextOrNull() ?: return false
        val decrypted = crypto.decryptAead(
            key = crypto.deriveSubkey(workspaceKey, WorkspaceSubkey.AUTHENTICATION),
            associatedData = verifierAssociatedData(metadata.workspaceId),
            ciphertext = verifier,
        )
        return decrypted is CryptoResult.Success &&
            decrypted.value.contentEquals(verifierPlaintext(metadata.workspaceId))
    }

    private fun unwrapRecoveryKey(
        metadata: PersistedWorkspaceKeyMetadata,
        recoveryMaterial: String,
    ): WorkspaceMasterKey? {
        val wrapper = metadata.recovery.toAeadCiphertextOrNull() ?: return null
        val salt = runCatching { metadata.recovery.salt.decodeBase64Bytes() }.getOrNull() ?: return null
        val policy = RecoveryKdfPolicy(
            opsLimit = metadata.recovery.opsLimit.toULongOrNull() ?: return null,
            memLimit = metadata.recovery.memLimit,
            algorithm = metadata.recovery.algorithm,
        )
        val recoveryKey = crypto.deriveRecoveryWrappingKey(
            recoveryMaterial = recoveryMaterial,
            salt = salt,
            policy = policy,
        )
        return when (
            val decrypted = crypto.decryptAead(
                key = recoveryKey,
                associatedData = recoveryAssociatedData(metadata.workspaceId),
                ciphertext = wrapper,
            )
        ) {
            is CryptoResult.Success -> runCatching { crypto.workspaceKeyFromBytes(decrypted.value) }.getOrNull()
            CryptoResult.AuthenticationFailed,
            CryptoResult.InvalidCiphertext,
            -> null
        }
    }

    private fun persistMetadata(
        metadataJson: String,
        deviceName: String,
        platform: String,
    ) {
        localRepository.persistWorkspaceMetadata(
            settingKey = WORKSPACE_KEY_METADATA_SETTING_KEY,
            metadataJson = metadataJson,
            deviceName = deviceName,
            platform = platform,
        )
    }

    private fun loadMetadata(): PersistedWorkspaceKeyMetadata? =
        localRepository.getSetting(WORKSPACE_KEY_METADATA_SETTING_KEY)
            ?.value
            ?.let(::decodeMetadata)

    private fun loadRotationState(): PersistedWorkspaceKeyRotationState? =
        localRepository.getSetting(WORKSPACE_KEY_ROTATION_SETTING_KEY)?.value?.let { encoded ->
            runCatching { json.decodeFromString<PersistedWorkspaceKeyRotationState>(encoded) }.getOrNull()
        }

    private fun persistRotationState(state: PersistedWorkspaceKeyRotationState) {
        localRepository.putLocalOnlySetting(WORKSPACE_KEY_ROTATION_SETTING_KEY, json.encodeToString(state))
    }

    private fun loadKeyArchives(): PersistedWorkspaceKeyArchives =
        localRepository.getSetting(WORKSPACE_KEY_ARCHIVES_SETTING_KEY)?.value?.let { encoded ->
            runCatching { json.decodeFromString<PersistedWorkspaceKeyArchives>(encoded) }.getOrNull()
        } ?: PersistedWorkspaceKeyArchives()

    private fun decodeMetadata(metadataJson: String): PersistedWorkspaceKeyMetadata? =
        try {
            json.decodeFromString(PersistedWorkspaceKeyMetadata.serializer(), metadataJson)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun encodeMetadata(metadata: PersistedWorkspaceKeyMetadata): String =
        json.encodeToString(PersistedWorkspaceKeyMetadata.serializer(), metadata)

    private fun verifierAssociatedData(workspaceId: String): ByteArray =
        "someday-workspace-verifier-v2|$workspaceId".encodeToByteArray()

    private fun recoveryAssociatedData(workspaceId: String): ByteArray =
        "someday-workspace-recovery-v2|$workspaceId".encodeToByteArray()

    private fun verifierPlaintext(workspaceId: String): ByteArray =
        "someday-workspace-key-verifier|$workspaceId".encodeToByteArray()

    companion object {
        const val WORKSPACE_KEY_METADATA_SETTING_KEY = "encryption.workspace.key_metadata"
        const val WORKSPACE_KEY_ROTATION_SETTING_KEY = "encryption.workspace.key_rotation_v2"
        const val WORKSPACE_KEY_ARCHIVES_SETTING_KEY = "encryption.workspace.key_archives_v2"
        private const val RECOVERY_SALT_BYTES = 16

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

@Serializable
internal data class PersistedWorkspaceKeyMetadata(
    val version: Int = 1,
    val workspaceId: String,
    val createdAt: String,
    val restoredAt: String? = null,
    val keyAlgorithm: String = "XCHACHA20-POLY1305-IETF",
    val recoveryKdf: String = "ARGON2ID13",
    val keyLengthBytes: Int = WorkspaceMasterKey.WORKSPACE_KEY_BYTES,
    val secureStorageAlias: String,
    val verifier: PersistedAeadCiphertext,
    val recovery: PersistedRecoveryWrapper,
)

@Serializable
internal data class PersistedAeadCiphertext(
    val nonce: String,
    val ciphertext: String,
) {
    fun toAeadCiphertextOrNull(): AeadCiphertext? =
        runCatching {
            AeadCiphertext(
                nonce = nonce.decodeBase64Bytes(),
                ciphertext = ciphertext.decodeBase64Bytes(),
            )
        }.getOrNull()
}

@Serializable
internal data class PersistedRecoveryWrapper(
    val salt: String,
    val nonce: String,
    val ciphertext: String,
    val opsLimit: String,
    val memLimit: Int,
    val algorithm: Int,
) {
    fun toAeadCiphertextOrNull(): AeadCiphertext? =
        runCatching {
            AeadCiphertext(
                nonce = nonce.decodeBase64Bytes(),
                ciphertext = ciphertext.decodeBase64Bytes(),
            )
        }.getOrNull()
}

@Serializable
private data class PersistedWorkspaceKeyRotationState(
    val version: Int = 1,
    val token: String,
    val sourceEpochId: String,
    val sourceSecureStorageAlias: String,
    val sourceKeyFingerprint: String,
    val targetSecureStorageAlias: String,
    val targetKeyFingerprint: String,
    val targetMetadataJson: String,
) {
    fun toPublic(): PendingWorkspaceKeyRotation = PendingWorkspaceKeyRotation(
        token,
        sourceEpochId,
        sourceKeyFingerprint,
        targetKeyFingerprint,
    )
}

@Serializable
private data class PersistedWorkspaceKeyArchiveEntry(
    val epochId: String,
    val secureStorageAlias: String,
    val keyFingerprint: String,
)

@Serializable
private data class PersistedWorkspaceKeyArchives(
    val version: Int = 1,
    val currentEpochId: String? = null,
    val entries: List<PersistedWorkspaceKeyArchiveEntry> = emptyList(),
)
