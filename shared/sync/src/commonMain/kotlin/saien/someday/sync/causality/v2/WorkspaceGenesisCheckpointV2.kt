@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.EntityType
import saien.someday.data.local.Note
import saien.someday.data.local.NoteLocation
import saien.someday.data.local.Notebook
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.Tombstone
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import kotlin.time.Clock
import kotlin.time.Instant

data class WorkspaceGenesisInventoryV2(
    val sourceHeads: List<WorkspaceCheckpointSourceHeadV2>,
)

sealed interface WorkspaceGenesisCheckpointResultV2 {
    data class Prepared(
        val checkpoint: PreparedWorkspaceEpochCheckpointV2,
        val inventory: WorkspaceGenesisInventoryV2,
    ) : WorkspaceGenesisCheckpointResultV2

    data class Blocked(
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspaceGenesisCheckpointResultV2
}

/**
 * Captures the complete device-local product snapshot used to initialize an
 * empty V2 workspace or preserve a joining device's pre-sync notes.
 *
 * This boundary intentionally knows nothing about any retired sync protocol:
 * local product rows are inputs, while the resulting checkpoint is the first
 * and only remote authority.
 */
class WorkspaceGenesisCheckpointServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val remoteProfile: String,
    private val idGenerator: CausalityIdGeneratorV2 = RandomUuidCausalityIdGeneratorV2(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun inventory(): WorkspaceGenesisInventoryV2 {
        val sources = mutableListOf<WorkspaceCheckpointSourceHeadV2>()
        localRepository.listActiveNotebooks().forEach { sources += it.toCheckpointSource() }
        localRepository.listAllTombstones()
            .filter { it.entityType == EntityType.NOTEBOOK }
            .forEach { sources += it.toNotebookCheckpointSource() }
        localRepository.listAllActiveNotes().forEach { note ->
            sources += note.toCheckpointSource(localRepository.getLocation(note.id)?.toV2())
        }
        localRepository.listAllTombstones()
            .filter { it.entityType == EntityType.NOTE }
            .forEach { sources += it.toNoteCheckpointSource() }

        val preferences = settingsRepository.load().toWorkspacePreferencesV2()
        sources += WorkspaceCheckpointSourceHeadV2(
            entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            content = preferences,
            deletion = null,
            sourceProfile = sourceProfile,
            sourceEpoch = null,
            sourceWriterId = writerDeviceId,
            sourceMutationId = null,
            sourceObjectId = "workspace-preferences:$writerDeviceId",
            sourceObjectDigest = preferences.stableSourceDigestV2(),
            sourceAuthoredAt = clock(),
        )
        return WorkspaceGenesisInventoryV2(
            sources.distinctBy { Triple(it.entityType, it.entityId, it.sourceObjectId) }
                .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2),
        )
    }

    fun prepare(): WorkspaceGenesisCheckpointResultV2 {
        val inventory = runCatching(::inventory).getOrElse {
            return WorkspaceGenesisCheckpointResultV2.Blocked(
                "genesis_snapshot_invalid",
                (it.message ?: "Local product state cannot be represented by Sync V2.").take(500),
            )
        }
        return runCatching {
            WorkspaceCheckpointBuilderV2(workspaceKey, writerDeviceId, idGenerator).build(
                remoteProfile = remoteProfile,
                sourceHeads = inventory.sourceHeads,
                createdAt = clock(),
            )
        }.fold(
            onSuccess = { WorkspaceGenesisCheckpointResultV2.Prepared(it, inventory) },
            onFailure = {
                WorkspaceGenesisCheckpointResultV2.Blocked(
                    "genesis_checkpoint_invalid",
                    (it.message ?: "Local product state violates a Sync V2 bound.").take(500),
                )
            },
        )
    }

    private val sourceProfile: String get() = "local-product:$remoteProfile"

