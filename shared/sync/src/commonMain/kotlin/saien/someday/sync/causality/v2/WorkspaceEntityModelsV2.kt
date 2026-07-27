@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant

const val SYNC_V2_CONTRACT_ID: String = "someday-system-v2"
const val SYNC_V2_SCHEMA_SET_VERSION: String = "workspace-entity-schema-set-v2"
const val WORKSPACE_ENTITY_ENVELOPE_SCHEMA_VERSION_V2: Int = 1
const val WORKSPACE_ENTITY_SCHEMA_VERSION_V2: Int = 1
const val WORKSPACE_PREFERENCES_ENTITY_ID_V2: String = "workspace-preferences"
const val WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2: String = "workspace_entity_version_v2"
const val MAX_WORKSPACE_ENTITY_PARENTS_V2: Int = 32
const val MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2: Int = 1_048_576
const val MAX_NOTE_TITLE_BYTES_V2: Int = 65_536
const val MAX_NOTE_MARKDOWN_BYTES_V2: Int = 921_600
const val MAX_NOTE_LOCATION_PLACE_BYTES_V2: Int = 1_024
const val MAX_NOTE_TIME_ZONE_BYTES_V2: Int = 255
const val MAX_PROTOCOL_IDENTIFIER_BYTES_V2: Int = 128
const val MAX_PROVENANCE_VALUE_BYTES_V2: Int = 512
const val SYSTEM_AUTO_MERGE_ACTOR_V2: String = "system:auto-merge"
const val SYSTEM_MIGRATION_ACTOR_V2: String = "system:migration"
const val SYSTEM_CHECKPOINT_ACTOR_V2: String = "system:checkpoint"
const val EQUIVALENT_MERGE_ALGORITHM_V2: String = "equivalent-v2"
const val DELETION_MERGE_ALGORITHM_V2: String = "deletion-v2"
const val FIELD_MERGE_ALGORITHM_V2: String = "field-merge-v2"
const val MANUAL_RESOLUTION_ALGORITHM_V2: String = "manual-resolution-v2"

enum class WorkspaceEntityTypeV2(val wireValue: String) {
    NOTE("note"),
    NOTEBOOK("notebook"),
    WORKSPACE_PREFERENCES("workspace_preferences"),
    ;

    companion object {
        fun fromWire(value: String): WorkspaceEntityTypeV2? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class WorkspaceEntityVersionKindV2(val wireValue: String) {
    CONTENT("content"),
    DELETION("deletion"),
    ;

    companion object {
        fun fromWire(value: String): WorkspaceEntityVersionKindV2? = entries.firstOrNull { it.wireValue == value }
    }
}

data class WorkspaceEntityKeyV2(
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
)

sealed interface WorkspaceEntityContentV2 {
    val entityType: WorkspaceEntityTypeV2
}

data class NoteLocationV2(
    val latitude: Double?,
    val longitude: Double?,
    val placeText: String?,
    val accuracyMeters: Double?,
    val altitudeMeters: Double?,
    val capturedAt: Instant,
)

data class NoteContentV2(
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val noteCreatedAt: Instant,
    val timeZoneId: String?,
    val location: NoteLocationV2?,
) : WorkspaceEntityContentV2 {
    override val entityType: WorkspaceEntityTypeV2 = WorkspaceEntityTypeV2.NOTE
}

data class NotebookContentV2(
    val title: String,
    val sortOrder: Long,
    val notebookCreatedAt: Instant,
) : WorkspaceEntityContentV2 {
    override val entityType: WorkspaceEntityTypeV2 = WorkspaceEntityTypeV2.NOTEBOOK
}

enum class WorkspaceThemeV2(val wireValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromWire(value: String): WorkspaceThemeV2? = entries.firstOrNull { it.wireValue == value }
    }
}

data class WorkspacePreferencesV2(
    val theme: WorkspaceThemeV2 = WorkspaceThemeV2.SYSTEM,
    val previewByDefault: Boolean = false,
    val markdownToolbarVisible: Boolean = true,
    val defaultNotebookId: String? = null,
) : WorkspaceEntityContentV2 {
    override val entityType: WorkspaceEntityTypeV2 = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES
}

data class WorkspaceDeletionV2(
    val deletedAt: Instant,
)

enum class WorkspaceVersionProvenanceTypeV2(val wireValue: String) {
    EPOCH_CHECKPOINT("epoch_checkpoint"),
    SOURCE_IMPORT("source_import"),
    ;

