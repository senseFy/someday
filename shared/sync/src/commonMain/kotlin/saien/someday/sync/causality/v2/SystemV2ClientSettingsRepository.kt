@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.WorkspacePreferencesConflictBranch
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.domain.settings.WorkspacePreferencesConflictView
import saien.someday.domain.settings.WorkspacePreferencesSnapshot
import saien.someday.domain.settings.WorkspacePreferencesSyncState
import saien.someday.domain.settings.WorkspacePreferencesSyncStatus
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Splits a Settings save into one typed workspace-preferences mutation and a
 * device-local snapshot.  Unknown/local/security keys never enter the entity
 * encoder, and remote projection reads never create an echo mutation.
 */
class SystemV2ClientSettingsRepository(
    private val localRepository: SqlDelightLocalDataRepository,
    private val localSettings: ClientSettingsRepository,
    workspaceKeyProvider: () -> WorkspaceMasterKey?,
    writerDeviceIdProvider: () -> String,
    remoteProfileProvider: () -> String,
    private val clock: () -> Instant = { Clock.System.now() },
    private val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator? = null,
) : ClientSettingsRepository, WorkspacePreferencesConflictResolver {
    private val contexts = WorkspaceSystemV2ContextProvider(
        localRepository,
        workspaceKeyProvider,
        writerDeviceIdProvider,
        remoteProfileProvider,
    )

    private fun <T> productAccess(block: () -> T): T =
        if (authorityMutationCoordinator != null) {
            authorityMutationCoordinator.productAccess(block)
        } else {
            block()
        }

    override fun load(): ClientSettings = productAccess(::loadUncoordinated)

    private fun loadUncoordinated(): ClientSettings {
        val local = localSettings.load()
        val context = contexts.openOrNull() ?: return local.copy(
            workspacePreferencesState = WorkspacePreferencesSyncState(
                status = WorkspacePreferencesSyncStatus.Unavailable,
                warning = "The authenticated whole-product V2 epoch is not available.",
            ),
        )
        return project(context, local)
    }

    override fun save(settings: ClientSettings): ClientSettings =
        productAccess { saveUncoordinated(settings) }

    private fun saveUncoordinated(settings: ClientSettings): ClientSettings {
        // Self-hosted sign-in / mode switches must persist before the first
        // whole-product epoch exists. Until an ACTIVE+HEALTHY epoch is local,
        // keep settings on the device-local path (same as load() when openOrNull).
        val context = contexts.openOrNull()?.takeIf {
            it.lifecycle == SyncEpochLifecycleV2.ACTIVE && it.health == SyncEpochHealthV2.HEALTHY
        } ?: return localSettings.save(settings)
        val viewState = settings.workspacePreferencesState
        val displayed = viewState.displayedSnapshot ?: loadUncoordinated().toPreferenceSnapshot()
        val requested = settings.toPreferenceSnapshot()
        val changedFields = preferenceChangedFields(displayed, requested)
        val activeConflict = context.store.loadConflicts(preferenceKeyV2())
            .singleOrNull { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }
        if (changedFields.isNotEmpty() && activeConflict != null) {
            error("Resolve the active workspace-preferences conflict before changing synchronized preferences.")
        }

        var result: ClientSettings? = null
        localRepository.database.transaction {
            if (changedFields.isNotEmpty()) {
                val token = viewState.causalToken
                    ?: error("Reload Settings before changing synchronized V2 preferences.")
                val base = viewState.baseSnapshot
                    ?: error("The exact Settings causal base is unavailable.")
                val next = applyPreferenceChanges(base, displayed, requested)
                val now = clock()
                val created = context.factory.createFromToken(
                    token = token.toWorkspaceTokenV2(),
                    retainedVersions = context.store.loadAllVersions().associateBy { it.versionId },
                    content = next.toWirePreferences(),
                    deletedAt = null,
                    deviceActorId = context.deviceActorId,
                    authoredAt = now,
                ) as? TokenBasedVersionResultV2.Created
                    ?: error("The exact Settings edit base is stale; reload without discarding local choices.")
                when (val committed = context.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        remoteProfile = context.remoteProfile,
                        mutationId = created.mutationId,
                        version = created.version,
                        createdAt = now,
                    ),
                ))) {
                    is WorkspaceLocalCommitResultV2.Committed,
                    is WorkspaceLocalCommitResultV2.AlreadyCommitted,
                    -> Unit
                    is WorkspaceLocalCommitResultV2.Rejected -> error(committed.error.safeMessage)
                }
            }

            // This is deliberately the internal non-dirty projection/local
            // path.  It cannot recurse into this user mutation repository.
            val projected = project(context, settings)
            localSettings.saveLocalSnapshot(projected.copy(workspacePreferencesState = WorkspacePreferencesSyncState()))
            result = project(context, localSettings.load())
        }
        return checkNotNull(result)
    }

    override fun saveLocalSnapshot(settings: ClientSettings): ClientSettings =
        productAccess { saveLocalSnapshotUncoordinated(settings) }

    private fun saveLocalSnapshotUncoordinated(settings: ClientSettings): ClientSettings {
        localSettings.saveLocalSnapshot(settings.copy(workspacePreferencesState = WorkspacePreferencesSyncState()))
        return loadUncoordinated()
    }

    override fun resolveWorkspacePreferencesBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): ClientSettings =
        productAccess {
            resolveWorkspacePreferencesBranchUncoordinated(conflictId, selectedVersionId, expectedHeadVersionIds)
        }

    private fun resolveWorkspacePreferencesBranchUncoordinated(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): ClientSettings {
        val context = contexts.requireActive()
        val conflict = context.store.loadConflicts(preferenceKeyV2()).singleOrNull {
            it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE && it.descriptor.conflictId == conflictId
        } ?: error("The workspace-preferences conflict is no longer active.")
        require(expectedHeadVersionIds.distinct().sorted() == conflict.descriptor.headVersionIds) {
            "The Settings conflict changed; reload it before resolving."
        }
        require(selectedVersionId in conflict.descriptor.headVersionIds)
        val selected = requireNotNull(context.store.loadVersion(selectedVersionId))
        val selectedContent = selected.contentPayload as? WorkspacePreferencesV2
            ?: error("Workspace preferences cannot resolve to deletion.")
        val now = clock()
        val chain = context.factory.createManualResolutionChain(
            parents = conflict.descriptor.headVersionIds.map { requireNotNull(context.store.loadVersion(it)) },
            selectedContent = selectedContent,
            selectedDeletion = null,
            deviceActorId = context.deviceActorId,
            authoredAt = now,
        )
        localRepository.database.transaction {
            when (val committed = context.store.commitLocalMutations(chain.map { version ->
                LocalWorkspaceMutationV2(
                    context.remoteProfile,
                    context.factory.newMutationId(),
                    version,
                    now,
                )
            })) {
                is WorkspaceLocalCommitResultV2.Committed,
                is WorkspaceLocalCommitResultV2.AlreadyCommitted,
                -> Unit
                is WorkspaceLocalCommitResultV2.Rejected -> error(committed.error.safeMessage)
            }
            val projected = project(context, localSettings.load())
            localSettings.saveLocalSnapshot(projected.copy(workspacePreferencesState = WorkspacePreferencesSyncState()))
        }
        return loadUncoordinated()
    }

    private fun project(context: ActiveWorkspaceSystemV2, local: ClientSettings): ClientSettings {
        val key = preferenceKeyV2()
        val projection = context.store.loadProjection(key) ?: return local.copy(
            workspacePreferencesState = WorkspacePreferencesSyncState(
                status = WorkspacePreferencesSyncStatus.Unavailable,
                warning = "The V2 epoch is invalid because its workspace-preferences root is missing.",
            ),
        )
        val conflict = context.store.loadConflicts(key)
            .singleOrNull { it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE }
        if (conflict != null) {
            val displayed = local.toPreferenceSnapshot()
            return local.copy(
                workspacePreferencesState = WorkspacePreferencesSyncState(
                    status = WorkspacePreferencesSyncStatus.Conflict,
                    warning = "Synchronized preferences are conflicted; device-local controls remain available.",
                    conflict = WorkspacePreferencesConflictView(
                        conflictId = conflict.descriptor.conflictId,
                        expectedHeadVersionIds = conflict.descriptor.headVersionIds,
                        conflictingFields = conflict.descriptor.conflictingFields,
                        branches = conflict.descriptor.headVersionIds.map { id ->
                            val payload = requireNotNull(context.store.loadVersion(id)).contentPayload as WorkspacePreferencesV2
                            payload.toConflictBranch(id)
                        },
                    ),
                    displayedSnapshot = displayed,
                ),
            )
        }

        val head = projection.preferredHeadVersionId?.let(context.store::loadVersion)
            ?: error("A non-conflicted preference projection must retain one head.")
        val payload = head.contentPayload as? WorkspacePreferencesV2
            ?: error("Workspace preferences cannot project deletion.")
        val base = payload.toDomainSnapshot()
        val displayed = base.copy(defaultNotebookId = projection.effectiveEntityId)
        val pending = context.store.loadPending(context.remoteProfile).any { it.objectId == head.versionId }
        return local.copy(
            theme = displayed.theme,
            editorPreferences = EditorPreferences(
                displayed.previewByDefault,
                displayed.markdownToolbarVisible,
            ),
            defaultNotebookId = displayed.defaultNotebookId,
            workspacePreferencesState = WorkspacePreferencesSyncState(
                status = when {
                    pending -> WorkspacePreferencesSyncStatus.Pending
                    projection.warning != null -> WorkspacePreferencesSyncStatus.Warning
                    else -> WorkspacePreferencesSyncStatus.Synced
                },
                causalToken = CausalEditToken(
                    syncEpochId = context.syncEpochId,
                    entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES.wireValue,
                    entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    expectedBaseVersionId = head.versionId,
                ),
                warning = projection.warning,
                baseSnapshot = base,
                displayedSnapshot = displayed,
            ),
        )
    }
}

