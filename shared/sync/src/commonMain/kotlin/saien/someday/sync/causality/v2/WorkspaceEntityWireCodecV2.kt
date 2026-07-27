@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant

data class WorkspaceVersionOuterMetadataV2(
    val syncEpochId: String,
    val objectId: String,
    val objectDigest: String,
)

enum class WorkspaceEntityWireErrorCodeV2(val wireValue: String) {
    ENVELOPE_TOO_LARGE("envelope_too_large"),
    MALFORMED_CBOR("malformed_cbor"),
    INVALID_ENVELOPE("invalid_envelope"),
    METADATA_MISMATCH("metadata_mismatch"),
    INTEGRITY_MISMATCH("integrity_mismatch"),
    INCOMPATIBLE_CONTRACT("incompatible_contract"),
}

data class WorkspaceEntityWireErrorV2(
    val code: WorkspaceEntityWireErrorCodeV2,
    val safeMessage: String,
)

sealed interface WorkspaceEntityWireDecodeResultV2 {
    data class Decoded(val version: WorkspaceEntityVersionV2) : WorkspaceEntityWireDecodeResultV2

    data class Rejected(val error: WorkspaceEntityWireErrorV2) : WorkspaceEntityWireDecodeResultV2
}

class WorkspaceEntityWireCodecV2(
    private val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    private val validator: WorkspaceEntityValidatorV2 = WorkspaceEntityValidatorV2(materializer),
) {
    fun encode(version: WorkspaceEntityVersionV2): ByteArray {
        val errors = validator.validateEnvelope(version)
        require(errors.isEmpty()) { "Cannot encode invalid V2 entity: ${errors.joinToString { it.code.wireValue }}" }
        return materializer.fullEnvelopeBytes(version).also {
            require(it.size <= MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2)
        }
    }

    fun decode(
        plaintext: ByteArray,
        outer: WorkspaceVersionOuterMetadataV2? = null,
    ): WorkspaceEntityWireDecodeResultV2 {
        if (plaintext.size > MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2) {
            return rejected(WorkspaceEntityWireErrorCodeV2.ENVELOPE_TOO_LARGE, "Entity plaintext exceeds the protocol limit.")
        }
        val root = try {
            DeterministicCborV2.decode(plaintext).asExactMap(ENTITY_ENVELOPE_KEYS_V2)
        } catch (_: Exception) {
            return rejected(WorkspaceEntityWireErrorCodeV2.MALFORMED_CBOR, "Entity plaintext is not canonical protocol CBOR.")
        }
        val version = try {
            root.toWorkspaceVersionV2()
        } catch (_: Exception) {
            return rejected(WorkspaceEntityWireErrorCodeV2.INVALID_ENVELOPE, "Entity envelope has an invalid exact shape.")
        }
        if (version.contractId != SYNC_V2_CONTRACT_ID || version.schemaSetVersion != SYNC_V2_SCHEMA_SET_VERSION) {
            return rejected(WorkspaceEntityWireErrorCodeV2.INCOMPATIBLE_CONTRACT, "Entity belongs to an unsupported contract or schema set.")
        }
        if (outer != null && (outer.syncEpochId != version.syncEpochId ||
                outer.objectId != version.versionId || outer.objectDigest != version.objectDigest)
        ) {
            return rejected(WorkspaceEntityWireErrorCodeV2.METADATA_MISMATCH, "Authenticated outer identity does not match the entity envelope.")
        }
        val errors = validator.validateEnvelope(version)
        if (errors.isNotEmpty()) {
            val integrity = errors.any { it.code in setOf(
                WorkspaceCausalityErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH,
                WorkspaceCausalityErrorCodeV2.INVALID_DIGEST,
            ) }
            return rejected(
                if (integrity) WorkspaceEntityWireErrorCodeV2.INTEGRITY_MISMATCH
                else WorkspaceEntityWireErrorCodeV2.INVALID_ENVELOPE,
                "Entity envelope failed protocol validation (${errors.first().code.wireValue}).",
            )
        }
        return WorkspaceEntityWireDecodeResultV2.Decoded(version)
    }

    private fun rejected(
        code: WorkspaceEntityWireErrorCodeV2,
        message: String,
    ): WorkspaceEntityWireDecodeResultV2.Rejected =
        WorkspaceEntityWireDecodeResultV2.Rejected(WorkspaceEntityWireErrorV2(code, message))
}