    companion object {
        fun fromWire(value: String): WorkspaceVersionProvenanceTypeV2? = entries.firstOrNull { it.wireValue == value }
    }
}

data class WorkspaceVersionProvenanceV2(
    val type: WorkspaceVersionProvenanceTypeV2,
    val sourceProfile: String? = null,
    val sourceEpoch: String? = null,
    val sourceWriterId: String? = null,
    val sourceMutationId: String? = null,
    val sourceObjectId: String? = null,
    val sourceDigest: String? = null,
)

data class WorkspaceEntityVersionV2(
    val envelopeSchemaVersion: Int = WORKSPACE_ENTITY_ENVELOPE_SCHEMA_VERSION_V2,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val syncEpochId: String,
    val versionId: String,
    val entityType: WorkspaceEntityTypeV2,
    val entitySchemaVersion: Int = WORKSPACE_ENTITY_SCHEMA_VERSION_V2,
    val entityId: String,
    val parentVersionIds: List<String>,
    val kind: WorkspaceEntityVersionKindV2,
    val contentPayload: WorkspaceEntityContentV2?,
    val deletionPayload: WorkspaceDeletionV2?,
    val provenance: WorkspaceVersionProvenanceV2?,
    val authorActorId: String,
    val authoredAt: Instant,
    val generation: Long,
    val payloadDigest: String,
    val objectDigest: String,
    val mergeAlgorithmVersion: String?,
) {
    val key: WorkspaceEntityKeyV2
        get() = WorkspaceEntityKeyV2(entityType, entityId)

    val semanticPayload: Any
        get() = contentPayload ?: checkNotNull(deletionPayload)
}

data class WorkspaceCausalEditTokenV2(
    val syncEpochId: String,
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val expectedBaseVersionId: String,
    val activeConflictId: String?,
)

enum class WorkspaceConflictReasonV2(val wireValue: String) {
    FIELD_CONFLICT("field_conflict"),
    CONCURRENT_DELETE_EDIT("concurrent_delete_edit"),
    NO_USABLE_MERGE_BASE("no_usable_merge_base"),
}

enum class WorkspaceConflictLifecycleV2(val wireValue: String) {
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    RESOLVED("resolved"),
}

data class WorkspaceConflictDescriptorV2(
    val conflictId: String,
    val syncEpochId: String,
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val headVersionIds: List<String>,
    val baseVersionId: String?,
    val reason: WorkspaceConflictReasonV2,
    val conflictingFields: Set<String> = emptySet(),
)

data class WorkspaceConflictStateV2(
    val descriptor: WorkspaceConflictDescriptorV2,
    val lifecycle: WorkspaceConflictLifecycleV2,
    val supersededByConflictId: String? = null,
    val resolvedByVersionId: String? = null,
)

data class AutomaticWorkspaceVersionDraftV2(
    val syncEpochId: String,
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val parentVersionIds: List<String>,
    val kind: WorkspaceEntityVersionKindV2,
    val contentPayload: WorkspaceEntityContentV2?,
    val deletionPayload: WorkspaceDeletionV2?,
    val authoredAt: Instant,
    val generation: Long,
    val mergeAlgorithmVersion: String,
)

interface WorkspaceCausalityMaterializerV2 {
    fun materializeAutomaticVersion(draft: AutomaticWorkspaceVersionDraftV2): WorkspaceEntityVersionV2

    fun conflictId(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        sortedHeadVersionIds: List<String>,
    ): String

