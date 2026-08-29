@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.crypto

import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

class WorkspaceKeyRepository(
    private val localRepository: SqlDelightLocalDataRepository,
    private val secureKeyStore: SecureWorkspaceKeyStore,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
    private val clock: () -> Instant = { Clock.System.now() },
    private val aliasGenerator: SecureStorageAliasGenerator = TimeBasedSecureStorageAliasGenerator(),
) {
    private var unlockedKey: WorkspaceMasterKey? = null

    fun startupState(): WorkspaceUnlockState {
        runCatching(::retryPendingSecureStorageAliasDeletions)
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

        // Journal the staged alias before secure storage so startup can clean
        // up a partially acknowledged write after an interrupted setup.
        recordPendingSecureStorageAliasDeletion(secureStorageAlias)
        try {
            secureKeyStore.put(secureStorageAlias, workspaceKey)
            persistMetadata(metadataJson, deviceName, platform)
        } catch (failure: Throwable) {
            runCatching(::retryPendingSecureStorageAliasDeletions)
            throw failure
        }
        unlockedKey = workspaceKey.copy()
        runCatching(::retryPendingSecureStorageAliasDeletions)

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

    /** Stable local workspace identity; available independently of network login state. */
    fun workspaceIdOrNull(): String? = loadMetadata()?.workspaceId

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
        beforeMetadataReplacement: (() -> Unit)? = null,
        afterMetadataReplacement: ((WorkspaceMasterKey, String) -> Unit)? = null,
    ): WorkspaceRestoreResult {
        require(!replaceExistingWorkspace ||
            (beforeMetadataReplacement != null && afterMetadataReplacement != null)
        ) {
            "Replacing a workspace requires transactional DAG cleanup and authority binding hooks."
        }
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
        check(secureStorageAlias != existingMetadata?.secureStorageAlias) {
            "Secure storage alias generation reused the current workspace key alias."
        }
        val restoredMetadata = importedMetadata.copy(
            secureStorageAlias = secureStorageAlias,
            restoredAt = clock().toString(),
        )
        val restoredMetadataJson = encodeMetadata(restoredMetadata)

        // Journal the staged alias before secure storage so startup can clean
        // it if the process stops before the replacement transaction commits.
        recordPendingSecureStorageAliasDeletion(secureStorageAlias)
        try {
            secureKeyStore.put(secureStorageAlias, workspaceKey)
            localRepository.database.transaction {
                beforeMetadataReplacement?.invoke()
                persistMetadata(restoredMetadataJson, deviceName, platform)
                existingMetadata
                    ?.secureStorageAlias
                    ?.takeUnless { it == secureStorageAlias }
                    ?.let(::recordPendingSecureStorageAliasDeletion)
                afterMetadataReplacement?.invoke(workspaceKey, importedMetadata.workspaceId)
            }
        } catch (failure: Throwable) {
            runCatching(::retryPendingSecureStorageAliasDeletions)
            throw failure
        }
        unlockedKey = workspaceKey.copy()
        runCatching(::retryPendingSecureStorageAliasDeletions)

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

    private fun recordPendingSecureStorageAliasDeletion(alias: String) {
        val pending = loadPendingSecureStorageAliasDeletions()
            ?: error("Pending secure-storage alias deletion metadata is invalid.")
        persistPendingSecureStorageAliasDeletions((pending + alias).distinct())
    }

    private fun retryPendingSecureStorageAliasDeletions() {
        val currentAlias = loadMetadata()?.secureStorageAlias
        val pending = loadPendingSecureStorageAliasDeletions() ?: return
        if (pending.isEmpty()) return

        val remaining = pending.filter { alias ->
            alias != currentAlias && runCatching { secureKeyStore.remove(alias) }.isFailure
        }
        persistPendingSecureStorageAliasDeletions(remaining)
    }

    private fun loadPendingSecureStorageAliasDeletions(): List<String>? {
        val value = localRepository
            .getSetting(PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY)
            ?.value
            ?: return emptyList()
        return try {
            json.decodeFromString(PersistedSecureStorageAliasDeletions.serializer(), value)
                .aliases
                .filter(String::isNotBlank)
                .distinct()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun persistPendingSecureStorageAliasDeletions(aliases: List<String>) {
        if (aliases.isEmpty()) {
            localRepository.deleteLocalOnlySetting(PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY)
        } else {
            localRepository.putLocalOnlySetting(
                PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                json.encodeToString(
                    PersistedSecureStorageAliasDeletions.serializer(),
                    PersistedSecureStorageAliasDeletions(aliases),
                ),
            )
        }
    }

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
        internal const val PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY =
            "encryption.workspace.pending_secure_storage_alias_deletions"
        private const val RECOVERY_SALT_BYTES = 16

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

@Serializable
internal data class PersistedSecureStorageAliasDeletions(
    val aliases: List<String>,
)

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