private fun Map<String, CborValueV2>.toWorkspaceVersionV2(): WorkspaceEntityVersionV2 {
    val entityType = requiredText("entityType").let {
        WorkspaceEntityTypeV2.fromWire(it) ?: throw IllegalArgumentException("Unknown entity type.")
    }
    val kind = requiredText("kind").let {
        WorkspaceEntityVersionKindV2.fromWire(it) ?: throw IllegalArgumentException("Unknown entity kind.")
    }
    val content = when (val value = getValue("contentPayload")) {
        CborValueV2.Null -> null
        else -> value.toWorkspaceContentV2(entityType)
    }
    val deletion = when (val value = getValue("deletionPayload")) {
        CborValueV2.Null -> null
        else -> {
            val map = value.asExactMap(DELETION_KEYS_V2)
            WorkspaceDeletionV2(map.getValue("deletedAt").asInstantV2())
        }
    }
    val provenance = when (val value = getValue("provenance")) {
        CborValueV2.Null -> null
        else -> value.toProvenanceV2()
    }
    return WorkspaceEntityVersionV2(
        envelopeSchemaVersion = requiredLong("envelopeSchemaVersion").toIntExactV2(),
        contractId = requiredText("contractId"),
        schemaSetVersion = requiredText("schemaSetVersion"),
        syncEpochId = requiredText("syncEpochId"),
        versionId = requiredText("versionId"),
        entityType = entityType,
        entitySchemaVersion = requiredLong("entitySchemaVersion").toIntExactV2(),
        entityId = requiredText("entityId"),
        parentVersionIds = getValue("parentVersionIds").asArrayV2().map { it.asTextV2() },
        kind = kind,
        contentPayload = content,
        deletionPayload = deletion,
        provenance = provenance,
        authorActorId = requiredText("authorActorId"),
        authoredAt = getValue("authoredAt").asInstantV2(),
        generation = requiredLong("generation"),
        payloadDigest = requiredText("payloadDigest"),
        objectDigest = requiredText("objectDigest"),
        mergeAlgorithmVersion = nullableText("mergeAlgorithmVersion"),
    )
}

private fun CborValueV2.toWorkspaceContentV2(entityType: WorkspaceEntityTypeV2): WorkspaceEntityContentV2 =
    when (entityType) {
        WorkspaceEntityTypeV2.NOTE -> {
            val map = asExactMap(NOTE_CONTENT_KEYS_V2)
            NoteContentV2(
                notebookId = map.requiredText("notebookId"),
                title = map.requiredText("title"),
                markdownBody = map.requiredText("markdownBody"),
                noteCreatedAt = map.getValue("noteCreatedAt").asInstantV2(),
                timeZoneId = map.nullableText("timeZoneId"),
                location = when (val value = map.getValue("location")) {
                    CborValueV2.Null -> null
                    else -> value.toLocationV2()
                },
            )
        }
        WorkspaceEntityTypeV2.NOTEBOOK -> {
            val map = asExactMap(NOTEBOOK_CONTENT_KEYS_V2)
            NotebookContentV2(
                title = map.requiredText("title"),
                sortOrder = map.requiredLong("sortOrder"),
                notebookCreatedAt = map.getValue("notebookCreatedAt").asInstantV2(),
            )
        }
        WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES -> {
            val map = asExactMap(PREFERENCES_CONTENT_KEYS_V2)
            WorkspacePreferencesV2(
                theme = WorkspaceThemeV2.fromWire(map.requiredText("theme"))
                    ?: throw IllegalArgumentException("Unknown workspace theme."),
                previewByDefault = map.getValue("previewByDefault").asBooleanV2(),
                markdownToolbarVisible = map.getValue("markdownToolbarVisible").asBooleanV2(),
                defaultNotebookId = map.nullableText("defaultNotebookId"),
            )
        }
    }

private fun CborValueV2.toLocationV2(): NoteLocationV2 {
    val map = asExactMap(LOCATION_KEYS_V2)
    return NoteLocationV2(
        latitude = map.nullableFloat64("latitude"),
        longitude = map.nullableFloat64("longitude"),
        placeText = map.nullableText("placeText"),
        accuracyMeters = map.nullableFloat64("accuracyMeters"),
        altitudeMeters = map.nullableFloat64("altitudeMeters"),
        capturedAt = map.getValue("capturedAt").asInstantV2(),
    )
}