    fun canonicalPayloadBytes(version: WorkspaceEntityVersionV2): ByteArray
}

sealed interface WorkspaceReconciliationOutcomeV2 {
    data class Projected(val headVersionId: String) : WorkspaceReconciliationOutcomeV2

    data class Conflict(val descriptor: WorkspaceConflictDescriptorV2) : WorkspaceReconciliationOutcomeV2
}

data class WorkspaceReconciliationPlanV2(
    val key: WorkspaceEntityKeyV2,
    val generatedVersions: List<WorkspaceEntityVersionV2>,
    val finalHeadVersionIds: List<String>,
    val outcome: WorkspaceReconciliationOutcomeV2,
    val conflictStates: List<WorkspaceConflictStateV2>,
)

enum class WorkspaceCausalityErrorCodeV2(val wireValue: String) {
    EMPTY_GRAPH("empty_graph"),
    CONTRACT_MISMATCH("contract_mismatch"),
    SCHEMA_SET_MISMATCH("schema_set_mismatch"),
    EPOCH_MISMATCH("epoch_mismatch"),
    ENTITY_MISMATCH("entity_mismatch"),
    INVALID_SINGLETON_ENTITY("invalid_singleton_entity"),
    DUPLICATE_VERSION_ID("duplicate_version_id"),
    IMMUTABLE_OBJECT_MISMATCH("immutable_object_mismatch"),
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version"),
    INVALID_IDENTIFIER("invalid_identifier"),
    INVALID_DIGEST("invalid_digest"),
    INVALID_PAYLOAD("invalid_payload"),
    ENVELOPE_TOO_LARGE("envelope_too_large"),
    INVALID_PARENT_SET("invalid_parent_set"),
    MISSING_PARENT("missing_parent"),
    CROSS_ENTITY_PARENT("cross_entity_parent"),
    CYCLIC_GRAPH("cyclic_graph"),
    INVALID_GENERATION("invalid_generation"),
    INVALID_PROVENANCE("invalid_provenance"),
    INVALID_AUTHOR_ACTOR("invalid_author_actor"),
    INVALID_TRANSITION("invalid_transition"),
    INVALID_AUTOMATIC_VERSION("invalid_automatic_version"),
    PAYLOAD_DIGEST_COLLISION("payload_digest_collision"),
    INVALID_CONFLICT_DESCRIPTOR("invalid_conflict_descriptor"),
    STALE_CONFLICT("stale_conflict"),
    EDIT_TOKEN_MISMATCH("edit_token_mismatch"),
    STALE_EDIT_BASE_MISSING("stale_edit_base_missing"),
}

data class WorkspaceCausalityErrorV2(
    val code: WorkspaceCausalityErrorCodeV2,
    val versionId: String? = null,
    val relatedId: String? = null,
    val safeMessage: String,
)

sealed interface WorkspaceReconciliationResultV2 {
    data class Reconciled(val plan: WorkspaceReconciliationPlanV2) : WorkspaceReconciliationResultV2

    data class InvalidGraph(val errors: List<WorkspaceCausalityErrorV2>) : WorkspaceReconciliationResultV2 {
        init {
            require(errors.isNotEmpty())
        }
    }
}

internal fun WorkspaceEntityVersionV2.payloadForDigest(): Any =
    when (kind) {
        WorkspaceEntityVersionKindV2.CONTENT -> checkNotNull(contentPayload)
        WorkspaceEntityVersionKindV2.DELETION -> checkNotNull(deletionPayload)
    }

internal fun NoteLocationV2.normalized(): NoteLocationV2 = copy(
    latitude = latitude?.let(::normalizeNegativeZero),
    longitude = longitude?.let(::normalizeNegativeZero),
    accuracyMeters = accuracyMeters?.let(::normalizeNegativeZero),
    altitudeMeters = altitudeMeters?.let(::normalizeNegativeZero),
    placeText = placeText?.takeUnless { it.isBlank() },
)

internal fun NoteContentV2.normalized(): NoteContentV2 = copy(
    timeZoneId = timeZoneId?.trim()?.takeUnless { it.isBlank() },
    location = location?.normalized(),
)
