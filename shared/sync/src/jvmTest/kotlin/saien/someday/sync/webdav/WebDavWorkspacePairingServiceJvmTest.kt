@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.webdav

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import saien.someday.sync.causality.v2.InMemoryWorkspaceSyncRemoteV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncRemoteTransportFactoryV2
import saien.someday.sync.causality.v2.SyncV2RuntimeService
import saien.someday.sync.causality.v2.SystemV2NotesRepository
import saien.someday.sync.pairing.WorkspacePairingToken

class WebDavWorkspacePairingServiceJvmTest {
    @Test
    fun invitationIsEncryptedClaimedOnceAndNeverDeleted() {
        val transport = MemoryPairingWebDavTransport()
        val packageData = testPackage()
        var joinedPackage: WorkspaceJoinPackage? = null
        var joinCount = 0
        val service = pairingService(
            transport = transport,
            packageData = packageData,
            joiner = WorkspaceJoiner { received ->
                joinCount += 1
                joinedPackage = received
                WorkspaceJoinResult.success("Joined.")
            },
        )

        val created = service.createInvitation()

        assertTrue(created.success)
        val invitation = assertNotNull(created.invitation)
        val token = invitation.revealManualToken()
        assertEquals(4, token.split(' ').size)
        assertTrue(token.split(' ').all { it.length == 7 })
        assertTrue(invitation.revealQrPayload().startsWith(WorkspacePairingToken.QR_PREFIX))
        assertEquals(1, transport.objectCount { it.contains("workspace-pairing/1/") })
        assertFalse(transport.allBodiesText().contains(packageData.recoveryCode))
        assertFalse(transport.allBodiesText().contains(packageData.metadataJson))

        val joined = service.joinWithToken(token.lowercase().replace(' ', '-'))

        assertTrue(joined.success)
        assertEquals(packageData, joinedPackage)
        assertEquals(1, joinCount)
        assertEquals(1, transport.objectCount { it.contains("workspace-pairing/1/") })
        assertEquals(0, transport.deleteCount)
        assertTrue(transport.allBodiesText().contains("\"state\":\"claimed\""))
        assertFalse(transport.allBodiesText().contains(packageData.recoveryCode))

        val replay = service.joinWithToken(token)
        assertFalse(replay.success)
        assertEquals(1, joinCount)
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun pairingJoinThenFirstSyncPublishesLeaderNotesOnFollower() {
        val leaderDriver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val followerDriver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val leaderLocal = SqlDelightLocalDataRepository(
                SomedayDatabase(leaderDriver),
                LEADER_DEVICE_ID,
                clock = { PAIRING_NOW },
            )
            val followerLocal = SqlDelightLocalDataRepository(
                SomedayDatabase(followerDriver),
                FOLLOWER_DEVICE_ID,
                clock = { PAIRING_NOW },
            )
            val keyCrypto = SodiumWorkspaceCrypto()
            val leaderKeys = WorkspaceKeyRepository(
                leaderLocal,
                InMemorySecureWorkspaceKeyStore(),
                keyCrypto,
                clock = { PAIRING_NOW },
            )
            val followerKeys = WorkspaceKeyRepository(
                followerLocal,
                InMemorySecureWorkspaceKeyStore(),
                keyCrypto,
                clock = { PAIRING_NOW },
            )
            leaderKeys.createFirstRunWorkspace("Leader", "jvm")
            followerKeys.createFirstRunWorkspace("Follower", "jvm")
            val followerProtocol = SqlDelightSyncProtocolStoreV2(followerLocal.database)
            val followerAuthorityMutations = WorkspaceAuthorityMutationCoordinator()
            val pairing = WebDavWorkspacePairingService(
                settingsProvider = ::webDavSettings,
                credentialStore = TestCredentialStore("saved-secret"),
                transport = MemoryPairingWebDavTransport(),
                workspaceJoinPackageProvider = leaderKeys.workspaceJoinPackageProvider(),
                workspaceJoiner = followerKeys.workspaceJoiner(
                    deviceName = "Follower",
                    platform = "jvm",
                    localV2KeyBoundStatePresent = followerProtocol::hasKeyBoundLocalV2State,
                ),
                localV2KeyBoundStatePresent = followerProtocol::hasKeyBoundLocalV2State,
                authorityMutationCoordinator = followerAuthorityMutations,
                clock = { PAIRING_NOW },
            )
            val invitation = assertNotNull(pairing.createInvitation().invitation)

            val joined = pairing.joinWithToken(invitation.revealQrPayload())

            assertTrue(joined.success, joined.message)
            val leaderKey = assertNotNull(leaderKeys.unlockedKeyOrNull())
            val followerKey = assertNotNull(followerKeys.unlockedKeyOrNull())
            assertEquals(leaderKey.fingerprint, followerKey.fingerprint)

            val notebook = leaderLocal.createNotebook("Leader notebook")
            leaderLocal.createNote(
                notebookId = notebook.id,
                title = "Visible after pairing",
                markdownBody = "Encrypted pairing followed by checkpoint bootstrap.",
            )
            val remote = InMemoryWorkspaceSyncRemoteV2(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
            )
            val leaderRuntime = SyncV2RuntimeService(
                mode = SyncMode.WebDav,
                localRepository = leaderLocal,
                settingsRepository = PairingTestSettingsRepository(webDavSettings()),
                workspaceKeyProvider = { leaderKeys.unlockedKeyOrNull() },
                writerDeviceIdProvider = { LEADER_DEVICE_ID },
                transportFactory = SyncRemoteTransportFactoryV2 { remote },
                activationEnabled = true,
                authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator(),
                clock = { PAIRING_NOW },
            )
            val followerRuntime = SyncV2RuntimeService(
                mode = SyncMode.WebDav,
                localRepository = followerLocal,
                settingsRepository = PairingTestSettingsRepository(webDavSettings()),
                workspaceKeyProvider = { followerKeys.unlockedKeyOrNull() },
                writerDeviceIdProvider = { FOLLOWER_DEVICE_ID },
                transportFactory = SyncRemoteTransportFactoryV2 { remote },
                activationEnabled = true,
                authorityMutationCoordinator = followerAuthorityMutations,
                clock = { PAIRING_NOW },
            )

            assertTrue(leaderRuntime.run().success)
            val firstFollowerSync = followerRuntime.run()

            assertTrue(firstFollowerSync.success, firstFollowerSync.message)
            assertTrue(firstFollowerSync.pulledObjects > 0)
            val followerNotes = SystemV2NotesRepository(
                followerLocal,
                { followerKeys.unlockedKeyOrNull() },
                { FOLLOWER_DEVICE_ID },
                { remote.remoteProfile },
                clock = { PAIRING_NOW },
            )
            val followerNotebook = followerNotes.listNotebooks().single()
            assertEquals("Leader notebook", followerNotebook.title)
            assertEquals(
                "Visible after pairing",
                followerNotes.listNotes(followerNotebook.id).single().title,
            )
        } finally {
            leaderDriver.close()
            followerDriver.close()
        }
    }