private fun CborValueV2.toProvenanceV2(): WorkspaceVersionProvenanceV2 {
    val map = asExactMap(PROVENANCE_KEYS_V2)
    return WorkspaceVersionProvenanceV2(
        type = WorkspaceVersionProvenanceTypeV2.fromWire(map.requiredText("type"))
            ?: throw IllegalArgumentException("Unknown provenance type."),
        sourceProfile = map.nullableText("sourceProfile"),
        sourceEpoch = map.nullableText("sourceEpoch"),
        sourceWriterId = map.nullableText("sourceWriterId"),
        sourceMutationId = map.nullableText("sourceMutationId"),
        sourceObjectId = map.nullableText("sourceObjectId"),
        sourceDigest = map.nullableText("sourceDigest"),
    )
}

private fun CborValueV2.asExactMap(expectedKeys: Set<String>): Map<String, CborValueV2> {
    val map = this as? CborValueV2.Map ?: throw IllegalArgumentException("Expected map.")
    val values = map.entries.associate { (key, value) ->
        (key as? CborValueV2.TextString)?.value?.let { it to value }
            ?: throw IllegalArgumentException("Expected text map key.")
    }
    require(values.size == map.entries.size && values.keys == expectedKeys) { "Map has missing, duplicate, or unknown fields." }
    return values
}

private fun CborValueV2.asTextV2(): String =
    (this as? CborValueV2.TextString)?.value ?: throw IllegalArgumentException("Expected text.")

private fun CborValueV2.asArrayV2(): List<CborValueV2> =
    (this as? CborValueV2.Array)?.values ?: throw IllegalArgumentException("Expected array.")

private fun CborValueV2.asBooleanV2(): Boolean =
    (this as? CborValueV2.Boolean)?.value ?: throw IllegalArgumentException("Expected boolean.")

private fun CborValueV2.asInstantV2(): Instant {
    val values = asArrayV2()
    require(values.size == 2)
    val seconds = (values[0] as? CborValueV2.Integer)?.value ?: throw IllegalArgumentException("Expected instant seconds.")
    val nanos = (values[1] as? CborValueV2.Integer)?.value ?: throw IllegalArgumentException("Expected instant nanos.")
    require(nanos in 0..999_999_999)
    return Instant.fromEpochSeconds(seconds, nanos)
}

private fun Map<String, CborValueV2>.requiredText(key: String): String = getValue(key).asTextV2()

private fun Map<String, CborValueV2>.requiredLong(key: String): Long =
    (getValue(key) as? CborValueV2.Integer)?.value ?: throw IllegalArgumentException("Expected integer.")

private fun Map<String, CborValueV2>.nullableText(key: String): String? = when (val value = getValue(key)) {
    CborValueV2.Null -> null
    else -> value.asTextV2()
}

private fun Map<String, CborValueV2>.nullableFloat64(key: String): Double? = when (val value = getValue(key)) {
    CborValueV2.Null -> null
    is CborValueV2.Float64 -> value.value
    else -> throw IllegalArgumentException("Expected float64 or null.")
}

private fun Long.toIntExactV2(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return toInt()
}

private val ENTITY_ENVELOPE_KEYS_V2 = setOf(
    "authoredAt",
    "authorActorId",
    "contentPayload",
    "contractId",
    "deletionPayload",
    "entityId",
    "entitySchemaVersion",
    "entityType",
    "envelopeSchemaVersion",
    "generation",
    "kind",
    "mergeAlgorithmVersion",
    "objectDigest",
    "parentVersionIds",
    "payloadDigest",
    "provenance",
    "schemaSetVersion",
    "syncEpochId",
    "versionId",
)
private val NOTE_CONTENT_KEYS_V2 = setOf(
    "location", "markdownBody", "noteCreatedAt", "notebookId", "timeZoneId", "title",
)
private val NOTEBOOK_CONTENT_KEYS_V2 = setOf("notebookCreatedAt", "sortOrder", "title")
private val PREFERENCES_CONTENT_KEYS_V2 = setOf(
    "defaultNotebookId", "markdownToolbarVisible", "previewByDefault", "theme",
)
private val LOCATION_KEYS_V2 = setOf(
    "accuracyMeters", "altitudeMeters", "capturedAt", "latitude", "longitude", "placeText",
)
private val DELETION_KEYS_V2 = setOf("deletedAt")
private val PROVENANCE_KEYS_V2 = setOf(
    "sourceDigest", "sourceEpoch", "sourceMutationId", "sourceObjectId", "sourceProfile", "sourceWriterId", "type",
)
