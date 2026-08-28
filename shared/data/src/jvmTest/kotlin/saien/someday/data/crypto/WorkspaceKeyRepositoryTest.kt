@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.crypto

import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.LocalWorkspaceAdoptionPolicy
import saien.someday.domain.settings.WorkspacePairingReason
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
                    adoptionPolicy = LocalWorkspaceAdoptionPolicy {
                        WorkspacePairingReason.LocalWorkspaceNotReplaceable
                    },
                    beforeWorkspaceReplacement = { true },
                    afterWorkspaceReplacement = { _, _, _ -> true },
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
                assertEquals(WorkspacePairingReason.LocalWorkspaceNotReplaceable, blocked.reason)
                // Key must remain the first-run identity, not the leader package.
                assertNotEquals(
                    setup.state.keyFingerprint,
                    secondDevice.workspaceKeys.unlockedKeyOrNull()?.fingerprint,
                )
            }
        }

    @Test
    fun failedTransactionalAdoptionBindingRollsBackWorkspaceMetadataAndUnlockedKey() =
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
                joining.workspaceKeys.createFirstRunWorkspace("Joining", "desktop")
                val originalWorkspaceId = joining.workspaceKeys.workspaceIdOrNull()
                val originalFingerprint = joining.workspaceKeys.unlockedKeyOrNull()?.fingerprint
                val result = joining.workspaceKeys.workspaceJoiner(
                    deviceName = "Joining",
                    platform = "desktop",
                    adoptionPolicy = LocalWorkspaceAdoptionPolicy { null },
                    beforeWorkspaceReplacement = { true },
                    afterWorkspaceReplacement = { _, _, _ -> false },
                ).join(domainPackage)

                assertFalse(result.success)
                assertEquals(originalWorkspaceId, joining.workspaceKeys.workspaceIdOrNull())
                assertEquals(originalFingerprint, joining.workspaceKeys.unlockedKeyOrNull()?.fingerprint)
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
