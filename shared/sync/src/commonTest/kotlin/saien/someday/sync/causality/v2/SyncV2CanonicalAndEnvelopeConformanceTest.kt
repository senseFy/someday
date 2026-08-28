@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class SyncV2CanonicalAndEnvelopeConformanceTest {
    private val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { it.toByte() })
    private val keys = SyncEpochKeyDerivationV2().derive(workspaceKey, EPOCH)
    private val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
    private val validator = WorkspaceEntityValidatorV2(materializer)
    private val factory = WorkspaceEntityVersionFactoryV2(EPOCH, materializer, CanonicalIdsV2())
    private val wire = WorkspaceEntityWireCodecV2(materializer, validator)
    private val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
    private val control = WorkspaceSyncControlCodecV2(cipher)

    @Test
    fun canonicalGoldenCorpusIsFrozenAcrossSupportedTargets() {
        val note = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(
                NOTEBOOK_ID,
                "标题\u0000🙂",
                "line 1\r\nline 2",
                Instant.fromEpochSeconds(-1, 999_999_999),
                null,
                NoteLocationV2(-0.0, 180.0, "東京", 0.0, -12.5, AT),
            ),
            ACTOR,
            AT,
        )
        val notebook = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("笔记本", Long.MIN_VALUE, Instant.fromEpochSeconds(1, 1)),
            ACTOR,
            AT,
        )
        val preferences = factory.createGenesis(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(WorkspaceThemeV2.DARK, true, false, NOTEBOOK_ID),
            ACTOR,
            AT,
        )
        val deleted = factory.createDeletion(note, Instant.fromEpochSeconds(7, 123), ACTOR, Instant.fromEpochSeconds(8, 456))
        val imported = factory.createSourceImport(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            note.contentPayload,
            null,
            WorkspaceVersionProvenanceV2(
                WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT,
                "local-source-enrichment",
                "source-epoch",
                WRITER,
                MUTATION,
                "source:note",
                "source-digest",
            ),
            AT,
            null,
        )
        val equivalentParent = factory.createContentChild(note, note.contentPayload!!, ACTOR, Instant.fromEpochSeconds(10))
        val equivalent = materializer.materializeAutomaticVersion(
            AutomaticWorkspaceVersionDraftV2(
                EPOCH,
                WorkspaceEntityTypeV2.NOTE,
                NOTE_ID,
                listOf(note.versionId, equivalentParent.versionId).sorted(),
                WorkspaceEntityVersionKindV2.CONTENT,
                note.contentPayload,
                null,
                equivalentParent.authoredAt,
                equivalentParent.generation + 1,
                EQUIVALENT_MERGE_ALGORITHM_V2,
            ),
        )
        val goldenNoteContent = note.contentPayload as NoteContentV2
        val titleBranch = factory.createContentChild(
            note,
            goldenNoteContent.copy(title = "title branch"),
            ACTOR,
            Instant.fromEpochSeconds(11),
        )
        val bodyBranch = factory.createContentChild(
            note,
            goldenNoteContent.copy(markdownBody = "body branch"),
            ACTOR,
            Instant.fromEpochSeconds(12),
        )
        val fieldMerge = materializer.materializeAutomaticVersion(
            AutomaticWorkspaceVersionDraftV2(
                EPOCH,
                WorkspaceEntityTypeV2.NOTE,
                NOTE_ID,
                listOf(titleBranch.versionId, bodyBranch.versionId).sorted(),
                WorkspaceEntityVersionKindV2.CONTENT,
                goldenNoteContent.copy(title = "title branch", markdownBody = "body branch"),
                null,
                bodyBranch.authoredAt,
                bodyBranch.generation + 1,
                FIELD_MERGE_ALGORITHM_V2,
            ),
        )
        val deletionBranch = factory.createDeletion(
            equivalentParent,
            Instant.fromEpochSeconds(6, 321),
            ACTOR,
            Instant.fromEpochSeconds(13),
        )
        val deletionMerge = materializer.materializeAutomaticVersion(
            AutomaticWorkspaceVersionDraftV2(
                EPOCH,
                WorkspaceEntityTypeV2.NOTE,
                NOTE_ID,
                listOf(deleted.versionId, deletionBranch.versionId).sorted(),
                WorkspaceEntityVersionKindV2.DELETION,
                null,
                WorkspaceDeletionV2(Instant.fromEpochSeconds(6, 321)),
                deletionBranch.authoredAt,
                deletionBranch.generation + 1,
                DELETION_MERGE_ALGORITHM_V2,
            ),
        )
        val otherTitleBranch = factory.createContentChild(
            note,
            goldenNoteContent.copy(title = "other title branch"),
            ACTOR,
            Instant.fromEpochSeconds(14),
        )
        val manualResolution = factory.createManualResolution(
            listOf(titleBranch, otherTitleBranch),
            titleBranch.contentPayload,
            null,
            ACTOR,
            Instant.fromEpochSeconds(15),
        )
        val checkpointRoot = factory.createCheckpointRoot(
            WorkspaceEntityTypeV2.NOTE,
            CHECKPOINT_NOTE_ID,
            goldenNoteContent,
            null,
            WorkspaceVersionProvenanceV2(
                WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT,
                SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                PREVIOUS_EPOCH,
                WRITER,
                MUTATION,
                note.versionId,
                note.objectDigest,
            ),
            AT,
        )
        val entityBytes = listOf(
            note,
            notebook,
            preferences,
            deleted,
            imported,
            checkpointRoot,
            equivalent,
            fieldMerge,
            deletionMerge,
            manualResolution,
        ).map(wire::encode)

        val fixedOuter = fixedEntityOuter(note)
        val chunk = WorkspaceCheckpointChunkV2(
            syncEpochId = EPOCH,
            checkpointId = CHECKPOINT,
            chunkIndex = 0,
            chunkId = CHUNK,
            objects = listOf(fixedOuter),
        )
        val chunkOuter = control.encodeCheckpointChunk(chunk, WRITER)
        val chunkPlaintext = decodedPlaintext(chunkOuter)
        val chunkRef = WorkspaceCheckpointChunkRefV2(
            0,
            CHUNK,
            chunkOuter.objectDigest,
            1,
            chunkPlaintext.size,
        )
        val manifest = WorkspaceCheckpointManifestV2(
            syncEpochId = EPOCH,
            checkpointId = CHECKPOINT,
            createdAt = AT,
            chunks = listOf(chunkRef),
            totalObjectCount = 1,
        )
        val manifestOuter = control.encodeCheckpointManifest(manifest, WRITER)
        val descriptor = SyncEpochDescriptorV2(
            syncEpochId = EPOCH,
            remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            checkpointId = CHECKPOINT,
            checkpointDigest = manifestOuter.objectDigest,
            previousEpochId = PREVIOUS_EPOCH,
            previousEpochPointerDigest = CONTROL_DIGEST,
            createdByDeviceId = WRITER,
            createdAt = AT,
            previousEpochFrontiers = listOf(SyncStreamFrontierV2(WRITER, "1:segment", CONTROL_DIGEST)),
        )
        val pointerOuter = control.encodeEpochPointer(
            WorkspaceSyncEpochPointerV2(previousPointerDigest = CONTROL_DIGEST, descriptor = descriptor),
            WRITER,
        )
        val controlBytes = listOf(pointerOuter, manifestOuter, chunkOuter)
            .map(::decodedPlaintext)
        val outerAssociatedData = cipher.associatedDataBytes(fixedOuter)
        val corpus = (entityBytes + controlBytes + listOf(outerAssociatedData))
            .joinToString(separator = "|") { it.toByteString().hex() }
        assertEquals(EXPECTED_CANONICAL_CORPUS_SHA256, corpus.encodeUtf8().sha256().hex())
        assertEquals(EXPECTED_NOTE_PAYLOAD_DIGEST, note.payloadDigest)
        assertEquals(EXPECTED_NOTE_OBJECT_DIGEST, note.objectDigest)
        assertEquals(EXPECTED_EQUIVALENT_VERSION_ID, equivalent.versionId)
        assertEquals(EXPECTED_FIELD_MERGE_VERSION_ID, fieldMerge.versionId)
        assertEquals(EXPECTED_DELETION_MERGE_VERSION_ID, deletionMerge.versionId)
        assertEquals(EXPECTED_CONFLICT_ID, materializer.conflictId(EPOCH, note.key, listOf(note.versionId, equivalentParent.versionId).sorted()))
    }

    @Test
    fun schemaActorKindAndAlgorithmMatrixFailsClosed() {
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "title", "body", AT, null, null),
            ACTOR,
            AT,
        )
        val sibling = factory.createContentChild(root, root.contentPayload!!, ACTOR, Instant.fromEpochSeconds(2))
        val validAutomatic = materializer.materializeAutomaticVersion(
            AutomaticWorkspaceVersionDraftV2(
                EPOCH,
                root.entityType,
                root.entityId,
                listOf(root.versionId, sibling.versionId).sorted(),
                WorkspaceEntityVersionKindV2.CONTENT,
                root.contentPayload,
                null,
                sibling.authoredAt,
                sibling.generation + 1,
                EQUIVALENT_MERGE_ALGORITHM_V2,
            ),
        )
        assertTrue(validator.validateEnvelope(root).isEmpty())
        assertTrue(validator.validateEnvelope(validAutomatic).isEmpty())

        val incompatible = listOf(
            root.copy(contractId = "other-contract"),
            root.copy(schemaSetVersion = "other-schema"),
            root.copy(envelopeSchemaVersion = 2),
            root.copy(entitySchemaVersion = 2),
            root.copy(payloadDigest = "pd9:sha256:${"0".repeat(64)}"),
            root.copy(mergeAlgorithmVersion = "future-merge-v9"),
            root.copy(authorActorId = SYSTEM_AUTO_MERGE_ACTOR_V2),
            root.copy(entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES, entityId = "other-preferences"),
            validAutomatic.copy(kind = WorkspaceEntityVersionKindV2.DELETION, contentPayload = null, deletionPayload = WorkspaceDeletionV2(AT)),
            validAutomatic.copy(authorActorId = ACTOR),
        )
        incompatible.forEach { value ->
            assertTrue(WorkspaceEntityValidatorV2().validateEnvelope(value).isNotEmpty(), value.toString())
        }

        val encoded = DeterministicCborV2.decode(wire.encode(root)) as CborValueV2.Map
        val unknownType = replaceTextField(encoded, "entityType", "future_entity")
        assertIs<WorkspaceEntityWireDecodeResultV2.Rejected>(
            wire.decode(DeterministicCborV2.encode(unknownType)),
        )
    }

    @Test
    fun plaintextAndEnvelopeByteBoundsApplyBeforeCreationAndOnDecode() {
        val exact = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(
                NOTEBOOK_ID,
                "t".repeat(MAX_NOTE_TITLE_BYTES_V2),
                "b".repeat(MAX_NOTE_MARKDOWN_BYTES_V2),
                AT,
                "z".repeat(MAX_NOTE_TIME_ZONE_BYTES_V2),
                NoteLocationV2(null, null, "p".repeat(MAX_NOTE_LOCATION_PLACE_BYTES_V2), null, null, AT),
            ),
            ACTOR,
            AT,
        )
        assertTrue(wire.encode(exact).size <= MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2)
        assertFailsWith<IllegalArgumentException> {
            factory.createGenesis(
                WorkspaceEntityTypeV2.NOTE,
                NOTE_ID,
                NoteContentV2(NOTEBOOK_ID, "title", "b".repeat(MAX_NOTE_MARKDOWN_BYTES_V2 + 1), AT, null, null),
                ACTOR,
                AT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptEntity(exact, MUTATION, WRITER, ByteArray(MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2 + 1))
        }

        val outer = cipher.encryptEntity(exact, MUTATION, WRITER, wire.encode(exact))
        val oversizedCiphertext = outer.copy(
            ciphertextBase64 = Base64.encode(ByteArray(MAX_ENTITY_CIPHERTEXT_BYTES_V2 + 1)),
            ciphertextDigest = "ct2:sha256:${"0".repeat(64)}",
        )
        assertEquals(
            EncryptedWorkspaceObjectErrorCodeV2.CIPHERTEXT_TOO_LARGE,
            assertIs<EncryptedWorkspaceObjectDecodeResultV2.Rejected>(cipher.decrypt(oversizedCiphertext)).error.code,
        )

        fun oversizedControl(objectType: String, objectId: String, limit: Int): EncryptedWorkspaceObjectV2 {
            val canonical = DeterministicCborV2.encode(CborValueV2.ByteString(ByteArray(limit + 1)))
            return cipher.encryptControl(EPOCH, objectType, objectId, WRITER, canonical)
        }
        assertEquals(
            WorkspaceControlErrorCodeV2.BOUNDS_EXCEEDED,
            assertIs<WorkspaceControlDecodeResultV2.Rejected>(
                control.decodeEpochPointer(
                    oversizedControl(
                        SYNC_EPOCH_POINTER_OBJECT_TYPE_V2,
                        SYNC_EPOCH_POINTER_ID_SYSTEM_V2,
                        MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2,
                    ),
                ),
            ).error.code,
        )
    }

    @Test
    fun declaredCollectionSizeIsBoundedBeforeMaterialization() {
        val declaredValues = 200_000
        val oversizedArray = ByteArray(5 + declaredValues) { index ->
            when (index) {
                0 -> 0x9a.toByte()
                1 -> 0x00
                2 -> 0x03
                3 -> 0x0d
                4 -> 0x40
                else -> 0xf6.toByte()
            }
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            DeterministicCborV2.decode(oversizedArray)
        }

        assertTrue(failure.message.orEmpty().contains("too many values"))
    }

    @Test
    fun encryptedOuterRejectsMalformedFramingAndMetadataBeforeSemanticApply() {
        val version = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Notebook", 0, AT),
            ACTOR,
            AT,
        )
        val outer = cipher.encryptEntity(version, MUTATION, WRITER, wire.encode(version))
        assertIs<EncryptedWorkspaceObjectDecodeResultV2.Decoded>(cipher.decrypt(outer))
        val cases = listOf(
            outer.copy(contractId = "future-contract") to EncryptedWorkspaceObjectErrorCodeV2.INCOMPATIBLE_CONTRACT,
            outer.copy(objectType = "attachment_future") to EncryptedWorkspaceObjectErrorCodeV2.UNKNOWN_OBJECT_TYPE,
            outer.copy(nonceBase64 = "***") to EncryptedWorkspaceObjectErrorCodeV2.INVALID_BASE64,
            outer.copy(nonceBase64 = Base64.encode(ByteArray(23))) to EncryptedWorkspaceObjectErrorCodeV2.INVALID_NONCE_LENGTH,
            outer.copy(ciphertextDigest = "ct2:sha256:${"0".repeat(64)}") to EncryptedWorkspaceObjectErrorCodeV2.CIPHERTEXT_DIGEST_MISMATCH,
            outer.copy(writerDeviceId = OTHER_WRITER) to EncryptedWorkspaceObjectErrorCodeV2.AUTHENTICATION_FAILED,
        )
        cases.forEach { (value, expected) ->
            assertEquals(expected, assertIs<EncryptedWorkspaceObjectDecodeResultV2.Rejected>(cipher.decrypt(value)).error.code)
        }

        val encoded = cipher.encodeJson(outer)
        assertTrue(cipher.decodeJson(encoded.dropLast(1) + ",\"future\":1}").isFailure)
        assertTrue(cipher.decodeJson(encoded.replaceFirst("{", "{\"contractId\":\"$SYNC_V2_CONTRACT_ID\",")).isFailure)
        assertIs<WorkspaceEntityWireDecodeResultV2.Rejected>(
            wire.decode(
                wire.encode(version),
                WorkspaceVersionOuterMetadataV2(EPOCH, OTHER_VERSION, version.objectDigest),
            ),
        )
    }

    private fun fixedEntityOuter(version: WorkspaceEntityVersionV2) = EncryptedWorkspaceObjectV2(
        syncEpochId = EPOCH,
        objectType = WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2,
        objectId = version.versionId,
        objectDigest = version.objectDigest,
        mutationId = MUTATION,
        writerDeviceId = WRITER,
        nonceBase64 = Base64.encode(ByteArray(24) { it.toByte() }),
        ciphertextBase64 = Base64.encode(ByteArray(16) { (it + 1).toByte() }),
        ciphertextDigest = "ct2:sha256:${"0".repeat(64)}",
    )

    private fun decodedPlaintext(outer: EncryptedWorkspaceObjectV2): ByteArray =
        assertIs<EncryptedWorkspaceObjectDecodeResultV2.Decoded>(cipher.decrypt(outer)).plaintext

    private fun replaceTextField(root: CborValueV2.Map, name: String, value: String): CborValueV2.Map =
        CborValueV2.Map(root.entries.map { (key, current) ->
            if ((key as? CborValueV2.TextString)?.value == name) key to cborText(value) else key to current
        })

    private companion object {
        const val EPOCH = "11111111-1111-4111-8111-111111111111"
        const val NOTEBOOK_ID = "22222222-2222-4222-8222-222222222222"
        const val NOTE_ID = "33333333-3333-4333-8333-333333333333"
        const val CHECKPOINT_NOTE_ID = "33333333-3333-4333-8333-333333333334"
        const val WRITER = "44444444-4444-4444-8444-444444444444"
        const val OTHER_WRITER = "55555555-5555-4555-8555-555555555555"
        const val ACTOR = "device:$WRITER"
        const val MUTATION = "66666666-6666-4666-8666-666666666666"
        const val CHECKPOINT = "77777777-7777-4777-8777-777777777777"
        const val PREVIOUS_EPOCH = "77777777-7777-4777-8777-777777777778"
        const val CHUNK = "88888888-8888-4888-8888-888888888888"
        const val SEGMENT = "99999999-9999-4999-8999-999999999999"
        const val OTHER_VERSION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CONTROL_DIGEST = "cd2:hmac-sha256:abababababababababababababababababababababababababababababababab"
        val AT = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)

        // Frozen after the exact commonMain corpus below is reviewed. A
        // mismatch is a schema-set change, not a test update by convenience.
        const val EXPECTED_CANONICAL_CORPUS_SHA256 = "15e6a0e9879992b13c32c1ec0bfa64d55778e89a9ba27b86ddd4ecf3b166a903"
        const val EXPECTED_NOTE_PAYLOAD_DIGEST = "pd2:sha256:e1a1e4b09f1e8ef7f7400b40a90091a9b85b417d9c25e5b939a4c0dc8f88516c"
        const val EXPECTED_NOTE_OBJECT_DIGEST = "od2:hmac-sha256:53cc6e64df5c37ed5ce73477baec2214a0c4d4f15d32fe2d04257af0f371c631"
        const val EXPECTED_EQUIVALENT_VERSION_ID = "05193d7e-a621-4e86-815d-3f5e9b8f019d"
        const val EXPECTED_FIELD_MERGE_VERSION_ID = "9cc1360c-bb8d-44ee-bc17-21d2ab49bf25"
        const val EXPECTED_DELETION_MERGE_VERSION_ID = "3b4b31e0-246b-4cda-bd88-4a251435f6f2"
        const val EXPECTED_CONFLICT_ID = "cf2_gdhw7wse3jm6zwpgfjvmspghagmjzycjz3f3o6b3iqf27n32voja"
    }
}

private class CanonicalIdsV2 : CausalityIdGeneratorV2 {
    private var value = 1
    override fun newId(): String = "aaaaaaaa-0000-4000-8000-${(value++).toString().padStart(12, '0')}"
}
