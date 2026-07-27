@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.crypto

import saien.someday.data.local.EntityType
import saien.someday.data.local.LocalIdGenerator
import saien.someday.data.local.LocationInput
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceKeyRepositoryTest {
    @Test
    fun firstRunGeneratesWrappedWorkspaceAndRedactsSecrets() =
        withFixture { fixture ->
            val setup = fixture.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Developer Mac",
                platform = "desktop",
            )
            val rawKey = checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull())

            val metadataJson = checkNotNull(
                fixture.localRepository.getSetting(WorkspaceKeyRepository.WORKSPACE_KEY_METADATA_SETTING_KEY),
            ).value
            val deviceMetadata = checkNotNull(
                fixture.localRepository.getDevice("device-a"),
            ).workspaceKeyMetadata

            assertIs<WorkspaceUnlockState.Unlocked>(fixture.workspaceKeys.startupState())
            assertTrue(fixture.secureKeyStore.contains(setup.secureStorageAlias))
            assertFalse(metadataJson.contains(rawKey.rawKeyBase64ForTest()))
            assertFalse(metadataJson.contains(rawKey.rawKeyHexForTest()))
            assertFalse(metadataJson.contains(setup.recoveryMaterial.revealForUserConfirmation()))
            assertFalse(deviceMetadata.orEmpty().contains(rawKey.rawKeyBase64ForTest()))
            assertFalse(deviceMetadata.orEmpty().contains(setup.recoveryMaterial.revealForUserConfirmation()))
            assertTrue(metadataJson.contains("XCHACHA20-POLY1305-IETF"))
            assertTrue(metadataJson.contains("ARGON2ID13"))
            assertTrue(setup.recoveryMaterial.toString().contains("redacted"))
            assertTrue(rawKey.toString().contains("redacted"))
            assertTrue(fixture.workspaceKeys.verifyRecoveryMaterial(setup.recoveryMaterial.revealForUserConfirmation()))
            assertFalse(fixture.workspaceKeys.verifyRecoveryMaterial("SOMEDAY-0000-0000-0000-0000"))
            assertTrue(
                fixture.localRepository
                    .getSyncMetadata(WorkspaceKeyRepository.WORKSPACE_KEY_METADATA_SETTING_KEY, EntityType.SETTING)
                    ?.dirty == true,
            )
        }

    @Test
    fun recoveryMaterialDisplaySessionRequiresExplicitRevealAndHidesAfterDismissal() =
        withFixture { fixture ->
            val setup = fixture.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Developer Mac",
                platform = "desktop",
            )
            val recoveryCode = setup.recoveryMaterial.revealForUserConfirmation()
            val displaySession = RecoveryMaterialDisplaySession(setup.recoveryMaterial)

            assertNull(displaySession.visibleRecoveryMaterial())
            assertFalse(displaySession.toString().contains(recoveryCode))
            assertEquals(recoveryCode, displaySession.revealForExplicitUserConfirmation())
            assertEquals(recoveryCode, displaySession.visibleRecoveryMaterial())
            assertTrue(displaySession.verify(recoveryCode, fixture.workspaceKeys::verifyRecoveryMaterial))
            assertFalse(displaySession.verify("$recoveryCode-WRONG", fixture.workspaceKeys::verifyRecoveryMaterial))

            displaySession.dismiss()

            assertNull(displaySession.visibleRecoveryMaterial())
            assertFalse(displaySession.toString().contains(recoveryCode))
            assertTrue(displaySession.toString().contains("redacted"))
        }

    @Test
    fun validRecoveryMaterialRestoresSecondDeviceAndDecryptsImportedObject() =
        withFixture { firstDevice ->
            val firstSetup = firstDevice.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Mac",
                platform = "desktop",
            )
            val firstKey = checkNotNull(firstDevice.workspaceKeys.unlockedKeyOrNull())
            val metadataJson = checkNotNull(firstDevice.workspaceKeys.exportRecoveryMetadataJson())
            val importAssociatedData = "note|note-1|7|hash-note-1".encodeToByteArray()
            val encryptedImportedPayload = firstDevice.crypto.encryptAead(
                key = firstDevice.crypto.deriveSubkey(firstKey, WorkspaceSubkey.OBJECTS),
                associatedData = importAssociatedData,
                plaintext = """{"title":"Diary","markdownBody":"second-device sentinel"}""".encodeToByteArray(),
            )

            withFixture(deviceId = "device-b") { secondDevice ->
                val restored = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = metadataJson,
                    recoveryMaterial = firstSetup.recoveryMaterial.revealForUserConfirmation(),
                    deviceName = "iPhone",
                    platform = "ios",
                )
                val secondKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                val decrypted = secondDevice.crypto.decryptAead(
                    key = secondDevice.crypto.deriveSubkey(secondKey, WorkspaceSubkey.OBJECTS),
                    associatedData = importAssociatedData,
                    ciphertext = encryptedImportedPayload,
                )

                assertIs<WorkspaceRestoreResult.Restored>(restored)
                assertEquals(firstKey.fingerprint, secondKey.fingerprint)
                assertIs<CryptoResult.Success<ByteArray>>(decrypted)
                assertEquals(
                    """{"title":"Diary","markdownBody":"second-device sentinel"}""",
                    decrypted.value.decodeToString(),
                )
                assertEquals("device-b", secondDevice.localRepository.getDevice("device-b")?.id)
                assertIs<WorkspaceUnlockState.Unlocked>(secondDevice.workspaceKeys.startupState())
            }
        }

    @Test
    fun joinPackageCanReplaceAutoCreatedWorkspaceOnSecondDevice() =
        withFixture { firstDevice ->
            firstDevice.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Mac",
                platform = "desktop",
            )
            val firstKey = checkNotNull(firstDevice.workspaceKeys.unlockedKeyOrNull())
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                firstDevice.workspaceKeys.createWorkspaceJoinPackage(),
            )

            withFixture(deviceId = "device-b") { secondDevice ->
                secondDevice.workspaceKeys.createFirstRunWorkspace(
                    deviceName = "iPhone",
                    platform = "ios",
                )
                val temporaryKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                assertNotEquals(firstKey.fingerprint, temporaryKey.fingerprint)

                val restored = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = joinPackage.metadataJson,
                    recoveryMaterial = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                    deviceName = "iPhone",
                    platform = "ios",
                    replaceExistingWorkspace = true,
                )
                val joinedKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())

                assertIs<WorkspaceRestoreResult.Restored>(restored)
                assertEquals(firstKey.fingerprint, joinedKey.fingerprint)
                assertEquals(joinPackage.workspaceId, restored.state.workspaceId)
            }
        }

    @Test
    fun masterKeyRotationStagesOutOfBandPackageAndRetainsOldEpochKeyWithoutSyncingSecrets() =
        withFixture { firstDevice ->
            val initial = firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val oldKey = checkNotNull(firstDevice.workspaceKeys.unlockedKeyOrNull())
            val sourceEpoch = "10000000-0000-4000-8000-000000000001"
            val targetEpoch = "20000000-0000-4000-8000-000000000002"

            val prepared = firstDevice.workspaceKeys.prepareWorkspaceKeyRotation(sourceEpoch)
            val stagedNewKey = checkNotNull(
                firstDevice.workspaceKeys.pendingWorkspaceKeyOrNull(prepared.token),
            )

            assertNotEquals(oldKey.fingerprint, stagedNewKey.fingerprint)
            assertEquals(oldKey.fingerprint, firstDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint)
            assertEquals(prepared.targetKeyFingerprint, stagedNewKey.fingerprint)
            assertFalse(
                checkNotNull(firstDevice.localRepository.getSetting(
                    WorkspaceKeyRepository.WORKSPACE_KEY_ROTATION_SETTING_KEY,
                )).dirty,
            )
            val stagedJson = checkNotNull(firstDevice.localRepository.getSetting(
                WorkspaceKeyRepository.WORKSPACE_KEY_ROTATION_SETTING_KEY,
            )).value
            assertFalse(stagedJson.contains(prepared.recoveryMaterial.revealForUserConfirmation()))
            assertFalse(stagedJson.contains(oldKey.rawKeyBase64ForTest()))
            assertFalse(stagedJson.contains(stagedNewKey.rawKeyBase64ForTest()))
            assertFalse(firstDevice.workspaceKeys.abortWorkspaceKeyRotation(prepared.token, false))

            withFixture(deviceId = "device-b") { secondDevice ->
                assertIs<WorkspaceRestoreResult.Restored>(
                    secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                        initial.metadataJson,
                        initial.recoveryMaterial.revealForUserConfirmation(),
                        "iPhone",
                        "ios",
                    ),
                )
                val received = assertIs<WorkspaceKeyRotationStageResult.Staged>(
                    secondDevice.workspaceKeys.stageWorkspaceKeyRotation(
                        sourceEpoch,
                        prepared.targetMetadataJson,
                        prepared.recoveryMaterial.revealForUserConfirmation(),
                    ),
                )
                assertEquals(prepared.targetKeyFingerprint, received.pending.targetKeyFingerprint)
                assertEquals(oldKey.fingerprint, secondDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint)
                assertIs<WorkspaceUnlockResult.Unlocked>(
                    secondDevice.workspaceKeys.commitWorkspaceKeyRotation(received.pending.token, targetEpoch),
                )
                assertEquals(
                    prepared.targetKeyFingerprint,
                    secondDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint,
                )
                assertEquals(
                    oldKey.fingerprint,
                    secondDevice.workspaceKeys.workspaceKeyForEpochOrNull(sourceEpoch)?.fingerprint,
                )
                assertEquals(
                    prepared.targetKeyFingerprint,
                    secondDevice.workspaceKeys.workspaceKeyForEpochOrNull(targetEpoch)?.fingerprint,
                )
                assertNull(secondDevice.workspaceKeys.workspaceKeyForEpochOrNull("unknown-epoch"))
            }

            assertIs<WorkspaceUnlockResult.Unlocked>(
                firstDevice.workspaceKeys.commitWorkspaceKeyRotation(prepared.token, targetEpoch),
            )
            assertEquals(prepared.targetKeyFingerprint, firstDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint)
            assertEquals(oldKey.fingerprint, firstDevice.workspaceKeys.workspaceKeyForEpochOrNull(sourceEpoch)?.fingerprint)
            assertEquals(
                prepared.targetKeyFingerprint,
                firstDevice.workspaceKeys.workspaceKeyForEpochOrNull(targetEpoch)?.fingerprint,
            )
            assertNull(firstDevice.workspaceKeys.pendingWorkspaceKeyRotation())
            assertFalse(checkNotNull(firstDevice.localRepository.getSetting(
                WorkspaceKeyRepository.WORKSPACE_KEY_ARCHIVES_SETTING_KEY,
            )).dirty)
            assertFalse(firstDevice.workspaceKeys.releaseWorkspaceKeyForEpoch(targetEpoch))
            assertTrue(firstDevice.workspaceKeys.releaseWorkspaceKeyForEpoch(sourceEpoch))
            assertNull(firstDevice.workspaceKeys.workspaceKeyForEpochOrNull(sourceEpoch))
        }

    @Test
    fun wrongRecoveryMaterialFailsSafelyWithoutWorkspaceOrDirtyStateMutation() =
        withFixture { firstDevice ->
            val firstSetup = firstDevice.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Mac",
                platform = "desktop",
            )
            val metadataJson = checkNotNull(firstDevice.workspaceKeys.exportRecoveryMetadataJson())

            withFixture(deviceId = "device-b") { secondDevice ->
                val notebook = secondDevice.localRepository.createNotebook("Offline")
                val note = secondDevice.localRepository.createNote(
                    notebookId = notebook.id,
                    title = "Local dirty note",
                    markdownBody = "This local plaintext must not be corrupted",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    location = LocationInput(placeText = "Local only"),
                )
                val syncBefore = checkNotNull(secondDevice.localRepository.getSyncMetadata(note.id, EntityType.NOTE))

                val failed = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = metadataJson,
                    recoveryMaterial = firstSetup.recoveryMaterial.revealForUserConfirmation() + "-WRONG",
                    deviceName = "iPhone",
                    platform = "ios",
                )
                val protectedRepository = WorkspaceProtectedLocalDataRepository(
                    localRepository = secondDevice.localRepository,
                    workspaceKeys = secondDevice.workspaceKeys,
                )

                assertIs<WorkspaceRestoreResult.Failed>(failed)
                assertEquals(WorkspaceUnlockFailure.AUTHENTICATION_FAILED, failed.reason)
                assertNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                assertNull(secondDevice.localRepository.getSetting(WorkspaceKeyRepository.WORKSPACE_KEY_METADATA_SETTING_KEY))
                assertFalse(secondDevice.secureKeyStore.containsAny())
                assertEquals("This local plaintext must not be corrupted", secondDevice.localRepository.getNote(note.id)?.markdownBody)
                assertEquals(syncBefore, secondDevice.localRepository.getSyncMetadata(note.id, EntityType.NOTE))
                assertIs<PlaintextAccessResult.Locked>(protectedRepository.getNote(note.id))
            }
        }

    @Test
    fun returningUserUnlocksBeforePlaintextNoteAppearsAfterRestart() =
        withFixture { fixture ->
            fixture.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Developer Mac",
                platform = "desktop",
            )
            val notebook = fixture.localRepository.createNotebook("Diary")
            val note = fixture.localRepository.createNote(
                notebookId = notebook.id,
                title = "Restart note",
                markdownBody = "plaintext-after-unlock-only",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )

            val restartedKeys = WorkspaceKeyRepository(
                localRepository = fixture.localRepository,
                secureKeyStore = fixture.secureKeyStore,
                crypto = fixture.crypto,
                clock = { Instant.fromEpochMilliseconds(2_000) },
                aliasGenerator = SequentialAliasGenerator("restart-alias"),
            )
            val protectedRepository = WorkspaceProtectedLocalDataRepository(
                localRepository = fixture.localRepository,
                workspaceKeys = restartedKeys,
            )

            assertIs<WorkspaceUnlockState.Locked>(restartedKeys.startupState())
            assertIs<PlaintextAccessResult.Locked>(protectedRepository.getNote(note.id))
            assertFalse(protectedRepository.getNote(note.id).toString().contains("plaintext-after-unlock-only"))

            val unlocked = restartedKeys.unlockWithSecureStorage()

            assertIs<WorkspaceUnlockResult.Unlocked>(unlocked)
            val plaintextResult = protectedRepository.getNote(note.id)
            val available = assertIs<PlaintextAccessResult.Available<*>>(plaintextResult)
            assertEquals("plaintext-after-unlock-only", (available.value as saien.someday.data.local.Note).markdownBody)
        }

    @Test
    fun replaceJoinIsBlockedWhenLocalV2AuthorityIsPresent() =
        withFixture { firstDevice ->
            val setup = firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val joinPackage = firstDevice.workspaceKeys.createWorkspaceJoinPackage()
            assertIs<WorkspaceJoinPackageResult.Created>(joinPackage)

            withFixture(deviceId = "device-b") { secondDevice ->
                secondDevice.workspaceKeys.createFirstRunWorkspace("iPhone", "ios")
                val joiner = secondDevice.workspaceKeys.workspaceJoiner(
                    deviceName = "iPhone",
                    platform = "ios",
                    localV2KeyBoundStatePresent = { true },
                )
                val blocked = joiner.join(
                    saien.someday.domain.settings.WorkspaceJoinPackage(
                        metadataJson = joinPackage.metadataJson,
                        recoveryCode = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        workspaceId = joinPackage.workspaceId,
                        keyFingerprint = joinPackage.keyFingerprint,
                    ),
                )
                assertFalse(blocked.success)
                assertTrue(blocked.message.contains("local Sync V2 history"))
                // Key must remain the first-run identity, not the leader package.
                assertNotEquals(
                    setup.state.keyFingerprint,
                    secondDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint,
                )
            }
        }

    private fun withFixture(
        deviceId: String = "device-a",
        block: (WorkspaceFixture) -> Unit,
    ) {
        val dbPath = Files.createTempFile("someday-workspace-key-", ".db")
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val driver = createSomedayJdbcDriver(jdbcUrl)
        val database = SomedayDatabase(driver)
        val localRepository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = deviceId,
            clock = { Instant.fromEpochMilliseconds(1_000) },
            idGenerator = SequentialTestIdGenerator(),
        )
        val secureKeyStore = InMemorySecureWorkspaceKeyStore()
        val crypto = SodiumWorkspaceCrypto(recoveryKdfPolicy = RecoveryKdfPolicy.forTests())
        val workspaceKeys = WorkspaceKeyRepository(
            localRepository = localRepository,
            secureKeyStore = secureKeyStore,
            crypto = crypto,
            clock = { Instant.fromEpochMilliseconds(1_000) },
            aliasGenerator = SequentialAliasGenerator("alias-$deviceId"),
        )

        try {
            block(WorkspaceFixture(localRepository, secureKeyStore, crypto, workspaceKeys))
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }

    private data class WorkspaceFixture(
        val localRepository: SqlDelightLocalDataRepository,
        val secureKeyStore: InMemorySecureWorkspaceKeyStore,
        val crypto: SodiumWorkspaceCrypto,
        val workspaceKeys: WorkspaceKeyRepository,
    )

    private class SequentialTestIdGenerator : LocalIdGenerator {
        private var next = 0

        override fun newId(prefix: String): String {
            next += 1
            return "$prefix-$next"
        }
    }

    private class SequentialAliasGenerator(
        private val prefix: String,
    ) : SecureStorageAliasGenerator {
        private var next = 0

        override fun newAlias(workspaceId: String): String {
            next += 1
            return "$prefix-$workspaceId-$next"
        }
    }
}
