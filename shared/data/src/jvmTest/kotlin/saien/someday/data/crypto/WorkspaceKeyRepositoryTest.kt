@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.crypto

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.WorkspacePairingReason
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceKeyRepositoryTest {
    @Test
    fun protocolV1RecoveryKdfProfileIsFrozenInProductionAndPortableMetadata() {
        val policy = RecoveryKdfPolicy.protocolV1()

        assertEquals(2UL, policy.opsLimit)
        assertEquals(67_108_864, policy.memLimit)
        assertEquals(2, policy.algorithm)
        assertEquals(policy, SodiumWorkspaceCrypto().recoveryKdfPolicy)

        withFixture(recoveryKdfPolicy = policy) { fixture ->
            fixture.workspaceKeys.createFirstRunWorkspace("Protocol v1", "desktop")
            val recoveryPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                fixture.workspaceKeys.createWorkspaceRecoveryPackage(),
            )
            val portable = Json.decodeFromString<PersistedPortableWorkspaceRecoveryEnvelope>(
                recoveryPackage.metadataJson,
            )

            assertEquals("2", portable.recovery.opsLimit)
            assertEquals(67_108_864, portable.recovery.memLimit)
            assertEquals(2, portable.recovery.algorithm)
        }
    }

    @Test
    fun firstRunGeneratesWrappedWorkspaceAndRedactsSecrets() =
        withFixture { fixture ->
            val setup = fixture.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Developer Mac",
                platform = "desktop",
            )
            val rawKey = checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull())
            assertEquals(setup.state.workspaceId, fixture.workspaceKeys.workspaceIdOrNull())

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
            assertEquals(metadataJson, fixture.workspaceKeys.exportRecoveryMetadataJson())
        }

    @Test
    fun freshReplacementCommitsCallerCleanupAndRotatesWorkspaceIdentityAndKey() =
        withFixture { fixture ->
            val original = fixture.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val originalKey = checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull())
            fixture.localRepository.putLocalOnlySetting("replacement-sentinel", "old")

            val replacement = fixture.workspaceKeys.replaceWithFreshWorkspace(
                deviceName = "Mac",
                platform = "desktop",
                beforeMetadataReplacement = {
                    fixture.localRepository.putLocalOnlySetting("replacement-sentinel", "cleaned")
                },
                afterMetadataReplacement = { _, workspaceId ->
                    assertEquals(workspaceId, fixture.workspaceKeys.workspaceIdOrNull())
                    fixture.localRepository.putLocalOnlySetting("replacement-hook", workspaceId)
                },
            )
            val replacementKey = checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull())

            assertNotEquals(original.state.workspaceId, replacement.state.workspaceId)
            assertNotEquals(originalKey.fingerprint, replacementKey.fingerprint)
            assertEquals("cleaned", fixture.localRepository.getSetting("replacement-sentinel")?.value)
            assertEquals(
                replacement.state.workspaceId,
                fixture.localRepository.getSetting("replacement-hook")?.value,
            )
            assertFalse(fixture.secureKeyStore.contains(original.secureStorageAlias))
            assertTrue(fixture.secureKeyStore.contains(replacement.secureStorageAlias))
            assertTrue(
                fixture.workspaceKeys.verifyRecoveryMaterial(
                    replacement.recoveryMaterial.revealForUserConfirmation(),
                ),
            )
        }

    @Test
    fun failedFreshReplacementRollsBackMetadataAndCallerCleanup() =
        withFixture { fixture ->
            val original = fixture.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val originalMetadata = checkNotNull(fixture.workspaceKeys.exportRecoveryMetadataJson())
            val originalKey = checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull())
            fixture.localRepository.putLocalOnlySetting("replacement-sentinel", "old")

            assertFailsWith<IllegalStateException> {
                fixture.workspaceKeys.replaceWithFreshWorkspace(
                    deviceName = "Mac",
                    platform = "desktop",
                    beforeMetadataReplacement = {
                        fixture.localRepository.putLocalOnlySetting("replacement-sentinel", "mutated")
                    },
                    afterMetadataReplacement = { _, _ -> error("simulated binding failure") },
                )
            }

            assertEquals(original.state.workspaceId, fixture.workspaceKeys.workspaceIdOrNull())
            assertEquals(originalMetadata, fixture.workspaceKeys.exportRecoveryMetadataJson())
            assertEquals("old", fixture.localRepository.getSetting("replacement-sentinel")?.value)
            assertContentEquals(
                originalKey.rawBytesCopy(),
                checkNotNull(fixture.workspaceKeys.unlockedKeyOrNull()).rawBytesCopy(),
            )
            assertTrue(fixture.secureKeyStore.contains(original.secureStorageAlias))
            assertNull(
                fixture.localRepository.getSetting(
                    WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                ),
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
    fun portableRecoveryPackageOmitsDeviceAliasAndRestoresTheSameWorkspaceKey() =
        withFixture { firstDevice ->
            val setup = firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val originalKey = checkNotNull(firstDevice.workspaceKeys.unlockedKeyOrNull())
            val recovery = assertIs<WorkspaceJoinPackageResult.Created>(
                firstDevice.workspaceKeys.createWorkspaceRecoveryPackage(),
            )
            val recoveryCode = recovery.recoveryMaterial.revealForUserConfirmation()

            assertTrue(recovery.metadataJson.contains("someday.workspace-recovery-metadata"))
            assertFalse(recovery.metadataJson.contains("secureStorageAlias"))
            assertFalse(recovery.metadataJson.contains(setup.secureStorageAlias))
            assertFalse(recovery.metadataJson.contains(recoveryCode))
            assertFalse(recovery.metadataJson.contains(originalKey.rawKeyHexForTest()))

            withFixture(deviceId = "device-b") { secondDevice ->
                val temporary = secondDevice.workspaceKeys.createFirstRunWorkspace("Phone", "android")
                val beforeKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                val normalizedInput = recoveryCode.lowercase().replace('-', ' ')
                val restored = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = recovery.metadataJson,
                    recoveryMaterial = normalizedInput,
                    deviceName = "Phone",
                    platform = "android",
                    replaceExistingWorkspace = true,
                    expectedWorkspaceId = recovery.workspaceId,
                    expectedKeyFingerprint = recovery.keyFingerprint,
                    beforeMetadataReplacement = {},
                    afterMetadataReplacement = { _, _ -> },
                )
                val restoredKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())

                assertIs<WorkspaceRestoreResult.Restored>(restored)
                assertNotEquals(temporary.state.workspaceId, restored.state.workspaceId)
                assertNotEquals(beforeKey.fingerprint, restoredKey.fingerprint)
                assertEquals(originalKey.fingerprint, restoredKey.fingerprint)
            }
        }

    @Test
    fun wrongCodeForPortableRecoveryPackagePreservesTheExistingWorkspace() =
        withFixture { firstDevice ->
            firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val recovery = assertIs<WorkspaceJoinPackageResult.Created>(
                firstDevice.workspaceKeys.createWorkspaceRecoveryPackage(),
            )

            withFixture(deviceId = "device-b") { secondDevice ->
                val local = secondDevice.workspaceKeys.createFirstRunWorkspace("Phone", "android")
                val metadataBefore = checkNotNull(secondDevice.workspaceKeys.exportRecoveryMetadataJson())
                val keyBefore = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                var cleanupCalled = false
                val failed = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = recovery.metadataJson,
                    recoveryMaterial = "SOMEDAY-0000-0000-0000-0000-0000-0000-0000-0000",
                    deviceName = "Phone",
                    platform = "android",
                    replaceExistingWorkspace = true,
                    expectedWorkspaceId = recovery.workspaceId,
                    expectedKeyFingerprint = recovery.keyFingerprint,
                    beforeMetadataReplacement = { cleanupCalled = true },
                    afterMetadataReplacement = { _, _ -> },
                )

                assertIs<WorkspaceRestoreResult.Failed>(failed)
                assertEquals(WorkspaceUnlockFailure.AUTHENTICATION_FAILED, failed.reason)
                assertFalse(cleanupCalled)
                assertEquals(local.state.workspaceId, secondDevice.workspaceKeys.workspaceIdOrNull())
                assertEquals(metadataBefore, secondDevice.workspaceKeys.exportRecoveryMetadataJson())
                assertContentEquals(
                    keyBefore.rawBytesCopy(),
                    checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull()).rawBytesCopy(),
                )
            }
        }

    @Test
    fun portableRecoveryRejectsADeclaredKeyFingerprintThatDoesNotMatchTheWrappedKey() =
        withFixture { firstDevice ->
            firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val recovery = assertIs<WorkspaceJoinPackageResult.Created>(
                firstDevice.workspaceKeys.createWorkspaceRecoveryPackage(),
            )
            val mismatchedFingerprint =
                (if (recovery.keyFingerprint.first() == 'f') "e" else "f").repeat(32)
            val tamperedMetadata = recovery.metadataJson.replace(
                recovery.keyFingerprint,
                mismatchedFingerprint,
            )

            withFixture(deviceId = "device-b") { secondDevice ->
                secondDevice.workspaceKeys.createFirstRunWorkspace("Phone", "android")
                var cleanupCalled = false

                val failed = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = tamperedMetadata,
                    recoveryMaterial = recovery.recoveryMaterial.revealForUserConfirmation(),
                    deviceName = "Phone",
                    platform = "android",
                    replaceExistingWorkspace = true,
                    expectedWorkspaceId = recovery.workspaceId,
                    expectedKeyFingerprint = recovery.keyFingerprint,
                    beforeMetadataReplacement = { cleanupCalled = true },
                    afterMetadataReplacement = { _, _ -> },
                )

                assertIs<WorkspaceRestoreResult.Failed>(failed)
                assertEquals(WorkspaceUnlockFailure.INVALID_METADATA, failed.reason)
                assertFalse(cleanupCalled)
            }
        }

    @Test
    fun portableRecoveryRejectsUnsupportedKdfAndCipherParametersBeforeReplacement() =
        withFixture { firstDevice ->
            firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val recovery = assertIs<WorkspaceJoinPackageResult.Created>(
                firstDevice.workspaceKeys.createWorkspaceRecoveryPackage(),
            )
            val json = Json { encodeDefaults = true }
            val metadata = json.decodeFromString<PersistedPortableWorkspaceRecoveryEnvelope>(
                recovery.metadataJson,
            )
            val unsupported = listOf(
                metadata.copy(keyAlgorithm = "unsupported"),
                metadata.copy(recoveryKdf = "unsupported"),
                metadata.copy(keyLengthBytes = WorkspaceMasterKey.WORKSPACE_KEY_BYTES + 1),
                metadata.copy(
                    recovery = metadata.recovery.copy(
                        opsLimit = (metadata.recovery.opsLimit.toULong() + 1UL).toString(),
                    ),
                ),
                metadata.copy(
                    recovery = metadata.recovery.copy(memLimit = metadata.recovery.memLimit + 1),
                ),
                metadata.copy(
                    recovery = metadata.recovery.copy(algorithm = metadata.recovery.algorithm + 1),
                ),
                metadata.copy(
                    recovery = metadata.recovery.copy(salt = byteArrayOf(1).base64()),
                ),
                metadata.copy(
                    recovery = metadata.recovery.copy(nonce = byteArrayOf(1).base64()),
                ),
                metadata.copy(
                    recovery = metadata.recovery.copy(ciphertext = byteArrayOf(1).base64()),
                ),
            )

            withFixture(deviceId = "device-b") { secondDevice ->
                val local = secondDevice.workspaceKeys.createFirstRunWorkspace("Phone", "android")
                unsupported.forEach { candidate ->
                    var cleanupCalled = false
                    val failed = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                        metadataJson = json.encodeToString(candidate),
                        recoveryMaterial = recovery.recoveryMaterial.revealForUserConfirmation(),
                        deviceName = "Phone",
                        platform = "android",
                        replaceExistingWorkspace = true,
                        expectedWorkspaceId = recovery.workspaceId,
                        expectedKeyFingerprint = recovery.keyFingerprint,
                        beforeMetadataReplacement = { cleanupCalled = true },
                        afterMetadataReplacement = { _, _ -> },
                    )

                    assertIs<WorkspaceRestoreResult.Failed>(failed)
                    assertEquals(WorkspaceUnlockFailure.INVALID_METADATA, failed.reason)
                    assertFalse(cleanupCalled)
                    assertEquals(local.state.workspaceId, secondDevice.workspaceKeys.workspaceIdOrNull())
                }
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
                    beforeMetadataReplacement = {},
                    afterMetadataReplacement = { _, _ -> },
                )
                val joinedKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())

                assertIs<WorkspaceRestoreResult.Restored>(restored)
                assertEquals(firstKey.fingerprint, joinedKey.fingerprint)
                assertEquals(joinPackage.workspaceId, restored.state.workspaceId)
            }
        }

    @Test
    fun wrongRecoveryMaterialFailsWithoutMutatingDeviceLocalFoundationState() =
        withFixture { firstDevice ->
            val firstSetup = firstDevice.workspaceKeys.createFirstRunWorkspace(
                deviceName = "Mac",
                platform = "desktop",
            )
            val metadataJson = checkNotNull(firstDevice.workspaceKeys.exportRecoveryMetadataJson())

            withFixture(deviceId = "device-b") { secondDevice ->
                secondDevice.localRepository.putLocalOnlySetting("sentinel", "must-survive")

                val failed = secondDevice.workspaceKeys.restoreWorkspaceFromRecovery(
                    metadataJson = metadataJson,
                    recoveryMaterial = firstSetup.recoveryMaterial.revealForUserConfirmation() + "-WRONG",
                    deviceName = "iPhone",
                    platform = "ios",
                )
                assertIs<WorkspaceRestoreResult.Failed>(failed)
                assertEquals(WorkspaceUnlockFailure.AUTHENTICATION_FAILED, failed.reason)
                assertNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                assertNull(secondDevice.localRepository.getSetting(WorkspaceKeyRepository.WORKSPACE_KEY_METADATA_SETTING_KEY))
                assertFalse(secondDevice.secureKeyStore.containsAny())
                assertEquals("must-survive", secondDevice.localRepository.getSetting("sentinel")?.value)
            }
        }

    @Test
    fun replacementWithoutExplicitAuthorizationPreservesExistingWorkspace() =
        withFixture { firstDevice ->
            firstDevice.workspaceKeys.createFirstRunWorkspace("Mac", "desktop")
            val joinPackage = firstDevice.workspaceKeys.createWorkspaceJoinPackage()
            assertIs<WorkspaceJoinPackageResult.Created>(joinPackage)

            withFixture(deviceId = "device-b") { secondDevice ->
                val localSetup = secondDevice.workspaceKeys.createFirstRunWorkspace("iPhone", "ios")
                val originalMetadata = secondDevice.workspaceKeys.exportRecoveryMetadataJson()
                val originalKey = checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull())
                var cleanupCalls = 0
                var bindingCalls = 0
                val joiner = secondDevice.workspaceKeys.workspaceJoiner(
                    deviceName = "iPhone",
                    platform = "ios",
                    beforeWorkspaceReplacement = {
                        cleanupCalls += 1
                        true
                    },
                    afterWorkspaceReplacement = { _, _, _ ->
                        bindingCalls += 1
                        true
                    },
                    afterWorkspaceReplacementCommitted = {},
                )
                val blocked = joiner.join(
                    saien.someday.domain.settings.WorkspaceJoinPackage(
                        metadataJson = joinPackage.metadataJson,
                        recoveryCode = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        workspaceId = joinPackage.workspaceId,
                        keyFingerprint = joinPackage.keyFingerprint,
                    ),
                    replaceExistingWorkspace = false,
                )
                assertFalse(blocked.success)
                assertEquals(WorkspacePairingReason.ReplacementConfirmationRequired, blocked.reason)
                assertEquals(0, cleanupCalls)
                assertEquals(0, bindingCalls)
                assertEquals(originalMetadata, secondDevice.workspaceKeys.exportRecoveryMetadataJson())
                assertEquals(localSetup.state.workspaceId, secondDevice.workspaceKeys.workspaceIdOrNull())
                assertContentEquals(
                    originalKey.rawBytesCopy(),
                    checkNotNull(secondDevice.workspaceKeys.unlockedKeyOrNull()).rawBytesCopy(),
                )
            }
        }

    @Test
    fun failedTransactionalReplacementBindingRollsBackWorkspaceMetadataAndUnlockedKey() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )
            val domainPackage = saien.someday.domain.settings.WorkspaceJoinPackage(
                joinPackage.metadataJson,
                joinPackage.recoveryMaterial.revealForUserConfirmation(),
                joinPackage.workspaceId,
                joinPackage.keyFingerprint,
            )

            withFixture(deviceId = "rollback-device") { joining ->
                val originalSetup = joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                val originalWorkspaceId = joining.workspaceKeys.workspaceIdOrNull()
                val originalKey = checkNotNull(joining.workspaceKeys.unlockedKeyOrNull())
                val originalMetadata = checkNotNull(joining.workspaceKeys.exportRecoveryMetadataJson())
                val originalDeviceMetadata = joining.localRepository
                    .getDevice("rollback-device")
                    ?.workspaceKeyMetadata
                joining.localRepository.putLocalOnlySetting("rollback-sentinel", "original")
                val stagedAlias = "alias-rollback-device-${joinPackage.workspaceId}-2"
                val result = joining.workspaceKeys.workspaceJoiner(
                    deviceName = "Joining",
                    platform = "desktop",
                    beforeWorkspaceReplacement = {
                        joining.localRepository.putLocalOnlySetting("rollback-sentinel", "mutated")
                        true
                    },
                    afterWorkspaceReplacement = { _, _, _ -> false },
                    afterWorkspaceReplacementCommitted = {},
                ).join(
                    packageData = domainPackage,
                    replaceExistingWorkspace = true,
                )

                assertFalse(result.success)
                assertEquals(WorkspacePairingReason.ReplacementFailed, result.reason)
                assertEquals(originalWorkspaceId, joining.workspaceKeys.workspaceIdOrNull())
                assertEquals(originalMetadata, joining.workspaceKeys.exportRecoveryMetadataJson())
                assertEquals(
                    originalDeviceMetadata,
                    joining.localRepository.getDevice("rollback-device")?.workspaceKeyMetadata,
                )
                assertEquals(
                    "original",
                    joining.localRepository.getSetting("rollback-sentinel")?.value,
                )
                assertContentEquals(
                    originalKey.rawBytesCopy(),
                    checkNotNull(joining.workspaceKeys.unlockedKeyOrNull()).rawBytesCopy(),
                )
                assertTrue(joining.secureKeyStore.contains(originalSetup.secureStorageAlias))
                assertFalse(joining.secureKeyStore.contains(stagedAlias))
                assertNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
            }
        }

    @Test
    fun replacementRejectsCurrentAliasReuseBeforeOverwritingTheExistingKey() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )

            withFixture(deviceId = "collision-device") { joining ->
                val original = joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                val originalMetadata = joining.workspaceKeys.exportRecoveryMetadataJson()
                val originalKey = checkNotNull(joining.secureKeyStore.get(original.secureStorageAlias))
                val collidingRepository = WorkspaceKeyRepository(
                    localRepository = joining.localRepository,
                    secureKeyStore = joining.secureKeyStore,
                    crypto = joining.crypto,
                    clock = { Instant.fromEpochMilliseconds(1_000) },
                    aliasGenerator = SecureStorageAliasGenerator { original.secureStorageAlias },
                )

                assertFailsWith<IllegalStateException> {
                    collidingRepository.restoreWorkspaceFromRecovery(
                        metadataJson = joinPackage.metadataJson,
                        recoveryMaterial = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        deviceName = "Joining",
                        platform = "desktop",
                        replaceExistingWorkspace = true,
                        beforeMetadataReplacement = {},
                        afterMetadataReplacement = { _, _ -> },
                    )
                }

                assertEquals(originalMetadata, joining.workspaceKeys.exportRecoveryMetadataJson())
                assertContentEquals(
                    originalKey.rawBytesCopy(),
                    checkNotNull(joining.secureKeyStore.get(original.secureStorageAlias)).rawBytesCopy(),
                )
                assertNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
            }
        }

    @Test
    fun partiallyPersistedStagedKeyIsRemovedWhenSecureStoragePutFails() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )

            withFixture(deviceId = "partial-put-device") { joining ->
                val original = joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                val originalMetadata = joining.workspaceKeys.exportRecoveryMetadataJson()
                val stagedAlias = "alias-partial-put-device-${joinPackage.workspaceId}-2"
                joining.secureKeyStore.failNextPutAfterSaving(stagedAlias)

                assertFailsWith<IllegalStateException> {
                    joining.workspaceKeys.restoreWorkspaceFromRecovery(
                        metadataJson = joinPackage.metadataJson,
                        recoveryMaterial = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        deviceName = "Joining",
                        platform = "desktop",
                        replaceExistingWorkspace = true,
                        beforeMetadataReplacement = {},
                        afterMetadataReplacement = { _, _ -> },
                    )
                }

                assertEquals(originalMetadata, joining.workspaceKeys.exportRecoveryMetadataJson())
                assertTrue(joining.secureKeyStore.contains(original.secureStorageAlias))
                assertFalse(joining.secureKeyStore.contains(stagedAlias))
                assertNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
            }
        }

    @Test
    fun confirmedReplacementCanReplaceLockedWorkspaceWhenPostCommitCleanupFails() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )
            val domainPackage = saien.someday.domain.settings.WorkspaceJoinPackage(
                joinPackage.metadataJson,
                joinPackage.recoveryMaterial.revealForUserConfirmation(),
                joinPackage.workspaceId,
                joinPackage.keyFingerprint,
            )

            withFixture(deviceId = "locked-device") { joining ->
                joining.workspaceKeys.createFirstRunWorkspace("Locked", "desktop")
                joining.workspaceKeys.lock()
                assertNull(joining.workspaceKeys.unlockedKeyOrNull())
                var cleanupCalls = 0

                val result = joining.workspaceKeys.workspaceJoiner(
                    deviceName = "Locked",
                    platform = "desktop",
                    beforeWorkspaceReplacement = { true },
                    afterWorkspaceReplacement = { _, _, _ -> true },
                    afterWorkspaceReplacementCommitted = {
                        cleanupCalls += 1
                        error("simulated filesystem cleanup failure")
                    },
                ).join(
                    packageData = domainPackage,
                    replaceExistingWorkspace = true,
                )

                assertTrue(result.success, result.diagnosticMessage)
                assertEquals(1, cleanupCalls)
                assertEquals(joinPackage.workspaceId, joining.workspaceKeys.workspaceIdOrNull())
                assertEquals(
                    joinPackage.keyFingerprint,
                    checkNotNull(joining.workspaceKeys.unlockedKeyOrNull()).fingerprint,
                )
            }
        }

    @Test
    fun failedOldAliasDeletionIsRetriedAndClearedDuringNextStartup() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )

            withFixture(deviceId = "retry-device") { joining ->
                val original = joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                joining.secureKeyStore.failNextRemovalOf(original.secureStorageAlias)

                val restored = assertIs<WorkspaceRestoreResult.Restored>(
                    joining.workspaceKeys.restoreWorkspaceFromRecovery(
                        metadataJson = joinPackage.metadataJson,
                        recoveryMaterial = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        deviceName = "Joining",
                        platform = "desktop",
                        replaceExistingWorkspace = true,
                        beforeMetadataReplacement = {},
                        afterMetadataReplacement = { _, _ -> },
                    ),
                )

                val pending = assertNotNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
                assertTrue(pending.value.contains(original.secureStorageAlias))
                assertTrue(joining.secureKeyStore.contains(original.secureStorageAlias))
                assertTrue(joining.secureKeyStore.contains(restored.secureStorageAlias))
                assertEquals(1, joining.secureKeyStore.removalAttempts(original.secureStorageAlias))
                joining.localRepository.putLocalOnlySetting(
                    WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    """{"aliases":["${original.secureStorageAlias}","${restored.secureStorageAlias}"]}""",
                )

                val restartedRepository = WorkspaceKeyRepository(
                    localRepository = joining.localRepository,
                    secureKeyStore = joining.secureKeyStore,
                    crypto = joining.crypto,
                    clock = { Instant.fromEpochMilliseconds(1_000) },
                )
                val startup = assertIs<WorkspaceUnlockState.Locked>(restartedRepository.startupState())

                assertEquals(joinPackage.workspaceId, startup.workspaceId)
                assertTrue(startup.secureStorageAvailable)
                assertFalse(joining.secureKeyStore.contains(original.secureStorageAlias))
                assertTrue(joining.secureKeyStore.contains(restored.secureStorageAlias))
                assertEquals(2, joining.secureKeyStore.removalAttempts(original.secureStorageAlias))
                assertEquals(0, joining.secureKeyStore.removalAttempts(restored.secureStorageAlias))
                assertNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
            }
        }

    @Test
    fun failedStagedAliasDeletionAfterRollbackIsRetriedDuringNextStartup() =
        withFixture { inviter ->
            inviter.workspaceKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertIs<WorkspaceJoinPackageResult.Created>(
                inviter.workspaceKeys.createWorkspaceJoinPackage(),
            )

            withFixture(deviceId = "staged-retry-device") { joining ->
                val original = joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                val stagedAlias = "alias-staged-retry-device-${joinPackage.workspaceId}-2"
                joining.secureKeyStore.failNextRemovalOf(stagedAlias)

                assertFailsWith<IllegalStateException> {
                    joining.workspaceKeys.restoreWorkspaceFromRecovery(
                        metadataJson = joinPackage.metadataJson,
                        recoveryMaterial = joinPackage.recoveryMaterial.revealForUserConfirmation(),
                        deviceName = "Joining",
                        platform = "desktop",
                        replaceExistingWorkspace = true,
                        beforeMetadataReplacement = {},
                        afterMetadataReplacement = { _, _ -> error("simulated binding failure") },
                    )
                }

                assertEquals(original.state.workspaceId, joining.workspaceKeys.workspaceIdOrNull())
                assertTrue(joining.secureKeyStore.contains(original.secureStorageAlias))
                assertTrue(joining.secureKeyStore.contains(stagedAlias))
                assertNotNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )

                val restartedRepository = WorkspaceKeyRepository(
                    localRepository = joining.localRepository,
                    secureKeyStore = joining.secureKeyStore,
                    crypto = joining.crypto,
                    clock = { Instant.fromEpochMilliseconds(1_000) },
                )
                val startup = assertIs<WorkspaceUnlockState.Locked>(restartedRepository.startupState())

                assertEquals(original.state.workspaceId, startup.workspaceId)
                assertTrue(joining.secureKeyStore.contains(original.secureStorageAlias))
                assertFalse(joining.secureKeyStore.contains(stagedAlias))
                assertNull(
                    joining.localRepository.getSetting(
                        WorkspaceKeyRepository.PENDING_SECURE_STORAGE_ALIAS_DELETIONS_SETTING_KEY,
                    ),
                )
            }
        }

    private fun withFixture(
        deviceId: String = "device-a",
        recoveryKdfPolicy: RecoveryKdfPolicy = RecoveryKdfPolicy.forTests(),
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
        )
        val secureKeyStore = TestSecureWorkspaceKeyStore()
        val crypto = SodiumWorkspaceCrypto(recoveryKdfPolicy = recoveryKdfPolicy)
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
        val secureKeyStore: TestSecureWorkspaceKeyStore,
        val crypto: SodiumWorkspaceCrypto,
        val workspaceKeys: WorkspaceKeyRepository,
    )

    private class TestSecureWorkspaceKeyStore : SecureWorkspaceKeyStore {
        private val delegate = InMemorySecureWorkspaceKeyStore()
        private val failedPutAliases = mutableSetOf<String>()
        private val failedRemovalAliases = mutableSetOf<String>()
        private val removalAttemptsByAlias = mutableMapOf<String, Int>()

        override fun put(
            alias: String,
            workspaceKey: WorkspaceMasterKey,
        ) {
            delegate.put(alias, workspaceKey)
            if (failedPutAliases.remove(alias)) {
                error("simulated secure-storage write acknowledgement failure")
            }
        }

        override fun get(alias: String): WorkspaceMasterKey? = delegate.get(alias)

        override fun remove(alias: String) {
            removalAttemptsByAlias[alias] = removalAttempts(alias) + 1
            if (failedRemovalAliases.remove(alias)) {
                error("simulated secure-storage deletion failure")
            }
            delegate.remove(alias)
        }

        fun containsAny(): Boolean = delegate.containsAny()

        fun failNextPutAfterSaving(alias: String) {
            failedPutAliases += alias
        }

        fun failNextRemovalOf(alias: String) {
            failedRemovalAliases += alias
        }

        fun removalAttempts(alias: String): Int = removalAttemptsByAlias[alias] ?: 0
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
