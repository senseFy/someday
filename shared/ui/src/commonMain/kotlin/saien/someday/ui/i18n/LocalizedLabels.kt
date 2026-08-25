package saien.someday.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import saien.someday.domain.navigation.PrimaryTab
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.SyncMode
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.badge_conflict
import saien.someday.ui.resources.badge_pending
import saien.someday.ui.resources.badge_sync_issue
import saien.someday.ui.resources.badge_synced
import saien.someday.ui.resources.badge_syncing
import saien.someday.ui.resources.badge_syncing_details
import saien.someday.ui.resources.badge_pending_details
import saien.someday.ui.resources.conflict_action_delete_copy
import saien.someday.ui.resources.conflict_action_keep_copy
import saien.someday.ui.resources.conflict_action_merge
import saien.someday.ui.resources.conflict_action_restore_original
import saien.someday.ui.resources.settings_language_chinese
import saien.someday.ui.resources.settings_language_english
import saien.someday.ui.resources.settings_language_japanese
import saien.someday.ui.resources.settings_language_korean
import saien.someday.ui.resources.settings_language_system
import saien.someday.ui.resources.sync_mode_off
import saien.someday.ui.resources.sync_mode_self_hosted
import saien.someday.ui.resources.tab_memories
import saien.someday.ui.resources.tab_notes
import saien.someday.ui.resources.tab_settings
import saien.someday.ui.resources.theme_dark
import saien.someday.ui.resources.theme_light
import saien.someday.ui.resources.theme_system

@Composable
fun PrimaryTab.localizedLabel(): String =
    when (this) {
        PrimaryTab.Notes -> stringResource(Res.string.tab_notes)
        PrimaryTab.Memories -> stringResource(Res.string.tab_memories)
        PrimaryTab.Settings -> stringResource(Res.string.tab_settings)
    }

@Composable
fun ClientTheme.localizedLabel(): String =
    when (this) {
        ClientTheme.System -> stringResource(Res.string.theme_system)
        ClientTheme.Light -> stringResource(Res.string.theme_light)
        ClientTheme.Dark -> stringResource(Res.string.theme_dark)
    }

@Composable
fun AppLanguage.localizedLabel(): String =
    when (this) {
        AppLanguage.System -> stringResource(Res.string.settings_language_system)
        AppLanguage.English -> stringResource(Res.string.settings_language_english)
        AppLanguage.Chinese -> stringResource(Res.string.settings_language_chinese)
        AppLanguage.Korean -> stringResource(Res.string.settings_language_korean)
        AppLanguage.Japanese -> stringResource(Res.string.settings_language_japanese)
    }

@Composable
fun SyncMode.localizedLabel(): String =
    when (this) {
        SyncMode.Off -> stringResource(Res.string.sync_mode_off)
        SyncMode.SelfHosted -> stringResource(Res.string.sync_mode_self_hosted)
    }

@Composable
fun ConflictResolutionAction.localizedLabel(): String =
    when (this) {
        ConflictResolutionAction.MergeIntoOriginal -> stringResource(Res.string.conflict_action_merge)
        ConflictResolutionAction.KeepConflictCopy -> stringResource(Res.string.conflict_action_keep_copy)
        ConflictResolutionAction.RestoreOriginalFromConflict -> stringResource(Res.string.conflict_action_restore_original)
        ConflictResolutionAction.DeleteConflictCopy -> stringResource(Res.string.conflict_action_delete_copy)
    }

@Composable
fun syncBadgeShortLabel(syncBadge: NoteSyncBadge, syncInProgress: Boolean): String =
    if (syncBadge == NoteSyncBadge.Pending && syncInProgress) {
        stringResource(Res.string.badge_syncing)
    } else {
        when (syncBadge) {
            NoteSyncBadge.Synced -> stringResource(Res.string.badge_synced)
            NoteSyncBadge.Pending -> stringResource(Res.string.badge_pending)
            is NoteSyncBadge.Error -> stringResource(Res.string.badge_sync_issue)
            is NoteSyncBadge.Conflict -> stringResource(Res.string.badge_conflict)
        }
    }

@Composable
fun syncBadgeDetailsText(syncBadge: NoteSyncBadge, syncInProgress: Boolean): String? =
    if (syncBadge == NoteSyncBadge.Pending && syncInProgress) {
        stringResource(Res.string.badge_syncing_details)
    } else {
        when (syncBadge) {
            NoteSyncBadge.Pending -> stringResource(Res.string.badge_pending_details)
            is NoteSyncBadge.Error -> syncBadge.details
            is NoteSyncBadge.Conflict -> syncBadge.details
            NoteSyncBadge.Synced -> null
        }
    }