private fun preferenceKeyV2() = WorkspaceEntityKeyV2(
    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
)

private fun ClientSettings.toPreferenceSnapshot() = WorkspacePreferencesSnapshot(
    theme,
    editorPreferences.previewByDefault,
    editorPreferences.markdownToolbarVisible,
    defaultNotebookId,
)

private fun WorkspacePreferencesV2.toDomainSnapshot() = WorkspacePreferencesSnapshot(
    theme = when (theme) {
        WorkspaceThemeV2.SYSTEM -> ClientTheme.System
        WorkspaceThemeV2.LIGHT -> ClientTheme.Light
        WorkspaceThemeV2.DARK -> ClientTheme.Dark
    },
    previewByDefault = previewByDefault,
    markdownToolbarVisible = markdownToolbarVisible,
    defaultNotebookId = defaultNotebookId,
)

private fun WorkspacePreferencesSnapshot.toWirePreferences() = WorkspacePreferencesV2(
    theme = when (theme) {
        ClientTheme.System -> WorkspaceThemeV2.SYSTEM
        ClientTheme.Light -> WorkspaceThemeV2.LIGHT
        ClientTheme.Dark -> WorkspaceThemeV2.DARK
    },
    previewByDefault = previewByDefault,
    markdownToolbarVisible = markdownToolbarVisible,
    defaultNotebookId = defaultNotebookId,
)

