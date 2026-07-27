@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant
import okio.ByteString.Companion.toByteString

data class WorkspaceVersionIntegrityV2(
    val expectedPayloadDigest: String,
    val expectedObjectDigest: String,
    val payloadDigestMatches: Boolean,
    val objectDigestMatches: Boolean,
) {
    val isValid: Boolean
        get() = payloadDigestMatches && objectDigestMatches
}

class CanonicalWorkspaceCausalityMaterializerV2(
    private val keys: SyncEpochKeysV2,
) : WorkspaceCausalityMaterializerV2 {
    override fun materializeAutomaticVersion(draft: AutomaticWorkspaceVersionDraftV2): WorkspaceEntityVersionV2 {
        require(draft.parentVersionIds.size in 2..MAX_WORKSPACE_ENTITY_PARENTS_V2)
        require(draft.parentVersionIds == draft.parentVersionIds.distinct().sorted())
        require(draft.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2)
        val unsigned = WorkspaceEntityVersionV2(
            syncEpochId = draft.syncEpochId,
            versionId = pendingVersionId(draft),
            entityType = draft.entityType,
            entityId = draft.entityId,
            parentVersionIds = draft.parentVersionIds,
            kind = draft.kind,
            contentPayload = draft.contentPayload,
            deletionPayload = draft.deletionPayload,
            provenance = null,
            authorActorId = SYSTEM_AUTO_MERGE_ACTOR_V2,
            authoredAt = draft.authoredAt,
            generation = draft.generation,
            payloadDigest = payloadDigest(
                entityType = draft.entityType,
                kind = draft.kind,
                content = draft.contentPayload,
                deletion = draft.deletionPayload,
            ),
            objectDigest = PENDING_DIGEST_V2,
            mergeAlgorithmVersion = draft.mergeAlgorithmVersion,
        )
        val identified = unsigned.copy(versionId = automaticVersionId(unsigned))
        return identified.copy(objectDigest = objectDigest(identified))
    }

    override fun conflictId(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        sortedHeadVersionIds: List<String>,
    ): String {
        require(sortedHeadVersionIds.size >= 2)
        require(sortedHeadVersionIds == sortedHeadVersionIds.distinct().sorted())
        return "cf2_${base32LowerNoPaddingV2(hmac(CanonicalWorkspaceEntityEncodingV2.conflictIdInput(
            syncEpochId = syncEpochId,
            key = key,
            sortedHeadVersionIds = sortedHeadVersionIds,
        )))}"
    }

    override fun canonicalPayloadBytes(version: WorkspaceEntityVersionV2): ByteArray =
        CanonicalWorkspaceEntityEncodingV2.semanticPayload(
            entityType = version.entityType,
            entitySchemaVersion = version.entitySchemaVersion,
            kind = version.kind,
            content = version.contentPayload,
            deletion = version.deletionPayload,
        )

    fun payloadDigest(version: WorkspaceEntityVersionV2): String = payloadDigest(
        entityType = version.entityType,
        kind = version.kind,
        content = version.contentPayload,
        deletion = version.deletionPayload,
        entitySchemaVersion = version.entitySchemaVersion,
    )

    fun payloadDigest(
        entityType: WorkspaceEntityTypeV2,
        kind: WorkspaceEntityVersionKindV2,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        entitySchemaVersion: Int = WORKSPACE_ENTITY_SCHEMA_VERSION_V2,
    ): String = "pd2:sha256:${CanonicalWorkspaceEntityEncodingV2.semanticPayload(
        entityType = entityType,
        entitySchemaVersion = entitySchemaVersion,
        kind = kind,
        content = content,
        deletion = deletion,
    ).toByteString().sha256().hex()}"

    fun objectDigest(version: WorkspaceEntityVersionV2): String =
        "od2:hmac-sha256:${objectKeyHmac(CanonicalWorkspaceEntityEncodingV2.objectDigestInput(version)).toByteString().hex()}"

    fun validateIntegrity(version: WorkspaceEntityVersionV2): WorkspaceVersionIntegrityV2 {
        val expectedPayload = payloadDigest(version)
        val expectedObject = objectDigest(version)
        return WorkspaceVersionIntegrityV2(
            expectedPayloadDigest = expectedPayload,
            expectedObjectDigest = expectedObject,
            payloadDigestMatches = version.payloadDigest == expectedPayload,
            objectDigestMatches = version.objectDigest == expectedObject,
        )
    }

    fun sourceImportVersionId(versionWithoutIdOrObjectDigest: WorkspaceEntityVersionV2): String {
        require(versionWithoutIdOrObjectDigest.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT)
        return uuidFromHmacV2(hmac(CanonicalWorkspaceEntityEncodingV2.sourceImportVersionIdInput(
            versionWithoutIdOrObjectDigest,
        )))
    }

    fun deterministicSystemMutationId(version: WorkspaceEntityVersionV2): String =
        uuidFromHmacV2(hmac(CanonicalWorkspaceEntityEncodingV2.systemMutationIdInput(version)))

    /**
     * Recomputes identities whose randomness is deliberately replaced by a
     * protocol-owned deterministic HMAC. Ordinary/checkpoint/manual versions
     * return null because their ids are persisted CSPRNG identities.
     */
    fun expectedDeterministicVersionId(version: WorkspaceEntityVersionV2): String? = when {
        version.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2 -> automaticVersionId(version)
        version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT ->
            sourceImportVersionId(version)
        else -> null
    }

    fun expectedDeterministicMutationId(version: WorkspaceEntityVersionV2): String? =
        if (version.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2 ||
            version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT
        ) deterministicSystemMutationId(version) else null

    fun mappedSourceEntityId(
        syncEpochId: String,
        entityType: WorkspaceEntityTypeV2,
        sourceEntityId: String,
    ): String = "le2_${base32LowerNoPaddingV2(hmac(CanonicalWorkspaceEntityEncodingV2.sourceEntityIdInput(
        syncEpochId,
        entityType,
        sourceEntityId,
    )))}"

    fun fullEnvelopeBytes(version: WorkspaceEntityVersionV2): ByteArray =
        DeterministicCborV2.encode(CanonicalWorkspaceEntityEncodingV2.envelope(version, includeObjectDigest = true))

    fun envelopeWithoutObjectDigestBytes(version: WorkspaceEntityVersionV2): ByteArray =
        DeterministicCborV2.encode(CanonicalWorkspaceEntityEncodingV2.envelope(version, includeObjectDigest = false))

    private fun automaticVersionId(version: WorkspaceEntityVersionV2): String =
        uuidFromHmacV2(hmac(CanonicalWorkspaceEntityEncodingV2.automaticVersionIdInput(version)))

    private fun pendingVersionId(@Suppress("UNUSED_PARAMETER") draft: AutomaticWorkspaceVersionDraftV2): String =
        // The id input does not include the envelope's versionId. A stable
        // placeholder keeps construction explicit until payloadDigest exists.
        "00000000-0000-4000-8000-000000000000"

    private fun hmac(bytes: ByteArray): ByteArray =
        bytes.toByteString().hmacSha256(keys.convergenceKey.toByteString()).toByteArray()

    internal fun objectKeyHmac(bytes: ByteArray): ByteArray =
        bytes.toByteString().hmacSha256(keys.objectDigestKey.toByteString()).toByteArray()

    companion object {
        private const val PENDING_DIGEST_V2: String = "pending"
    }
}

