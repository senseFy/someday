@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetAddressingStrategy
import saien.someday.data.media.MediaAssetDecodeValidator
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetInspection
import saien.someday.data.media.MediaAssetInspector
import saien.someday.data.media.MediaAssetIntegrityException
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SystemV3MediaCoordinatorTest {
    @Test
    fun pendingAssetPublishesOneObjectAndRecordsScopedProof() = withStore { fixture ->
        val imported = fixture.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        val coordinator = fixture.coordinator(transport)

        assertEquals(SystemV3MediaPublicationSummary(1, 1, 0), coordinator.publishPending())
        val published = fixture.store.getAsset(imported.metadata.id)!!
        assertEquals(CREDENTIALS.authorityBindingId, published.publishedAuthorityBindingId)
        assertEquals(WORKSPACE, published.publishedWorkspaceId)
        assertTrue(published.publishedObjectDigest?.startsWith("sha256:") == true)
        assertTrue(fixture.store.listAssetsPendingPublication(CREDENTIALS.authorityBindingId, WORKSPACE).isEmpty())
        assertEquals(SystemV3MediaPublicationSummary(0, 0, 0), coordinator.publishPending())
        assertEquals(1, transport.puts)
    }

    @Test
    fun exactGateRepairsRemoteLossFromVerifiedLocalOriginal() = withStore { fixture ->
        val imported = fixture.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        val coordinator = fixture.coordinator(transport)
        coordinator.publishPending()
        transport.dropRemoteAsset(WORKSPACE, imported.metadata.id.value)

        coordinator.ensurePublished(setOf(imported.metadata.id))

        assertEquals(2, transport.puts)
        assertEquals(0, transport.gets)
    }

    @Test
    fun remoteOnlyReferenceDownloadsAuthenticatesAndCachesBytesAndProofOnce() = withStore { source ->
        val imported = source.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        source.coordinator(transport).publishPending()

        withStore { target ->
            val coordinator = target.coordinator(transport)
            coordinator.ensurePublished(setOf(imported.metadata.id))

            assertEquals(1, transport.gets)
            val cached = target.store.getAsset(imported.metadata.id)!!
            assertEquals(WORKSPACE, cached.publishedWorkspaceId)
            assertContentEquals(
                IMAGE_BYTES,
                target.store.openSource(imported.metadata.id).buffer().use { it.readByteArray() },
            )

            coordinator.ensurePublished(setOf(imported.metadata.id))
            assertEquals(1, transport.gets)
        }
    }

    @Test
    fun workspaceReplacementWaitsForBlockedMaterializationBeforeCommitting() = withStore { source ->
        val imported = source.import(IMAGE_BYTES)
        val remote = InMemoryMediaTransportV3()
        source.coordinator(remote).publishPending()

        withStore { target ->
            val fetchEntered = CountDownLatch(1)
            val releaseFetch = CountDownLatch(1)
            val blockingTransport = object : SelfHostedMediaTransportV3 by remote {
                override fun getMediaObject(
                    endpoint: String,
                    accessToken: String,
                    workspaceId: String,
                    mediaId: String,
                ): SelfHostedMediaRemoteObjectV3 {
                    fetchEntered.countDown()
                    assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
                    return remote.getMediaObject(endpoint, accessToken, workspaceId, mediaId)
                }
            }
            val workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator()
            var activeWorkspaceId = WORKSPACE
            val coordinator = SystemV3MediaCoordinator(
                localStore = AuthorityCoordinatedMediaAssetStore(target.store, workspaceLifecycleCoordinator),
                transport = blockingTransport,
                sessionStore = FixedSessionStore(),
                workspaceKeyProvider = { WORKSPACE_KEY },
                workspaceIdProvider = { activeWorkspaceId },
                workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
            )
            val replacementAttempting = CountDownLatch(1)
            val replacementEntered = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val materializing = executor.submit(java.util.concurrent.Callable {
                    runCatching { coordinator.materialize(imported.metadata.id) }
                })
                assertTrue(fetchEntered.await(5, TimeUnit.SECONDS))

                val workspaceReplacement = executor.submit {
                    replacementAttempting.countDown()
                    workspaceLifecycleCoordinator.exclusive {
                        replacementEntered.countDown()
                        workspaceLifecycleCoordinator.productAccess {
                            assertEquals(WORKSPACE, activeWorkspaceId)
                            assertEquals(
                                listOf(imported.metadata.id),
                                target.store.listAssets().map { it.metadata.id },
                            )
                            activeWorkspaceId = OTHER_WORKSPACE
                        }
                    }
                }
                assertTrue(replacementAttempting.await(5, TimeUnit.SECONDS))
                assertEquals(1L, replacementEntered.count)
                assertEquals(WORKSPACE, activeWorkspaceId)
                assertTrue(target.store.listAssets().isEmpty())

                releaseFetch.countDown()
                val result = materializing.get(5, TimeUnit.SECONDS)
                assertTrue(result.getOrThrow().downloaded)
                workspaceReplacement.get(5, TimeUnit.SECONDS)
                assertEquals(0L, replacementEntered.count)
                assertEquals(OTHER_WORKSPACE, activeWorkspaceId)
                assertEquals(listOf(imported.metadata.id), target.store.listAssets().map { it.metadata.id })
            } finally {
                releaseFetch.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun publicationProofDoesNotCrossWorkspaceBoundary() = withStore { fixture ->
        val imported = fixture.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        fixture.coordinator(transport, workspaceId = WORKSPACE).publishPending()
        val other = "workspace-${"f".repeat(32)}"

        assertEquals(
            listOf(imported.metadata.id),
            fixture.store.listAssetsPendingPublication(CREDENTIALS.authorityBindingId, other)
                .map { it.metadata.id },
        )
        fixture.coordinator(transport, workspaceId = other).ensurePublished(setOf(imported.metadata.id))
        assertEquals(other, fixture.store.getAsset(imported.metadata.id)?.publishedWorkspaceId)
        assertEquals(2, transport.puts)
    }

    @Test
    fun localCorruptionFailsBeforeRemoteWrite() = withStore { fixture ->
        val imported = fixture.import(IMAGE_BYTES)
        fixture.overwriteObject(imported.contentSha256, ByteArray(IMAGE_BYTES.size))
        val transport = InMemoryMediaTransportV3()

        assertFailsWith<MediaAssetIntegrityException> { fixture.coordinator(transport).publishPending() }

        assertEquals(0, transport.puts)
        assertNull(fixture.store.getAsset(imported.metadata.id)?.publishedAuthorityBindingId)
    }

    @Test
    fun activeGuardRejectsWorkspaceMismatchBeforeMediaRequest() = withStore { fixture ->
        fixture.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        val coordinator = fixture.coordinator(
            transport,
            requirement = ActiveWorkspaceSessionRequirement(
                CREDENTIALS.authorityBindingId,
                CREDENTIALS.deviceId,
                "workspace-${"f".repeat(32)}",
            ),
        )

        assertFailsWith<IllegalArgumentException> { coordinator.publishPending() }
        assertTrue(transport.writeEvents.isEmpty())
    }

    @Test
    fun materializationRepairsCorruptLocalBytesFromAuthenticatedObject() = withStore { source ->
        val imported = source.import(IMAGE_BYTES)
        val transport = InMemoryMediaTransportV3()
        source.coordinator(transport).publishPending()

        withStore { target ->
            val coordinator = target.coordinator(transport)
            assertTrue(coordinator.materialize(imported.metadata.id).downloaded)
            target.overwriteObject(imported.contentSha256, ByteArray(IMAGE_BYTES.size))
            assertTrue(coordinator.materialize(imported.metadata.id).downloaded)
            assertContentEquals(
                IMAGE_BYTES,
                target.store.openSource(imported.metadata.id).buffer().use { it.readByteArray() },
            )
            assertFalse(coordinator.materialize(imported.metadata.id).downloaded)
        }
    }

    private fun withStore(block: (StoreFixture) -> Unit) {
        val directory = Files.createTempDirectory("someday-system-v3-media-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${directory.resolve("someday.db").toAbsolutePath()}")
        val root = directory.resolve("private").toString().toPath()
        val store = LocalMediaAssetStore(
            database = SomedayDatabase(driver),
            appPrivateRoot = root,
            addressingStrategy = MediaAssetAddressingStrategy(MediaAssetId::fromCanonicalValue),
            inspector = INSPECTOR,
            decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(32, 32) },
        )
        try {
            block(StoreFixture(store, root))
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private data class StoreFixture(val store: LocalMediaAssetStore, val root: Path) {
        fun import(bytes: ByteArray) = store.importAsset(
            Buffer().write(bytes),
            MediaAssetImportRequest(mediaType = "image/png", originalFileName = "image.png"),
        ).asset

        fun overwriteObject(contentSha256: String, bytes: ByteArray) {
            val path = root.resolve("media-v1/objects/${contentSha256.take(2)}/${contentSha256.substring(2, 4)}/$contentSha256.blob")
            FileSystem.SYSTEM.write(path) { write(bytes) }
        }

        fun coordinator(
            transport: SelfHostedMediaTransportV3,
            workspaceId: String = WORKSPACE,
            requirement: ActiveWorkspaceSessionRequirement? = null,
            workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator =
                WorkspaceLifecycleCoordinator(),
        ) = SystemV3MediaCoordinator(
            AuthorityCoordinatedMediaAssetStore(store, workspaceLifecycleCoordinator),
            transport,
            FixedSessionStore(),
            workspaceKeyProvider = { WORKSPACE_KEY },
            workspaceIdProvider = { workspaceId },
            activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard { requirement },
            workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        )
    }

    private class FixedSessionStore : SelfHostedSessionCredentialStore {
        override fun load() = CREDENTIALS
        override fun save(credentials: SelfHostedSessionCredentials) = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val WORKSPACE = "workspace-0123456789abcdef0123456789abcdef"
        const val OTHER_WORKSPACE = "workspace-fedcba9876543210fedcba9876543210"
        val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { it.toByte() })
        val CREDENTIALS = SelfHostedSessionCredentials(
            "https://sync.example", "user-a", "user@example.com", "device-1", "Test device", "jvm",
            "access", "refresh",
        )
        val INSPECTOR = MediaAssetInspector { _, _, declared, _ ->
            MediaAssetInspection(checkNotNull(declared), 32, 32)
        }
        val IMAGE_BYTES = ByteArray(2_048) { (it * 17).toByte() }
    }
}
