@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant
import kotlin.uuid.Uuid

fun interface CausalityIdGeneratorV2 {
    fun newId(): String
}

class RandomUuidCausalityIdGeneratorV2 : CausalityIdGeneratorV2 {
    override fun newId(): String = Uuid.random().toString()
}

class WorkspaceEntityValidatorV2(
    private val materializer: CanonicalWorkspaceCausalityMaterializerV2? = null,
) {
    fun validateEnvelope(version: WorkspaceEntityVersionV2): List<WorkspaceCausalityErrorV2> {
        val errors = mutableListOf<WorkspaceCausalityErrorV2>()
        fun error(code: WorkspaceCausalityErrorCodeV2, message: String, related: String? = null) {
            errors += WorkspaceCausalityErrorV2(code, version.versionId, related, message)
        }

        if (version.envelopeSchemaVersion != WORKSPACE_ENTITY_ENVELOPE_SCHEMA_VERSION_V2 ||
            version.entitySchemaVersion != WORKSPACE_ENTITY_SCHEMA_VERSION_V2
        ) {
            error(WorkspaceCausalityErrorCodeV2.UNSUPPORTED_SCHEMA_VERSION, "Unsupported V2 entity envelope or payload schema.")
        }
        if (version.contractId != SYNC_V2_CONTRACT_ID) {
            error(WorkspaceCausalityErrorCodeV2.CONTRACT_MISMATCH, "Entity version belongs to another sync contract.")
        }
        if (version.schemaSetVersion != SYNC_V2_SCHEMA_SET_VERSION) {
            error(WorkspaceCausalityErrorCodeV2.SCHEMA_SET_MISMATCH, "Entity version belongs to another schema set.")
        }
        if (!UUID_V4_PATTERN_SYSTEM_V2.matches(version.syncEpochId) ||
            !UUID_V4_PATTERN_SYSTEM_V2.matches(version.versionId) ||
            !version.entityId.isWholeProductProtocolIdentifierV2() ||
            version.parentVersionIds.any { !UUID_V4_PATTERN_SYSTEM_V2.matches(it) }
        ) {
            error(WorkspaceCausalityErrorCodeV2.INVALID_IDENTIFIER, "Entity envelope contains an invalid protocol identifier.")
        }
        if (version.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES &&
            version.entityId != WORKSPACE_PREFERENCES_ENTITY_ID_V2
        ) {
            error(WorkspaceCausalityErrorCodeV2.INVALID_SINGLETON_ENTITY, "Workspace preferences must use its singleton entity id.")
        }
        if (version.parentVersionIds != version.parentVersionIds.distinct().sorted() ||
            version.parentVersionIds.size > MAX_WORKSPACE_ENTITY_PARENTS_V2 ||
            version.versionId in version.parentVersionIds
        ) {
            error(WorkspaceCausalityErrorCodeV2.INVALID_PARENT_SET, "Parent ids must be unique, sorted, non-self, and within the protocol limit.")
        }
        if (version.generation < 1) {
            error(WorkspaceCausalityErrorCodeV2.INVALID_GENERATION, "Entity generation must be positive.")
        }
        if (!PAYLOAD_DIGEST_PATTERN_SYSTEM_V2.matches(version.payloadDigest) ||
            !OBJECT_DIGEST_PATTERN_SYSTEM_V2.matches(version.objectDigest)
        ) {
            error(WorkspaceCausalityErrorCodeV2.INVALID_DIGEST, "Entity envelope uses an unsupported digest form.")
        }
        errors += validatePayload(version)
        errors += validateAuthorship(version)
        errors += validateProvenance(version)

        materializer?.let { canonical ->
            if (runCatching { canonical.fullEnvelopeBytes(version).size }.getOrDefault(Int.MAX_VALUE) >
                MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2
            ) {
                error(WorkspaceCausalityErrorCodeV2.ENVELOPE_TOO_LARGE, "Entity plaintext exceeds the protocol byte limit.")
            }
            if (errors.none { it.code in setOf(
                    WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD,
                    WorkspaceCausalityErrorCodeV2.INVALID_DIGEST,
                )
                }
            ) {
                val integrity = runCatching { canonical.validateIntegrity(version) }.getOrNull()
                if (integrity == null || !integrity.isValid) {
                    error(WorkspaceCausalityErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH, "Entity immutable digests do not match its canonical envelope.")
                }
            }
            canonical.expectedDeterministicVersionId(version)?.let { expectedId ->
                if (version.versionId != expectedId) {
                    error(
                        WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION,
                        "Deterministic automatic/import version identity does not match its canonical context.",
                        expectedId,
                    )
                }
            }
        }
        return errors.distinct()
    }

    private fun validatePayload(version: WorkspaceEntityVersionV2): List<WorkspaceCausalityErrorV2> {
        val errors = mutableListOf<WorkspaceCausalityErrorV2>()
        fun invalid(message: String) {
            errors += WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD,
                version.versionId,
                safeMessage = message,
            )
        }
        when (version.kind) {
            WorkspaceEntityVersionKindV2.CONTENT -> {
                val content = version.contentPayload
                if (content == null || version.deletionPayload != null || content.entityType != version.entityType) {
                    invalid("Content/deletion shape does not match entity kind and type.")
                    return errors
                }
                when (content) {
                    is NoteContentV2 -> {
                        if (!content.notebookId.isWholeProductProtocolIdentifierV2() ||
                            content.title.utf8SizeV2() > MAX_NOTE_TITLE_BYTES_V2 ||
                            content.markdownBody.utf8SizeV2() > MAX_NOTE_MARKDOWN_BYTES_V2 ||
                            content.timeZoneId?.let { it.isBlank() || it != it.trim() || it.utf8SizeV2() > MAX_NOTE_TIME_ZONE_BYTES_V2 } == true
                        ) {
                            invalid("Note payload violates an identifier or text bound.")
                        }
                        content.location?.let { location ->
                            val hasCoordinates = location.latitude != null && location.longitude != null
                            val hasPartialCoordinates = (location.latitude == null) != (location.longitude == null)
                            val hasPlace = !location.placeText.isNullOrBlank()
                            val numericValues = listOfNotNull(
                                location.latitude,
                                location.longitude,
                                location.accuracyMeters,
                                location.altitudeMeters,
                            )
                            if (hasPartialCoordinates || (!hasCoordinates && !hasPlace) ||
                                numericValues.any { !it.isFinite() || it.toBits() == Long.MIN_VALUE } ||
                                location.latitude?.let { it !in -90.0..90.0 } == true ||
                                location.longitude?.let { it !in -180.0..180.0 } == true ||
                                location.accuracyMeters?.let { it < 0.0 } == true ||
                                (!hasCoordinates && (location.accuracyMeters != null || location.altitudeMeters != null)) ||
                                location.placeText?.let { it.isBlank() || it.utf8SizeV2() > MAX_NOTE_LOCATION_PLACE_BYTES_V2 } == true
                            ) {
                                invalid("Note location violates atomic location normalization or numeric bounds.")
                            }
                        }
                    }
                    is NotebookContentV2 -> {
                        if (content.title.isBlank() || content.title.utf8SizeV2() > MAX_NOTE_TITLE_BYTES_V2) {
                            invalid("Notebook title is blank or exceeds the protocol bound.")
                        }
                    }
                    is WorkspacePreferencesV2 -> {
                        if (content.defaultNotebookId?.isWholeProductProtocolIdentifierV2() == false) {
                            invalid("Workspace default notebook id is invalid.")
                        }
                    }
                }
            }
            WorkspaceEntityVersionKindV2.DELETION -> {
                if (version.contentPayload != null || version.deletionPayload == null) {
                    invalid("Deletion version must contain only deletionPayload.")
                }
                if (version.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES) {
                    invalid("Workspace preferences cannot be deleted.")
                }
            }
        }
        return errors
    }

    private fun validateAuthorship(version: WorkspaceEntityVersionV2): List<WorkspaceCausalityErrorV2> {
        val automatic = version.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2
        val manual = version.mergeAlgorithmVersion == MANUAL_RESOLUTION_ALGORITHM_V2
        val valid = when {
            version.mergeAlgorithmVersion !in ALL_MERGE_ALGORITHMS_SYSTEM_V2 -> false
            automatic -> version.authorActorId == SYSTEM_AUTO_MERGE_ACTOR_V2 &&
                version.parentVersionIds.size in 2..MAX_WORKSPACE_ENTITY_PARENTS_V2 &&
                when (version.mergeAlgorithmVersion) {
                    EQUIVALENT_MERGE_ALGORITHM_V2,
                    FIELD_MERGE_ALGORITHM_V2,
                    -> version.kind == WorkspaceEntityVersionKindV2.CONTENT
                    DELETION_MERGE_ALGORITHM_V2 -> version.kind == WorkspaceEntityVersionKindV2.DELETION
                    else -> false
                }
            manual -> DEVICE_ACTOR_PATTERN_SYSTEM_V2.matches(version.authorActorId) && version.parentVersionIds.size in 2..32
            version.provenance?.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT ->
                version.authorActorId == SYSTEM_CHECKPOINT_ACTOR_V2 && version.parentVersionIds.isEmpty()
            version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT ->
                version.authorActorId == SYSTEM_MIGRATION_ACTOR_V2 && version.parentVersionIds.size <= 1
            else -> DEVICE_ACTOR_PATTERN_SYSTEM_V2.matches(version.authorActorId) && version.parentVersionIds.size <= 1
        }
        return if (valid) emptyList() else listOf(
            WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_AUTHOR_ACTOR,
                version.versionId,
                safeMessage = "Actor, provenance, parent count, and merge algorithm are inconsistent.",
            ),
        )
    }

    private fun validateProvenance(version: WorkspaceEntityVersionV2): List<WorkspaceCausalityErrorV2> {
        val provenance = version.provenance
        val sources = provenance?.let {
            listOfNotNull(
                it.sourceProfile,
                it.sourceEpoch,
                it.sourceWriterId,
                it.sourceMutationId,
                it.sourceObjectId,
                it.sourceDigest,
            )
        }.orEmpty()
        val valid = when (provenance?.type) {
            WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT ->
                version.parentVersionIds.isEmpty() && version.authorActorId == SYSTEM_CHECKPOINT_ACTOR_V2
            WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT ->
                !provenance.sourceProfile.isNullOrBlank() &&
                    !provenance.sourceObjectId.isNullOrBlank() &&
                    !provenance.sourceDigest.isNullOrBlank() &&
                    version.parentVersionIds.size <= 1 &&
                    version.authorActorId == SYSTEM_MIGRATION_ACTOR_V2
            null -> version.authorActorId !in setOf(SYSTEM_CHECKPOINT_ACTOR_V2, SYSTEM_MIGRATION_ACTOR_V2)
        } && sources.all { it.utf8SizeV2() <= MAX_PROVENANCE_VALUE_BYTES_V2 }
        return if (valid) emptyList() else listOf(
            WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_PROVENANCE,
                version.versionId,
                safeMessage = "Entity provenance is invalid for its root/parent shape.",
            ),
        )
    }
}