internal object CanonicalWorkspaceEntityEncodingV2 {
    fun semanticPayload(
        entityType: WorkspaceEntityTypeV2,
        entitySchemaVersion: Int,
        kind: WorkspaceEntityVersionKindV2,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
    ): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-payload-digest-v2"),
            "entitySchemaVersion" to cborInt(entitySchemaVersion.toLong()),
            "entityType" to cborText(entityType.wireValue),
            "kind" to cborText(kind.wireValue),
            "payload" to payloadValue(entityType, kind, content, deletion),
        ),
    )

    fun objectDigestInput(version: WorkspaceEntityVersionV2): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-object-digest-v2"),
            "envelope" to envelope(version, includeObjectDigest = false),
        ),
    )

    fun envelope(
        version: WorkspaceEntityVersionV2,
        includeObjectDigest: Boolean,
    ): CborValueV2.Map {
        val fields = mutableListOf<Pair<String, CborValueV2>>()
        fields += "authoredAt" to version.authoredAt.toCborInstantV2()
        fields += "authorActorId" to cborText(version.authorActorId)
        fields += "contentPayload" to (version.contentPayload?.toCborV2() ?: CborValueV2.Null)
        fields += "contractId" to cborText(version.contractId)
        fields += "deletionPayload" to (version.deletionPayload?.toCborV2() ?: CborValueV2.Null)
        fields += "entityId" to cborText(version.entityId)
        fields += "entitySchemaVersion" to cborInt(version.entitySchemaVersion.toLong())
        fields += "entityType" to cborText(version.entityType.wireValue)
        fields += "envelopeSchemaVersion" to cborInt(version.envelopeSchemaVersion.toLong())
        fields += "generation" to cborInt(version.generation)
        fields += "kind" to cborText(version.kind.wireValue)
        fields += "mergeAlgorithmVersion" to cborNullableText(version.mergeAlgorithmVersion)
        if (includeObjectDigest) fields += "objectDigest" to cborText(version.objectDigest)
        fields += "parentVersionIds" to cborArray(version.parentVersionIds.map(::cborText))
        fields += "payloadDigest" to cborText(version.payloadDigest)
        fields += "provenance" to version.provenance.toCborV2()
        fields += "schemaSetVersion" to cborText(version.schemaSetVersion)
        fields += "syncEpochId" to cborText(version.syncEpochId)
        fields += "versionId" to cborText(version.versionId)
        return CborValueV2.Map(fields.map { (name, value) -> cborText(name) to value })
    }

    fun automaticVersionIdInput(version: WorkspaceEntityVersionV2): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-automatic-version-id-v2"),
            "syncEpochId" to cborText(version.syncEpochId),
            "entityType" to cborText(version.entityType.wireValue),
            "entityId" to cborText(version.entityId),
            "outputKind" to cborText(version.kind.wireValue),
            "mergeAlgorithmVersion" to cborText(checkNotNull(version.mergeAlgorithmVersion)),
            "parentVersionIds" to cborArray(version.parentVersionIds.map(::cborText)),
            "payloadDigest" to cborText(version.payloadDigest),
        ),
    )

    fun conflictIdInput(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        sortedHeadVersionIds: List<String>,
    ): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-conflict-id-v2"),
            "syncEpochId" to cborText(syncEpochId),
            "entityType" to cborText(key.entityType.wireValue),
            "entityId" to cborText(key.entityId),
            "headVersionIds" to cborArray(sortedHeadVersionIds.map(::cborText)),
        ),
    )

    fun sourceImportVersionIdInput(version: WorkspaceEntityVersionV2): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-source-import-version-id-v2"),
            "syncEpochId" to cborText(version.syncEpochId),
            "entityType" to cborText(version.entityType.wireValue),
            "entityId" to cborText(version.entityId),
            "provenance" to version.provenance.toCborV2(),
            "outputKind" to cborText(version.kind.wireValue),
            "parentVersionIds" to cborArray(version.parentVersionIds.map(::cborText)),
            "payloadDigest" to cborText(version.payloadDigest),
        ),
    )

    fun systemMutationIdInput(version: WorkspaceEntityVersionV2): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-system-mutation-id-v2"),
            "syncEpochId" to cborText(version.syncEpochId),
            "objectId" to cborText(version.versionId),
            "objectDigest" to cborText(version.objectDigest),
        ),
    )

    fun sourceEntityIdInput(
        syncEpochId: String,
        entityType: WorkspaceEntityTypeV2,
        sourceEntityId: String,
    ): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "domain" to cborText("someday-system-v2-source-entity-id-v2"),
            "syncEpochId" to cborText(syncEpochId),
            "entityType" to cborText(entityType.wireValue),
            "sourceEntityId" to cborText(sourceEntityId),
        ),
    )

    private fun payloadValue(
        entityType: WorkspaceEntityTypeV2,
        kind: WorkspaceEntityVersionKindV2,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
    ): CborValueV2 = when (kind) {
        WorkspaceEntityVersionKindV2.CONTENT -> {
            require(content != null && deletion == null && content.entityType == entityType)
            content.toCborV2()
        }
        WorkspaceEntityVersionKindV2.DELETION -> {
            require(content == null && deletion != null)
            deletion.toCborV2()
        }
    }
}

