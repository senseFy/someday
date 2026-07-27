@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceEntitySystemV2Test {
    private val epoch = "10000000-0000-4000-8000-000000000001"
    private val actor = "device:20000000-0000-4000-8000-000000000001"
    private val notebookId = "30000000-0000-4000-8000-000000000001"
    private val noteId = "40000000-0000-4000-8000-000000000001"
    private val keys = SyncEpochKeysV2(
        convergenceKey = ByteArray(32) { (it + 1).toByte() },
        objectDigestKey = ByteArray(32) { (it + 65).toByte() },
    )
    private val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
    private val validator = WorkspaceEntityValidatorV2(materializer)
    private val engine = WorkspaceEntityCausalityEngineV2(materializer, validator)

    @Test
    fun allTypedPayloadsRoundTripThroughOneCanonicalCodec() {
        val ids = IdsV2()
        val factory = factory(ids)
        val note = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(
                location = NoteLocationV2(
                    latitude = -0.0,
                    longitude = 121.4737,
                    placeText = "上海",
                    accuracyMeters = 3.5,
                    altitudeMeters = 12.0,
                    capturedAt = instant(4),
                ),
            ),
            actor,
            instant(10),
        )
        val notebook = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Journal", 7, instant(1)),
            actor,
            instant(11),
        )
        val preferences = factory.createGenesis(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(WorkspaceThemeV2.DARK, true, false, notebookId),
            actor,
            instant(12),
        )
        val codec = WorkspaceEntityWireCodecV2(materializer, validator)

        listOf(note, notebook, preferences).forEach { version ->
            val bytes = codec.encode(version)
            val decoded = assertIs<WorkspaceEntityWireDecodeResultV2.Decoded>(
                codec.decode(bytes, WorkspaceVersionOuterMetadataV2(epoch, version.versionId, version.objectDigest)),
            ).version
            assertEquals(version, decoded)
            assertContentEquals(bytes, codec.encode(decoded))
            assertTrue(version.payloadDigest.startsWith("pd2:sha256:"))
            assertTrue(version.objectDigest.startsWith("od2:hmac-sha256:"))
        }
        val normalized = assertIs<NoteContentV2>(note.contentPayload)
        assertEquals(0L, normalized.location?.latitude?.toBits())
    }

    @Test
    fun decoderRejectsUnknownMapFieldAndNonCanonicalInteger() {
        val factory = factory(IdsV2())
        val version = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Notebook", 1, instant(1)),
            actor,
            instant(2),
        )
        val codec = WorkspaceEntityWireCodecV2(materializer, validator)
        val root = DeterministicCborV2.decode(codec.encode(version)) as CborValueV2.Map
        val withUnknown = DeterministicCborV2.encode(
            CborValueV2.Map(root.entries + (cborText("future") to cborText("ignored-by-no-one"))),
        )
        assertIs<WorkspaceEntityWireDecodeResultV2.Rejected>(codec.decode(withUnknown))

        // Integer zero encoded using an unnecessary one-byte argument.
        val nonCanonical = byteArrayOf(0x18, 0x00)
        assertTrue(runCatching { DeterministicCborV2.decode(nonCanonical) }.isFailure)
    }

    @Test
    fun notebookRenameAndReorderMergeButTwoRenamesConflict() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Root", 1, instant(1)),
            actor,
            instant(1),
        )
        val rename = factory.createContentChild(root, NotebookContentV2("Renamed", 1, instant(1)), actor, instant(2))
        val reorder = factory.createContentChild(root, NotebookContentV2("Root", 99, instant(1)), actor, instant(3))
        val merged = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, rename, reorder)),
        ).plan
        assertIs<WorkspaceReconciliationOutcomeV2.Projected>(merged.outcome)
        assertEquals(1, merged.generatedVersions.size)
        assertEquals(
            NotebookContentV2("Renamed", 99, instant(1)),
            merged.generatedVersions.single().contentPayload,
        )

        val renameAgain = factory.createContentChild(root, NotebookContentV2("Other", 1, instant(1)), actor, instant(4))
        val conflict = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, rename, renameAgain)),
        ).plan
        val descriptor = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(conflict.outcome).descriptor
        assertEquals(setOf("title"), descriptor.conflictingFields)
        assertEquals(WorkspaceConflictReasonV2.FIELD_CONFLICT, descriptor.reason)
    }

    @Test
    fun notebookConcurrentDeletesConvergeBeforeDeleteEditConflicts() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Root", 1, instant(1)),
            actor,
            instant(1),
        )
        val firstDeletion = factory.createDeletion(root, instant(8), actor, instant(8))
        val secondDeletion = factory.createDeletion(root, instant(5), actor, instant(9))
        val concurrentRename = factory.createContentChild(
            root,
            NotebookContentV2("Offline rename", 1, instant(1)),
            actor,
            instant(10),
        )

        val deletesOnly = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, firstDeletion, secondDeletion)),
        ).plan
        assertEquals(1, deletesOnly.finalHeadVersionIds.size)
        assertEquals(DELETION_MERGE_ALGORITHM_V2, deletesOnly.generatedVersions.single().mergeAlgorithmVersion)
        assertEquals(instant(5), deletesOnly.generatedVersions.single().deletionPayload?.deletedAt)

        val mixed = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, firstDeletion, secondDeletion, concurrentRename)),
        ).plan
        val conflict = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(mixed.outcome).descriptor
        assertEquals(WorkspaceConflictReasonV2.CONCURRENT_DELETE_EDIT, conflict.reason)
        assertEquals(2, conflict.headVersionIds.size)
        assertTrue(mixed.generatedVersions.any { it.mergeAlgorithmVersion == DELETION_MERGE_ALGORITHM_V2 })
    }

    @Test
    fun noteBodyAndAtomicLocationMergeWhileTwoLocationsConflict() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val body = factory.createContentChild(root, notePayload(body = "body changed"), actor, instant(2))
        val locationValue = NoteLocationV2(31.2, 121.4, "home", 5.0, null, instant(3))
        val location = factory.createContentChild(root, notePayload(location = locationValue), actor, instant(3))
        val merged = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, body, location)),
        ).plan.generatedVersions.single().contentPayload as NoteContentV2
        assertEquals("body changed", merged.markdownBody)
        assertEquals(locationValue, merged.location)

        val otherLocation = factory.createContentChild(
            root,
            notePayload(location = NoteLocationV2(null, null, "elsewhere", null, null, instant(4))),
            actor,
            instant(4),
        )
        val conflict = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, location, otherLocation)),
        ).plan
        assertEquals(
            setOf("location"),
            assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(conflict.outcome).descriptor.conflictingFields,
        )

        val equalLocation = factory.createContentChild(root, notePayload(location = locationValue), actor, instant(5))
        val equivalent = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, location, equalLocation)),
        ).plan
        assertEquals(EQUIVALENT_MERGE_ALGORITHM_V2, equivalent.generatedVersions.single().mergeAlgorithmVersion)
        assertEquals(locationValue, (equivalent.generatedVersions.single().contentPayload as NoteContentV2).location)
    }

    @Test
    fun everyNoteFieldUsesTypedThreeWayConflictRules() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val cases = listOf<Pair<String, (NoteContentV2, Int) -> NoteContentV2>>(
            "notebookId" to { value, side -> value.copy(notebookId = "book-$side") },
            "title" to { value, side -> value.copy(title = "title-$side") },
            "markdownBody" to { value, side -> value.copy(markdownBody = "body-$side") },
            "noteCreatedAt" to { value, side -> value.copy(noteCreatedAt = instant(10L + side)) },
            "timeZoneId" to { value, side -> value.copy(timeZoneId = "Etc/GMT${if (side == 1) "+1" else "+2"}") },
            "location" to { value, side ->
                value.copy(location = NoteLocationV2(null, null, "place-$side", null, null, instant(20L + side)))
            },
        )
        cases.forEachIndexed { index, (field, change) ->
            val base = root.contentPayload as NoteContentV2
            val left = factory.createContentChild(root, change(base, 1), actor, instant(30L + index * 2))
            val right = factory.createContentChild(root, change(base, 2), actor, instant(31L + index * 2))
            val result = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
                engine.reconcile(epoch, root.key, listOf(root, left, right)),
            ).plan
            assertEquals(
                setOf(field),
                assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(result.outcome).descriptor.conflictingFields,
                field,
            )
        }
    }

    @Test
    fun preferencesDisjointChangesMergeAndSameFieldConflict() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(),
            actor,
            instant(1),
        )
        val theme = factory.createContentChild(
            root,
            WorkspacePreferencesV2(theme = WorkspaceThemeV2.DARK),
            actor,
            instant(2),
        )
        val preview = factory.createContentChild(
            root,
            WorkspacePreferencesV2(previewByDefault = true),
            actor,
            instant(3),
        )
        val result = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, theme, preview)),
        ).plan
        assertEquals(
            WorkspacePreferencesV2(theme = WorkspaceThemeV2.DARK, previewByDefault = true),
            result.generatedVersions.single().contentPayload,
        )

        val light = factory.createContentChild(
            root,
            WorkspacePreferencesV2(theme = WorkspaceThemeV2.LIGHT),
            actor,
            instant(4),
        )
        val conflict = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(
            assertIs<WorkspaceReconciliationResultV2.Reconciled>(
                engine.reconcile(epoch, root.key, listOf(root, theme, light)),
            ).plan.outcome,
        )
        assertEquals(setOf("theme"), conflict.descriptor.conflictingFields)
    }

    @Test
    fun deletionConvergesBeforeMixedDeleteEditConflict() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val deletionOne = factory.createDeletion(root, instant(8), actor, instant(8))
        val deletionTwo = factory.createDeletion(root, instant(5), actor, instant(9))
        val edit = factory.createContentChild(root, notePayload(body = "offline edit"), actor, instant(10))
        val plan = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, deletionOne, deletionTwo, edit)),
        ).plan
        assertEquals(DELETION_MERGE_ALGORITHM_V2, plan.generatedVersions.single().mergeAlgorithmVersion)
        assertEquals(instant(5), plan.generatedVersions.single().deletionPayload?.deletedAt)
        val conflict = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(plan.outcome).descriptor
        assertEquals(WorkspaceConflictReasonV2.CONCURRENT_DELETE_EDIT, conflict.reason)
        assertEquals(2, conflict.headVersionIds.size)
    }

    @Test
    fun restoreBranchesWithDeletionBaseConservativelyConflict() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val deletion = factory.createDeletion(root, instant(2), actor, instant(2))
        val restoreOne = factory.createContentChild(deletion, notePayload(body = "one"), actor, instant(3))
        val restoreTwo = factory.createContentChild(deletion, notePayload(body = "two"), actor, instant(4))
        val result = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, deletion, restoreOne, restoreTwo)),
        ).plan
        assertEquals(
            WorkspaceConflictReasonV2.NO_USABLE_MERGE_BASE,
            assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(result.outcome).descriptor.reason,
        )
    }

    @Test
    fun viewedBaseTokenCreatesHonestBranchAndMissingBasePreservesDraft() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val remote = factory.createContentChild(root, notePayload(title = "remote"), actor, instant(2))
        val token = WorkspaceCausalEditTokenV2(epoch, root.entityType, root.entityId, root.versionId, null)
        val localDraft = notePayload(body = "local")
        val saved = assertIs<TokenBasedVersionResultV2.Created>(
            factory.createFromToken(token, mapOf(root.versionId to root, remote.versionId to remote), localDraft, null, actor, instant(3)),
        )
        assertEquals(listOf(root.versionId), saved.version.parentVersionIds)
        assertNotEquals(remote.versionId, saved.version.parentVersionIds.single())

        val rejected = assertIs<TokenBasedVersionResultV2.Rejected>(
            factory.createFromToken(token, mapOf(remote.versionId to remote), localDraft, null, actor, instant(3)),
        )
        assertEquals(WorkspaceCausalityErrorCodeV2.STALE_EDIT_BASE_MISSING, rejected.error.code)
        assertEquals(localDraft, rejected.preservedDraft)

        val oldEpoch = assertIs<TokenBasedVersionResultV2.Rejected>(
            factory.createFromToken(
                token.copy(syncEpochId = "10000000-0000-4000-8000-000000000099"),
                mapOf(root.versionId to root, remote.versionId to remote),
                localDraft,
                null,
                actor,
                instant(3),
            ),
        )
        assertEquals(WorkspaceCausalityErrorCodeV2.EDIT_TOKEN_MISMATCH, oldEpoch.error.code)
        assertEquals(localDraft, oldEpoch.preservedDraft)
    }

    @Test
    fun conflictHeadEvolutionSupersedesOldRecordAndResolutionExpectationIsExact() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val left = factory.createContentChild(root, notePayload(title = "left"), actor, instant(2))
        val right = factory.createContentChild(root, notePayload(title = "right"), actor, instant(3))
        val first = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(
            assertIs<WorkspaceReconciliationResultV2.Reconciled>(
                engine.reconcile(epoch, root.key, listOf(root, left, right)),
            ).plan.outcome,
        ).descriptor
        val evolved = factory.createContentChild(left, notePayload(title = "left evolved"), actor, instant(4))
        val secondPlan = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, left, right, evolved), listOf(first)),
        ).plan
        val second = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(secondPlan.outcome).descriptor
        assertNotEquals(first.conflictId, second.conflictId)
        assertEquals(1, secondPlan.conflictStates.count { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE })
        assertEquals(
            second.conflictId,
            secondPlan.conflictStates.single { it.descriptor.conflictId == first.conflictId }.supersededByConflictId,
        )
        assertEquals(
            WorkspaceCausalityErrorCodeV2.STALE_CONFLICT,
            engine.validateResolutionExpectation(second, first.conflictId, first.headVersionIds)?.code,
        )
        assertEquals(null, engine.validateResolutionExpectation(second, second.conflictId, second.headVersionIds))

        val resolution = factory.createManualResolution(
            second.headVersionIds.map { id -> listOf(root, right, evolved).single { it.versionId == id } },
            evolved.contentPayload,
            null,
            actor,
            instant(5),
        )
        val resolved = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, left, right, evolved, resolution), listOf(first, second)),
        ).plan
        assertEquals(listOf(resolution.versionId), resolved.finalHeadVersionIds)
        assertTrue(second.headVersionIds.all { it in resolution.parentVersionIds })
        assertEquals(0, resolved.conflictStates.count { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE })
    }

    @Test
    fun deterministicAutomaticIdentityIsRecomputedInsteadOfTrusted() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Root", 1, instant(1)),
            actor,
            instant(1),
        )
        val rename = factory.createContentChild(
            root,
            NotebookContentV2("Renamed", 1, instant(1)),
            actor,
            instant(2),
        )
        val reorder = factory.createContentChild(
            root,
            NotebookContentV2("Root", 2, instant(1)),
            actor,
            instant(3),
        )
        val generated = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root, rename, reorder)),
        ).plan.generatedVersions.single()
        assertTrue(validator.validateEnvelope(generated).isEmpty())

        // Re-signing an otherwise valid automatic envelope under another UUID
        // must not turn that UUID into an accepted deterministic identity.
        val wrongId = generated.copy(versionId = factory.newMutationId(), objectDigest = "pending")
        val resigned = wrongId.copy(objectDigest = materializer.objectDigest(wrongId))
        assertTrue(
            validator.validateEnvelope(resigned).any {
                it.code == WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION
            },
        )

        // A writer owns the convergence key, so a matching deterministic id
        // alone is not proof that the advertised algorithm was executed. The
        // graph validator must recompute the field merge from its parents.
        val invented = materializer.materializeAutomaticVersion(
            AutomaticWorkspaceVersionDraftV2(
                syncEpochId = epoch,
                entityType = WorkspaceEntityTypeV2.NOTEBOOK,
                entityId = notebookId,
                parentVersionIds = listOf(rename.versionId, reorder.versionId).sorted(),
                kind = WorkspaceEntityVersionKindV2.CONTENT,
                contentPayload = NotebookContentV2("Invented", 999, instant(1)),
                deletionPayload = null,
                authoredAt = maxOf(rename.authoredAt, reorder.authoredAt),
                generation = maxOf(rename.generation, reorder.generation) + 1,
                mergeAlgorithmVersion = FIELD_MERGE_ALGORITHM_V2,
            ),
        )
        assertTrue(validator.validateEnvelope(invented).isEmpty())
        val semanticForgery = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            engine.reconcile(epoch, root.key, listOf(root, rename, reorder, invented)),
        )
        assertTrue(semanticForgery.errors.any {
            it.code == WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION
        })
    }

    @Test
    fun locationBoundsTextBoundsAndActorShapesFailClosed() {
        val factory = factory(IdsV2())
        val valid = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val invalidLocations = listOf(
            NoteLocationV2(91.0, 0.0, null, null, null, instant(1)),
            NoteLocationV2(0.0, 181.0, null, null, null, instant(1)),
            NoteLocationV2(0.0, null, "partial", null, null, instant(1)),
            NoteLocationV2(null, null, "place", -1.0, null, instant(1)),
            NoteLocationV2(Double.NaN, 0.0, null, null, null, instant(1)),
            NoteLocationV2(Double.POSITIVE_INFINITY, 0.0, null, null, null, instant(1)),
            NoteLocationV2(0.0, Double.NEGATIVE_INFINITY, null, null, null, instant(1)),
            NoteLocationV2(null, null, "place", null, 1.0, instant(1)),
            NoteLocationV2(null, null, " ", null, null, instant(1)),
        )
        invalidLocations.forEach { location ->
            val invalid = valid.copy(contentPayload = notePayload(location = location))
            assertTrue(
                WorkspaceEntityValidatorV2().validateEnvelope(invalid).any {
                    it.code == WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD
                },
                location.toString(),
            )
        }
        val oversized = valid.copy(
            contentPayload = notePayload(title = "界".repeat(MAX_NOTE_TITLE_BYTES_V2 / 3 + 1)),
        )
        assertTrue(WorkspaceEntityValidatorV2().validateEnvelope(oversized).any {
            it.code == WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD
        })
        val wrongActor = valid.copy(authorActorId = SYSTEM_AUTO_MERGE_ACTOR_V2)
        assertTrue(WorkspaceEntityValidatorV2().validateEnvelope(wrongActor).any {
            it.code == WorkspaceCausalityErrorCodeV2.INVALID_AUTHOR_ACTOR
        })
        val preferenceDeletion = valid.copy(
            entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            kind = WorkspaceEntityVersionKindV2.DELETION,
            contentPayload = null,
            deletionPayload = WorkspaceDeletionV2(instant(2)),
        )
        assertTrue(WorkspaceEntityValidatorV2().validateEnvelope(preferenceDeletion).any {
            it.code == WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD
        })

        val placeOnly = factory.createContentChild(
            valid,
            notePayload(location = NoteLocationV2(null, null, "place", null, null, instant(2))),
            actor,
            instant(2),
        )
        val coordinateOnly = factory.createContentChild(
            valid,
            notePayload(location = NoteLocationV2(1.25, 2.5, null, 0.0, -4.0, instant(3))),
            actor,
            instant(3),
        )
        assertTrue(validator.validateEnvelope(placeOnly).isEmpty())
        assertTrue(validator.validateEnvelope(coordinateOnly).isEmpty())
    }

    @Test
    fun ancestryNotMetadataSelectsProjectionAndConcurrentHigherGenerationConflicts() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(title = "root"),
            actor,
            instant(999),
        )
        val descendant = factory.createContentChild(
            root,
            notePayload(title = "descendant"),
            "device:ffffffff-ffff-4fff-8fff-ffffffffffff",
            instant(1),
        )
        val projected = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(descendant, root)),
        ).plan
        assertEquals(descendant.versionId, assertIs<WorkspaceReconciliationOutcomeV2.Projected>(projected.outcome).headVersionId)

        val longHistory = mutableListOf(
            factory.createContentChild(root, notePayload(title = "long"), actor, instant(2)),
        )
        repeat(5) { index ->
            val parent = longHistory.last()
            longHistory += factory.createContentChild(
                parent,
                (parent.contentPayload as NoteContentV2).copy(markdownBody = "long-$index"),
                actor,
                instant(3L + index),
            )
        }
        val longBranch = longHistory.last()
        val shortBranch = factory.createContentChild(root, notePayload(title = "short"), actor, instant(500))
        val concurrent = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + longHistory + shortBranch),
        ).plan
        assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(concurrent.outcome)
        assertEquals(setOf(longBranch.versionId, shortBranch.versionId), concurrent.finalHeadVersionIds.toSet())
    }

    @Test
    fun pureEngineIsOrderIndependentAndPartialFrontierJoinsCascade() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val branches = (2L..4L).map { second ->
            factory.createContentChild(root, notePayload(), actor, instant(second))
        }
        val firstView = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + branches.take(2)),
        ).plan
        val widerView = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + branches),
        ).plan
        val exchanged = (listOf(root) + branches + firstView.generatedVersions + widerView.generatedVersions)
            .distinctBy { it.versionId }
        val forward = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, exchanged),
        ).plan
        val reverse = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, exchanged.reversed()),
        ).plan
        assertEquals(forward.finalHeadVersionIds, reverse.finalHeadVersionIds)
        assertEquals(forward.generatedVersions.map { it.versionId }, reverse.generatedVersions.map { it.versionId })
        assertEquals(1, forward.finalHeadVersionIds.size)
        val finalGraph = exchanged + forward.generatedVersions
        val final = finalGraph.single { it.versionId == forward.finalHeadVersionIds.single() }
        assertTrue(final.parentVersionIds.contains(firstView.finalHeadVersionIds.single()))
        assertTrue(final.parentVersionIds.contains(widerView.finalHeadVersionIds.single()))
    }

    @Test
    fun zeroAndMultipleUsableMergeBasesNeverProducePartialOutput() {
        val factory = factory(IdsV2())
        val leftRoot = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(title = "left-root"),
            actor,
            instant(1),
        )
        val rightRoot = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(title = "right-root"),
            actor,
            instant(2),
        )
        val noBase = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, leftRoot.key, listOf(leftRoot, rightRoot)),
        ).plan
        assertEquals(
            WorkspaceConflictReasonV2.NO_USABLE_MERGE_BASE,
            assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(noBase.outcome).descriptor.reason,
        )
        assertTrue(noBase.generatedVersions.isEmpty())

        val left = factory.createManualResolution(
            listOf(leftRoot, rightRoot),
            notePayload(title = "left"),
            null,
            actor,
            instant(3),
        )
        val right = factory.createManualResolution(
            listOf(leftRoot, rightRoot),
            notePayload(title = "right"),
            null,
            actor,
            instant(4),
        )
        val multipleBases = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, leftRoot.key, listOf(leftRoot, rightRoot, left, right)),
        ).plan
        val descriptor = assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(multipleBases.outcome).descriptor
        assertEquals(WorkspaceConflictReasonV2.NO_USABLE_MERGE_BASE, descriptor.reason)
        assertNull(descriptor.baseVersionId)
        assertTrue(multipleBases.generatedVersions.isEmpty())
    }

    @Test
    fun randomIdsAreUuidV4AndUniqueAcrossGenerators() {
        val first = RandomUuidCausalityIdGeneratorV2()
        val second = RandomUuidCausalityIdGeneratorV2()
        val ids = buildList {
            repeat(2_000) {
                add(first.newId())
                add(second.newId())
            }
        }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all(UUID_V4_PATTERN_SYSTEM_V2::matches))
    }

    @Test
    fun graphRejectsMissingCrossEntityCyclicAndWrongGenerationParents() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val child = factory.createContentChild(root, notePayload(body = "child"), actor, instant(2))
        val missing = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            engine.reconcile(epoch, root.key, listOf(child)),
        )
        assertTrue(missing.errors.any { it.code == WorkspaceCausalityErrorCodeV2.MISSING_PARENT })

        val wrongGenerationUnsigned = child.copy(generation = 99, objectDigest = "pending")
        val wrongGeneration = wrongGenerationUnsigned.copy(
            objectDigest = materializer.objectDigest(wrongGenerationUnsigned),
        )
        val generation = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            engine.reconcile(epoch, root.key, listOf(root, wrongGeneration)),
        )
        assertTrue(generation.errors.any { it.code == WorkspaceCausalityErrorCodeV2.INVALID_GENERATION })

        val notebook = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            notebookId,
            NotebookContentV2("Other", 0, instant(1)),
            actor,
            instant(1),
        )
        val crossUnsigned = child.copy(
            parentVersionIds = listOf(notebook.versionId),
            generation = 2,
            objectDigest = "pending",
        )
        val cross = crossUnsigned.copy(objectDigest = materializer.objectDigest(crossUnsigned))
        val crossResult = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            engine.reconcile(epoch, root.key, listOf(notebook, cross)),
        )
        assertTrue(crossResult.errors.any { it.code == WorkspaceCausalityErrorCodeV2.CROSS_ENTITY_PARENT })

        val cyclicRootUnsigned = root.copy(
            parentVersionIds = listOf(child.versionId),
            generation = 3,
            objectDigest = "pending",
        )
        val cyclicRoot = cyclicRootUnsigned.copy(objectDigest = materializer.objectDigest(cyclicRootUnsigned))
        val cycle = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            engine.reconcile(epoch, root.key, listOf(cyclicRoot, child)),
        )
        assertTrue(cycle.errors.any { it.code == WorkspaceCausalityErrorCodeV2.CYCLIC_GRAPH })
    }

    @Test
    fun largeHeadSetsUseDeterministicBoundedJoinBatches() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val equivalent = (1L..65L).map { index ->
            factory.createContentChild(root, notePayload(), actor, instant(index + 1))
        }
        val forward = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + equivalent),
        ).plan
        val reversed = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, (listOf(root) + equivalent).reversed()),
        ).plan
        assertEquals(1, forward.finalHeadVersionIds.size)
        assertEquals(forward.finalHeadVersionIds, reversed.finalHeadVersionIds)
        assertEquals(
            forward.generatedVersions.map { it.versionId },
            reversed.generatedVersions.map { it.versionId },
        )
        assertTrue(forward.generatedVersions.all { it.parentVersionIds.size <= MAX_WORKSPACE_ENTITY_PARENTS_V2 })

        val deletions = equivalent.mapIndexed { index, branch ->
            factory.createDeletion(branch, instant(100L - index), actor, instant(200L + index))
        }
        val deletionPlan = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + equivalent + deletions),
        ).plan
        assertEquals(1, deletionPlan.finalHeadVersionIds.size)
        assertEquals(instant(36), deletionPlan.generatedVersions.last().deletionPayload?.deletedAt)
        assertTrue(deletionPlan.generatedVersions.all { it.parentVersionIds.size <= MAX_WORKSPACE_ENTITY_PARENTS_V2 })

        val resolution = factory.createManualResolutionChain(
            parents = equivalent,
            selectedContent = notePayload(body = "resolved"),
            selectedDeletion = null,
            deviceActorId = actor,
            authoredAt = instant(300),
        )
        assertEquals(3, resolution.size)
        assertTrue(resolution.all { it.parentVersionIds.size in 2..MAX_WORKSPACE_ENTITY_PARENTS_V2 })
        assertTrue(resolution.zipWithNext().all { (left, right) -> left.versionId in right.parentVersionIds })

        val base = root.contentPayload as NoteContentV2
        val safeMergeHeads = (1 until 64).map { mask ->
            factory.createContentChild(
                root,
                base.copy(
                    notebookId = if (mask and 1 != 0) "alternate-notebook" else base.notebookId,
                    title = if (mask and 2 != 0) "changed-title" else base.title,
                    markdownBody = if (mask and 4 != 0) "changed-body" else base.markdownBody,
                    noteCreatedAt = if (mask and 8 != 0) instant(500) else base.noteCreatedAt,
                    timeZoneId = if (mask and 16 != 0) "UTC" else base.timeZoneId,
                    location = if (mask and 32 != 0) {
                        NoteLocationV2(null, null, "changed-place", null, null, instant(501))
                    } else base.location,
                ),
                actor,
                instant(600L + mask),
            )
        }
        val safeMerged = assertIs<WorkspaceReconciliationResultV2.Reconciled>(
            engine.reconcile(epoch, root.key, listOf(root) + safeMergeHeads),
        ).plan
        assertEquals(1, safeMerged.finalHeadVersionIds.size)
        assertTrue(safeMerged.generatedVersions.size >= 2)
        assertTrue(safeMerged.generatedVersions.all { it.parentVersionIds.size <= MAX_WORKSPACE_ENTITY_PARENTS_V2 })
        val payload = safeMerged.generatedVersions.last().contentPayload as NoteContentV2
        assertEquals("alternate-notebook", payload.notebookId)
        assertEquals("changed-title", payload.title)
        assertEquals("changed-body", payload.markdownBody)
        assertEquals("changed-place", payload.location?.placeText)
    }

    @Test
    fun equalPayloadDigestWithUnequalCanonicalBytesBlocksAsCollision() {
        val factory = factory(IdsV2())
        val root = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            noteId,
            notePayload(),
            actor,
            instant(1),
        )
        val left = factory.createContentChild(root, notePayload(title = "left"), actor, instant(2))
        val rightOriginal = factory.createContentChild(root, notePayload(title = "right"), actor, instant(3))
        val right = rightOriginal.copy(payloadDigest = left.payloadDigest)
        val collisionMaterializer = object : WorkspaceCausalityMaterializerV2 {
            override fun materializeAutomaticVersion(
                draft: AutomaticWorkspaceVersionDraftV2,
            ): WorkspaceEntityVersionV2 = materializer.materializeAutomaticVersion(draft)

            override fun conflictId(
                syncEpochId: String,
                key: WorkspaceEntityKeyV2,
                sortedHeadVersionIds: List<String>,
            ): String = materializer.conflictId(syncEpochId, key, sortedHeadVersionIds)

            override fun canonicalPayloadBytes(version: WorkspaceEntityVersionV2): ByteArray =
                materializer.canonicalPayloadBytes(version)
        }
        val collisionEngine = WorkspaceEntityCausalityEngineV2(
            collisionMaterializer,
            WorkspaceEntityValidatorV2(),
        )
        val result = assertIs<WorkspaceReconciliationResultV2.InvalidGraph>(
            collisionEngine.reconcile(epoch, root.key, listOf(root, left, right)),
        )
        assertEquals(
            WorkspaceCausalityErrorCodeV2.PAYLOAD_DIGEST_COLLISION,
            result.errors.single().code,
        )
    }

    private fun factory(ids: IdsV2) = WorkspaceEntityVersionFactoryV2(epoch, materializer, ids)

    private fun notePayload(
        title: String = "title",
        body: String = "body",
        location: NoteLocationV2? = null,
    ): NoteContentV2 = NoteContentV2(
        notebookId = notebookId,
        title = title,
        markdownBody = body,
        noteCreatedAt = instant(1),
        timeZoneId = "Asia/Shanghai",
        location = location,
    )

    private fun instant(second: Long): Instant = Instant.fromEpochSeconds(second)
}

private class IdsV2 : CausalityIdGeneratorV2 {
    private var next = 100L

    override fun newId(): String = "90000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
}