private fun WorkspacePreferencesV2.toConflictBranch(versionId: String) = WorkspacePreferencesConflictBranch(
    versionId = versionId,
    theme = toDomainSnapshot().theme,
    previewByDefault = previewByDefault,
    markdownToolbarVisible = markdownToolbarVisible,
    defaultNotebookId = defaultNotebookId,
)

private fun CausalEditToken.toWorkspaceTokenV2() = WorkspaceCausalEditTokenV2(
    syncEpochId,
    WorkspaceEntityTypeV2.fromWire(entityType) ?: error("Unsupported Settings edit token."),
    entityId,
    expectedBaseVersionId,
    activeConflictId,
)

private fun preferenceChangedFields(
    before: WorkspacePreferencesSnapshot,
    after: WorkspacePreferencesSnapshot,
): Set<String> = buildSet {
    if (before.theme != after.theme) add("theme")
    if (before.previewByDefault != after.previewByDefault) add("previewByDefault")
    if (before.markdownToolbarVisible != after.markdownToolbarVisible) add("markdownToolbarVisible")
    if (before.defaultNotebookId != after.defaultNotebookId) add("defaultNotebookId")
}

private fun applyPreferenceChanges(
    base: WorkspacePreferencesSnapshot,
    displayed: WorkspacePreferencesSnapshot,
    requested: WorkspacePreferencesSnapshot,
): WorkspacePreferencesSnapshot = base.copy(
    theme = if (displayed.theme != requested.theme) requested.theme else base.theme,
    previewByDefault = if (displayed.previewByDefault != requested.previewByDefault) {
        requested.previewByDefault
    } else base.previewByDefault,
    markdownToolbarVisible = if (displayed.markdownToolbarVisible != requested.markdownToolbarVisible) {
        requested.markdownToolbarVisible
    } else base.markdownToolbarVisible,
    defaultNotebookId = if (displayed.defaultNotebookId != requested.defaultNotebookId) {
        requested.defaultNotebookId
    } else base.defaultNotebookId,
)