    @Test
    fun validUnknownTokenFailsWithoutCallingJoiner() {
        var joinCalled = false
        val service = pairingService(
            transport = MemoryPairingWebDavTransport(),
            joiner = WorkspaceJoiner {
                joinCalled = true
                WorkspaceJoinResult.success("Unexpected join.")
            },
        )
        val unknown = WorkspacePairingToken.generate(SodiumWorkspaceCrypto()).formattedManualToken()

        val joined = service.joinWithToken(unknown)

        assertFalse(joined.success)
        assertFalse(joinCalled)
        assertTrue(joined.message.contains("not found", ignoreCase = true))
    }

    @Test
    fun expiredInvitationRemainsRemoteAndDoesNotCallJoiner() {
        val transport = MemoryPairingWebDavTransport()
        var now = Instant.fromEpochMilliseconds(1_000)
        var joinCalled = false
        val service = pairingService(
            transport = transport,
            joiner = WorkspaceJoiner {
                joinCalled = true
                WorkspaceJoinResult.success("Unexpected join.")
            },
            clock = { now },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        now += 11.minutes

        val joined = service.joinWithToken(invitation.revealManualToken())

        assertFalse(joined.success)
        assertTrue(joined.message.contains("expired", ignoreCase = true))
        assertFalse(joinCalled)
        assertEquals(1, transport.objectCount { it.contains("workspace-pairing/1/") })
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun cancellationPublishesAuthenticatedTombstoneWithoutDelete() {
        val transport = MemoryPairingWebDavTransport()
        var joinCalled = false
        val service = pairingService(
            transport = transport,
            joiner = WorkspaceJoiner {
                joinCalled = true
                WorkspaceJoinResult.success("Unexpected join.")
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)

        val cancelled = service.cancelInvitation(invitation)
        val joinAfterCancel = service.joinWithToken(invitation.revealManualToken())

        assertTrue(cancelled.success)
        assertFalse(joinAfterCancel.success)
        assertFalse(joinCalled)
        assertTrue(transport.allBodiesText().contains("\"state\":\"cancelled\""))
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun keyBoundLocalHistoryBlocksBeforeRemoteRead() {
        val transport = MemoryPairingWebDavTransport()
        val invitation = assertNotNull(pairingService(transport).createInvitation().invitation)
        transport.getCount = 0
        val blocked = pairingService(
            transport = transport,
            localV2KeyBoundStatePresent = { true },
        ).joinWithToken(invitation.revealManualToken())

        assertFalse(blocked.success)
        assertTrue(blocked.message.contains("local Sync V2 history"))
        assertEquals(0, transport.getCount)
    }

    @Test
    fun authorityBindingPreventsCrossAccountDecryption() {
        val transport = MemoryPairingWebDavTransport()
        val invitation = assertNotNull(pairingService(transport).createInvitation().invitation)
        val wrongAuthority = pairingService(
            transport = transport,
            settingsProvider = { webDavSettings(username = "bob") },
        )

        val joined = wrongAuthority.joinWithToken(invitation.revealManualToken())

        assertFalse(joined.success)
        assertTrue(joined.message.contains("could not be verified", ignoreCase = true))
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun missingEtagFailsClosedWithoutJoiningOrDeleting() {
        val transport = MemoryPairingWebDavTransport()
        var joinCalled = false
        val service = pairingService(
            transport = transport,
            joiner = WorkspaceJoiner {
                joinCalled = true
                WorkspaceJoinResult.success("Unexpected join.")
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        transport.omitEtagOnGet = true

        val joined = service.joinWithToken(invitation.revealManualToken())

        assertFalse(joined.success)
        assertFalse(joinCalled)
        assertEquals(0, transport.deleteCount)
    }
}

private fun pairingService(
    transport: MemoryPairingWebDavTransport,
    packageData: WorkspaceJoinPackage = testPackage(),
    joiner: WorkspaceJoiner = WorkspaceJoiner { WorkspaceJoinResult.success("Joined.") },
    localV2KeyBoundStatePresent: () -> Boolean = { false },
    settingsProvider: () -> ClientSettings = ::webDavSettings,
    clock: () -> Instant = { Instant.fromEpochMilliseconds(1_000) },
): WebDavWorkspacePairingService =
    WebDavWorkspacePairingService(
        settingsProvider = settingsProvider,
        credentialStore = TestCredentialStore("saved-secret"),
        transport = transport,
        workspaceJoinPackageProvider = WorkspaceJoinPackageProvider {
            WorkspaceJoinResult.success("Created.", packageData = packageData)
        },
        workspaceJoiner = joiner,
        localV2KeyBoundStatePresent = localV2KeyBoundStatePresent,
        authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator(),
        clock = clock,
    )

private fun testPackage(): WorkspaceJoinPackage =
    WorkspaceJoinPackage(
        metadataJson = """{"workspaceId":"workspace-a"}""",
        recoveryCode = "SOMEDAY-SECRET-RECOVERY",
        workspaceId = "workspace-a",
        keyFingerprint = "fingerprint-a",
    )

private fun webDavSettings(username: String = "alice"): ClientSettings =
    ClientSettings(
        syncConfiguration = SyncConfiguration(
            mode = SyncMode.WebDav,
            webDavEndpoint = "https://dav.example.com/dav",
            webDavUsername = username,
            webDavAppDirectory = "/someday/",
        ),
    )

private class TestCredentialStore(
    private var secret: String?,
) : WebDavCredentialStore {
    override fun load(): String? = secret

    override fun save(secret: String) {
        this.secret = secret
    }

    override fun clear() {
        secret = null
    }
}

private class PairingTestSettingsRepository(
    private var settings: ClientSettings,
) : ClientSettingsRepository {
    override fun load(): ClientSettings = settings

    override fun save(settings: ClientSettings): ClientSettings {
        this.settings = settings
        return settings
    }
}

private class MemoryPairingWebDavTransport : WebDavTransport {
    private val objects = mutableMapOf<String, StoredObject>()
    private val collections = mutableSetOf<String>()
    private var nextEtag = 1

    var deleteCount: Int = 0
        private set
    var getCount: Int = 0
    var omitEtagOnGet: Boolean = false

    override fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse =
        when (request.method) {
            "MKCOL" -> {
                val created = collections.add(request.path.asCollectionPath())
                WebDavResponse(status = if (created) 201 else 405)
            }
            "GET" -> {
                getCount += 1
                objects[request.path]?.let { stored ->
                    WebDavResponse(
                        status = 200,
                        headers = if (omitEtagOnGet) emptyMap() else mapOf("ETag" to stored.etag),
                        body = stored.body,
                    )
                } ?: WebDavResponse(status = 404)
            }
            "PUT" -> put(request)
            "PROPFIND" -> WebDavResponse(
                status = 207,
                body = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"></D:multistatus>""".encodeToByteArray(),
            )
            "DELETE" -> {
                deleteCount += 1
                WebDavResponse(status = 405)
            }
            else -> WebDavResponse(status = 405)
        }

    fun objectCount(predicate: (String) -> Boolean): Int = objects.keys.count(predicate)

    fun allBodiesText(): String =
        objects.values.joinToString(separator = "\n") { it.body.decodeToString() }

    private fun put(request: WebDavRequest): WebDavResponse {
        val current = objects[request.path]
        if (request.headers["If-None-Match"] == "*" && current != null) {
            return WebDavResponse(status = 412)
        }
        request.headers["If-Match"]?.let { expected ->
            if (current == null || current.etag != expected) {
                return WebDavResponse(status = 412)
            }
        }
        val etag = """"etag-${nextEtag++}""""
        objects[request.path] = StoredObject(
            etag = etag,
            body = request.body ?: ByteArray(0),
        )
        return WebDavResponse(
            status = if (current == null) 201 else 200,
            headers = mapOf("ETag" to etag),
        )
    }
}

private data class StoredObject(
    val etag: String,
    val body: ByteArray,
)

private fun String.asCollectionPath(): String =
    trimStart('/').let { path -> if (path.endsWith("/")) path else "$path/" }

private val PAIRING_NOW = Instant.parse("2026-07-27T10:00:00Z")
private const val LEADER_DEVICE_ID = "10000000-0000-4000-8000-000000000001"
private const val FOLLOWER_DEVICE_ID = "20000000-0000-4000-8000-000000000002"
