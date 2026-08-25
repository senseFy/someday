@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
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
 * Creates the empty local DAG's initial workspace-preferences checkpoint.
 */
class WorkspaceGenesisCheckpointServiceV2(
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val remoteProfile: String,
    private val idGenerator: CausalityIdGeneratorV2 = RandomUuidCausalityIdGeneratorV2(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun inventory(): WorkspaceGenesisInventoryV2 {
        val sources = mutableListOf<WorkspaceCheckpointSourceHeadV2>()
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
                (it.message ?: "Local product state cannot be represented by the sync protocol.").take(500),
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
                    (it.message ?: "Local product state violates a sync protocol bound.").take(500),
                )
            },
        )
    }
    private val sourceProfile: String get() = "local-draft:$remoteProfile"
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