internal fun WorkspaceEntityContentV2.toCborV2(): CborValueV2.Map = when (this) {
    is NoteContentV2 -> cborMap(
        "location" to (location?.toCborV2() ?: CborValueV2.Null),
        "markdownBody" to cborText(markdownBody),
        "noteCreatedAt" to noteCreatedAt.toCborInstantV2(),
        "notebookId" to cborText(notebookId),
        "timeZoneId" to cborNullableText(timeZoneId),
        "title" to cborText(title),
    )
    is NotebookContentV2 -> cborMap(
        "notebookCreatedAt" to notebookCreatedAt.toCborInstantV2(),
        "sortOrder" to cborInt(sortOrder),
        "title" to cborText(title),
    )
    is WorkspacePreferencesV2 -> cborMap(
        "defaultNotebookId" to cborNullableText(defaultNotebookId),
        "markdownToolbarVisible" to cborBoolean(markdownToolbarVisible),
        "previewByDefault" to cborBoolean(previewByDefault),
        "theme" to cborText(theme.wireValue),
    )
}

internal fun NoteLocationV2.toCborV2(): CborValueV2.Map = cborMap(
    "accuracyMeters" to accuracyMeters.toNullableCborFloat64V2(),
    "altitudeMeters" to altitudeMeters.toNullableCborFloat64V2(),
    "capturedAt" to capturedAt.toCborInstantV2(),
    "latitude" to latitude.toNullableCborFloat64V2(),
    "longitude" to longitude.toNullableCborFloat64V2(),
    "placeText" to cborNullableText(placeText),
)