sealed interface TokenBasedVersionResultV2 {
    data class Created(
        val version: WorkspaceEntityVersionV2,
        val mutationId: String,
    ) : TokenBasedVersionResultV2

    data class Rejected(
        val error: WorkspaceCausalityErrorV2,
        val preservedDraft: WorkspaceEntityContentV2?,
    ) : TokenBasedVersionResultV2
}

class WorkspaceEntityVersionFactoryV2(
    private val syncEpochId: String,
    private val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    private val idGenerator: CausalityIdGeneratorV2 = RandomUuidCausalityIdGeneratorV2(),
) {
    private val validator = WorkspaceEntityValidatorV2(materializer)

    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(syncEpochId))
    }

    fun newEntityId(): String = checkedRandomId()

    fun newMutationId(): String = checkedRandomId()

    fun createGenesis(
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        content: WorkspaceEntityContentV2,
        deviceActorId: String,
        authoredAt: Instant,
    ): WorkspaceEntityVersionV2 = sign(
        versionId = checkedRandomId(),
        entityType = entityType,
        entityId = entityId,
        parents = emptyList(),
        kind = WorkspaceEntityVersionKindV2.CONTENT,
        content = normalizeContentV2(content),
        deletion = null,
        provenance = null,
        actor = deviceActorId,
        authoredAt = authoredAt,
        mergeAlgorithm = null,
    )

    fun createFromToken(
        token: WorkspaceCausalEditTokenV2,
        retainedVersions: Map<String, WorkspaceEntityVersionV2>,
        content: WorkspaceEntityContentV2?,
        deletedAt: Instant?,
        deviceActorId: String,
        authoredAt: Instant,
    ): TokenBasedVersionResultV2 {
        if (token.syncEpochId != syncEpochId || token.activeConflictId != null) {
            return tokenRejected(
                token,
                content,
                WorkspaceCausalityErrorCodeV2.EDIT_TOKEN_MISMATCH,
                "The edit view belongs to an old epoch or active conflict.",
            )
        }
        val parent = retainedVersions[token.expectedBaseVersionId]
        if (parent == null || parent.syncEpochId != syncEpochId || parent.key != WorkspaceEntityKeyV2(token.entityType, token.entityId)) {
            return tokenRejected(
                token,
                content,
                WorkspaceCausalityErrorCodeV2.STALE_EDIT_BASE_MISSING,
                "The exact viewed edit base is no longer retained.",
            )
        }
        val version = when {
            content != null && deletedAt == null -> createContentChild(parent, content, deviceActorId, authoredAt)
            content == null && deletedAt != null -> createDeletion(parent, deletedAt, deviceActorId, authoredAt)
            else -> return TokenBasedVersionResultV2.Rejected(
                WorkspaceCausalityErrorV2(
                    WorkspaceCausalityErrorCodeV2.INVALID_PAYLOAD,
                    relatedId = token.expectedBaseVersionId,
                    safeMessage = "A typed edit must supply exactly one content or deletion payload.",
                ),
                content,
            )
        }
        return TokenBasedVersionResultV2.Created(version, newMutationId())
    }

    fun createContentChild(
        parent: WorkspaceEntityVersionV2,
        content: WorkspaceEntityContentV2,
        deviceActorId: String,
        authoredAt: Instant,
    ): WorkspaceEntityVersionV2 = sign(
        versionId = checkedRandomId(),
        entityType = parent.entityType,
        entityId = parent.entityId,
        parents = listOf(parent),
        kind = WorkspaceEntityVersionKindV2.CONTENT,
        content = normalizeContentV2(content),
        deletion = null,
        provenance = null,
        actor = deviceActorId,
        authoredAt = authoredAt,
        mergeAlgorithm = null,
    )

    fun createDeletion(
        parent: WorkspaceEntityVersionV2,
        deletedAt: Instant,
        deviceActorId: String,
        authoredAt: Instant,
    ): WorkspaceEntityVersionV2 {
        require(parent.entityType != WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES)
        require(parent.kind == WorkspaceEntityVersionKindV2.CONTENT) { "Deleting an already deleted entity is a no-op." }
        return sign(
            versionId = checkedRandomId(),
            entityType = parent.entityType,
            entityId = parent.entityId,
            parents = listOf(parent),
            kind = WorkspaceEntityVersionKindV2.DELETION,
            content = null,
            deletion = WorkspaceDeletionV2(deletedAt),
            provenance = null,
            actor = deviceActorId,
            authoredAt = authoredAt,
            mergeAlgorithm = null,
        )
    }

    fun createManualResolution(
        parents: List<WorkspaceEntityVersionV2>,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        deviceActorId: String,
        authoredAt: Instant,
    ): WorkspaceEntityVersionV2 {
        require(parents.size in 2..MAX_WORKSPACE_ENTITY_PARENTS_V2)
        require(parents.map { it.versionId }.distinct().size == parents.size)
        require(parents.map { Triple(it.syncEpochId, it.entityType, it.entityId) }.distinct().size == 1)
        return sign(
            versionId = checkedRandomId(),
            entityType = parents.first().entityType,
            entityId = parents.first().entityId,
            parents = parents,
            kind = if (content != null) WorkspaceEntityVersionKindV2.CONTENT else WorkspaceEntityVersionKindV2.DELETION,
            content = content?.let(::normalizeContentV2),
            deletion = deletion,
            provenance = null,
            actor = deviceActorId,
            authoredAt = authoredAt,
            mergeAlgorithm = MANUAL_RESOLUTION_ALGORITHM_V2,
        )
    }

    fun createManualResolutionChain(
        parents: List<WorkspaceEntityVersionV2>,
        selectedContent: WorkspaceEntityContentV2?,
        selectedDeletion: WorkspaceDeletionV2?,
        deviceActorId: String,
        authoredAt: Instant,
    ): List<WorkspaceEntityVersionV2> {
        require(parents.size >= 2)
        val sorted = parents.distinctBy { it.versionId }.sortedBy { it.versionId }
        require(sorted.size == parents.size)
        val output = mutableListOf<WorkspaceEntityVersionV2>()
        var prior: WorkspaceEntityVersionV2? = null
        var index = 0
        while (index < sorted.size) {
            val originals = sorted.drop(index).take(MAX_WORKSPACE_ENTITY_PARENTS_V2 - if (prior == null) 0 else 1)
            val joined = createManualResolution(
                parents = listOfNotNull(prior) + originals,
                content = selectedContent,
                deletion = selectedDeletion,
                deviceActorId = deviceActorId,
                authoredAt = authoredAt,
            )
            output += joined
            prior = joined
            index += originals.size
        }
        return output
    }

    fun createCheckpointRoot(
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        provenance: WorkspaceVersionProvenanceV2,
        authoredAt: Instant,
    ): WorkspaceEntityVersionV2 {
        require(provenance.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT)
        return sign(
            versionId = checkedRandomId(),
            entityType = entityType,
            entityId = entityId,
            parents = emptyList(),
            kind = if (content != null) WorkspaceEntityVersionKindV2.CONTENT else WorkspaceEntityVersionKindV2.DELETION,
            content = content?.let(::normalizeContentV2),
            deletion = deletion,
            provenance = provenance,
            actor = SYSTEM_CHECKPOINT_ACTOR_V2,
            authoredAt = authoredAt,
            mergeAlgorithm = null,
        )
    }

    fun createSourceImport(
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        provenance: WorkspaceVersionProvenanceV2,
        authoredAt: Instant,
        verifiedParent: WorkspaceEntityVersionV2?,
    ): WorkspaceEntityVersionV2 {
        require(provenance.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT)
        require(verifiedParent == null || verifiedParent.key == WorkspaceEntityKeyV2(entityType, entityId))
        val normalizedContent = content?.let(::normalizeContentV2)
        val base = unsigned(
            versionId = "00000000-0000-4000-8000-000000000000",
            entityType = entityType,
            entityId = entityId,
            parents = listOfNotNull(verifiedParent),
            kind = if (normalizedContent != null) WorkspaceEntityVersionKindV2.CONTENT else WorkspaceEntityVersionKindV2.DELETION,
            content = normalizedContent,
            deletion = deletion,
            provenance = provenance,
            actor = SYSTEM_MIGRATION_ACTOR_V2,
            authoredAt = authoredAt,
            mergeAlgorithm = null,
        )
        val identified = base.copy(versionId = materializer.sourceImportVersionId(base))
        return validateSigned(identified.copy(objectDigest = materializer.objectDigest(identified)))
    }

    private fun sign(
        versionId: String,
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        parents: List<WorkspaceEntityVersionV2>,
        kind: WorkspaceEntityVersionKindV2,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        provenance: WorkspaceVersionProvenanceV2?,
        actor: String,
        authoredAt: Instant,
        mergeAlgorithm: String?,
    ): WorkspaceEntityVersionV2 {
        val base = unsigned(
            versionId,
            entityType,
            entityId,
            parents,
            kind,
            content,
            deletion,
            provenance,
            actor,
            authoredAt,
            mergeAlgorithm,
        )
        return validateSigned(base.copy(objectDigest = materializer.objectDigest(base)))
    }

    private fun unsigned(
        versionId: String,
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        parents: List<WorkspaceEntityVersionV2>,
        kind: WorkspaceEntityVersionKindV2,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        provenance: WorkspaceVersionProvenanceV2?,
        actor: String,
        authoredAt: Instant,
        mergeAlgorithm: String?,
    ): WorkspaceEntityVersionV2 {
        require(parents.all { it.syncEpochId == syncEpochId && it.key == WorkspaceEntityKeyV2(entityType, entityId) })
        val parentIds = parents.map { it.versionId }.distinct().sorted()
        val payloadDigest = materializer.payloadDigest(entityType, kind, content, deletion)
        return WorkspaceEntityVersionV2(
            syncEpochId = syncEpochId,
            versionId = versionId,
            entityType = entityType,
            entityId = entityId,
            parentVersionIds = parentIds,
            kind = kind,
            contentPayload = content,
            deletionPayload = deletion,
            provenance = provenance,
            authorActorId = actor,
            authoredAt = authoredAt,
            generation = parents.maxOfOrNull { it.generation }?.plus(1) ?: 1,
            payloadDigest = payloadDigest,
            objectDigest = "pending",
            mergeAlgorithmVersion = mergeAlgorithm,
        )
    }

    private fun validateSigned(version: WorkspaceEntityVersionV2): WorkspaceEntityVersionV2 {
        val errors = validator.validateEnvelope(version)
        require(errors.isEmpty()) { errors.joinToString { it.code.wireValue } }
        return version
    }

    private fun checkedRandomId(): String = idGenerator.newId().lowercase().also {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(it)) { "V2 random ids must be lowercase UUIDv4." }
    }

    private fun tokenRejected(
        token: WorkspaceCausalEditTokenV2,
        draft: WorkspaceEntityContentV2?,
        code: WorkspaceCausalityErrorCodeV2,
        message: String,
    ): TokenBasedVersionResultV2.Rejected = TokenBasedVersionResultV2.Rejected(
        WorkspaceCausalityErrorV2(
            code,
            relatedId = token.expectedBaseVersionId,
            safeMessage = message,
        ),
        draft,
    )
}

internal fun normalizeContentV2(content: WorkspaceEntityContentV2): WorkspaceEntityContentV2 = when (content) {
    is NoteContentV2 -> content.normalized()
    is NotebookContentV2 -> content
    is WorkspacePreferencesV2 -> content
}

internal fun String.isWholeProductProtocolIdentifierV2(): Boolean {
    val bytes = encodeToByteArray()
    return bytes.size in 1..MAX_PROTOCOL_IDENTIFIER_BYTES_V2 && all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '_' || character == ':' || character == '.'
    }
}

internal fun String.utf8SizeV2(): Int = encodeToByteArray().size