    private fun Notebook.toCheckpointSource() = WorkspaceCheckpointSourceHeadV2(
        entityType = WorkspaceEntityTypeV2.NOTEBOOK,
        entityId = id,
        content = NotebookContentV2(title, sortOrder, createdAt),
        deletion = null,
        sourceProfile = sourceProfile,
        sourceEpoch = null,
        sourceWriterId = writerDeviceId,
        sourceMutationId = null,
        sourceObjectId = "notebook:$id:$revision",
        sourceObjectDigest = "notebook:$contentHash",
        sourceAuthoredAt = updatedAt,
    )

    private fun Tombstone.toNotebookCheckpointSource() = WorkspaceCheckpointSourceHeadV2(
        entityType = WorkspaceEntityTypeV2.NOTEBOOK,
        entityId = entityId,
        content = null,
        deletion = WorkspaceDeletionV2(deletedAt),
        sourceProfile = sourceProfile,
        sourceEpoch = null,
        sourceWriterId = writerDeviceId,
        sourceMutationId = null,
        sourceObjectId = "notebook-deletion:$entityId:${deletedAt.epochSeconds}:${deletedAt.nanosecondsOfSecond}",
        sourceObjectDigest = "notebook-deletion:$lastKnownRevision",
        sourceAuthoredAt = deletedAt,
    )

    private fun Note.toCheckpointSource(location: NoteLocationV2?) = WorkspaceCheckpointSourceHeadV2(
        entityType = WorkspaceEntityTypeV2.NOTE,
        entityId = id,
        content = NoteContentV2(notebookId, title, markdownBody, createdAt, timeZoneId, location),
        deletion = null,
        sourceProfile = sourceProfile,
        sourceEpoch = null,
        sourceWriterId = writerDeviceId,
        sourceMutationId = null,
        sourceObjectId = currentVersionId ?: "note:$id:$revision",
        sourceObjectDigest = "note:$contentHash:${location?.stableSourceDigestV2().orEmpty()}",
        sourceAuthoredAt = updatedAt,
    )

    private fun Tombstone.toNoteCheckpointSource() = WorkspaceCheckpointSourceHeadV2(
        entityType = WorkspaceEntityTypeV2.NOTE,
        entityId = entityId,
        content = null,
        deletion = WorkspaceDeletionV2(deletedAt),
        sourceProfile = sourceProfile,
        sourceEpoch = null,
        sourceWriterId = writerDeviceId,
        sourceMutationId = null,
        sourceObjectId = "note-deletion:$entityId:${deletedAt.epochSeconds}:${deletedAt.nanosecondsOfSecond}",
        sourceObjectDigest = "note-deletion:$lastKnownRevision",
        sourceAuthoredAt = deletedAt,
    )
}

private fun NoteLocation.toV2() = NoteLocationV2(
    latitude, longitude, placeText, accuracyMeters, altitudeMeters, capturedAt,
)

private fun NoteLocationV2.stableSourceDigestV2(): String = buildString {
    append(latitude)
    append(':')
    append(longitude)
    append(':')
    append(placeText.orEmpty())
    append(':')
    append(accuracyMeters ?: "")
    append(':')
    append(altitudeMeters ?: "")
    append(':')
    append(capturedAt.epochSeconds)
    append(':')
    append(capturedAt.nanosecondsOfSecond)
}

internal fun ClientSettings.toWorkspacePreferencesV2() = WorkspacePreferencesV2(
    theme = when (theme) {
        ClientTheme.System -> WorkspaceThemeV2.SYSTEM
        ClientTheme.Light -> WorkspaceThemeV2.LIGHT
        ClientTheme.Dark -> WorkspaceThemeV2.DARK
    },
    previewByDefault = editorPreferences.previewByDefault,
    markdownToolbarVisible = editorPreferences.markdownToolbarVisible,
    defaultNotebookId = defaultNotebookId,
)

private fun WorkspacePreferencesV2.stableSourceDigestV2(): String = buildString {
    append("preferences:")
    append(theme.wireValue)
    append(':')
    append(previewByDefault)
    append(':')
    append(markdownToolbarVisible)
    append(':')
    append(defaultNotebookId.orEmpty())
}