internal fun WorkspaceDeletionV2.toCborV2(): CborValueV2.Map =
    cborMap("deletedAt" to deletedAt.toCborInstantV2())

internal fun WorkspaceVersionProvenanceV2?.toCborV2(): CborValueV2 = when (this) {
    null -> CborValueV2.Null
    else -> cborMap(
        "sourceDigest" to cborNullableText(sourceDigest),
        "sourceEpoch" to cborNullableText(sourceEpoch),
        "sourceMutationId" to cborNullableText(sourceMutationId),
        "sourceObjectId" to cborNullableText(sourceObjectId),
        "sourceProfile" to cborNullableText(sourceProfile),
        "sourceWriterId" to cborNullableText(sourceWriterId),
        "type" to cborText(type.wireValue),
    )
}

internal fun Instant.toCborInstantV2(): CborValueV2.Array = cborArray(
    listOf(cborInt(epochSeconds), cborInt(nanosecondsOfSecond.toLong())),
) as CborValueV2.Array

private fun Double?.toNullableCborFloat64V2(): CborValueV2 =
    this?.let(::cborFloat64) ?: CborValueV2.Null

internal fun uuidFromHmacV2(hmac: ByteArray): String {
    require(hmac.size >= 16)
    val bytes = hmac.copyOfRange(0, 16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal fun base32LowerNoPaddingV2(bytes: ByteArray): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    val output = StringBuilder((bytes.size * 8 + 4) / 5)
    var buffer = 0
    var bufferedBits = 0
    bytes.forEach { byte ->
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bufferedBits += 8
        while (bufferedBits >= 5) {
            bufferedBits -= 5
            output.append(alphabet[(buffer shr bufferedBits) and 0x1f])
        }
    }
    if (bufferedBits > 0) output.append(alphabet[(buffer shl (5 - bufferedBits)) and 0x1f])
    return output.toString()
}

internal val PAYLOAD_DIGEST_PATTERN_SYSTEM_V2 = Regex("^pd2:sha256:[0-9a-f]{64}$")
internal val OBJECT_DIGEST_PATTERN_SYSTEM_V2 = Regex("^od2:hmac-sha256:[0-9a-f]{64}$")
internal val CONTROL_DIGEST_PATTERN_SYSTEM_V2 = Regex("^cd2:hmac-sha256:[0-9a-f]{64}$")
internal val CIPHERTEXT_DIGEST_PATTERN_SYSTEM_V2 = Regex("^ct2:sha256:[0-9a-f]{64}$")
internal val UUID_V4_PATTERN_SYSTEM_V2 = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
internal val DEVICE_ACTOR_PATTERN_SYSTEM_V2 = Regex(
    "^device:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
internal val AUTOMATIC_MERGE_ALGORITHMS_V2 = setOf(
    EQUIVALENT_MERGE_ALGORITHM_V2,
    DELETION_MERGE_ALGORITHM_V2,
    FIELD_MERGE_ALGORITHM_V2,
)
internal val ALL_MERGE_ALGORITHMS_SYSTEM_V2 = AUTOMATIC_MERGE_ALGORITHMS_V2 +
    setOf(null, MANUAL_RESOLUTION_ALGORITHM_V2)
