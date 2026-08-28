@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.composables.icons.lucide.Bold
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.CalendarDays
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Heading
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Image as ImageIcon
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.LockKeyhole
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.NotebookTabs
import com.composables.icons.lucide.NotebookText
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Quote
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.X
import com.composables.icons.lucide.List as ListIcon
import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel
import saien.someday.domain.location.LocationCaptureAdapter
import saien.someday.domain.location.UnavailableLocationCaptureAdapter
import saien.someday.domain.navigation.PrimaryTab
import saien.someday.domain.navigation.primaryNavigationTabs
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.DeletedWorkspaceItem
import saien.someday.domain.notes.DeletedWorkspaceItemType
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notes.noteCalendarDate
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.OnThisDayNotificationTimeFormatter
import saien.someday.domain.notifications.TwentyFourHourOnThisDayNotificationTimeFormatter
import saien.someday.domain.notifications.UnavailableOnThisDayNotificationScheduler
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.domain.settings.WorkspacePreferencesSyncStatus
import saien.someday.ui.designsystem.SomedayDesignDefaults
import saien.someday.ui.designsystem.SomedayLiquidGlassActionGroup
import saien.someday.ui.designsystem.SomedayLiquidGlassBackdrop
import saien.someday.ui.designsystem.SomedayLiquidGlassBottomBar
import saien.someday.ui.designsystem.SomedayLiquidGlassFloatingActionButton
import saien.someday.ui.designsystem.SomedayLiquidGlassGroupedIconButton
import saien.someday.ui.designsystem.SomedayLiquidGlassHost
import saien.someday.ui.designsystem.SomedayLiquidGlassIconButton
import saien.someday.ui.designsystem.SomedayLiquidGlassNavigationItem
import saien.someday.ui.designsystem.SomedayLiquidGlassTopBar
import saien.someday.ui.i18n.AppLocaleEnvironment
import saien.someday.ui.i18n.localizedLabel
import saien.someday.ui.i18n.rememberMemoriesUiStrings
import saien.someday.ui.i18n.rememberNotesUiStrings
import saien.someday.ui.i18n.rememberSettingsUiStrings
import saien.someday.ui.i18n.syncBadgeDetailsText
import saien.someday.ui.i18n.syncBadgeShortLabel
import saien.someday.ui.settings.UnavailableWorkspacePairingScanner
import saien.someday.ui.settings.WorkspacePairingScanner
import saien.someday.ui.memories.MemoriesUiController
import saien.someday.ui.memories.MemoriesUiState
import saien.someday.ui.memories.MemoryCalendarDay
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.notes.InMemoryNotesRepository
import saien.someday.ui.notes.MarkdownEditSpanKind
import saien.someday.ui.notes.MarkdownInlineKind
import saien.someday.ui.notes.MarkdownPreviewBlock
import saien.someday.ui.notes.MarkdownPreviewInline
import saien.someday.ui.notes.MarkdownToolbarAction
import saien.someday.ui.notes.markdownEditPreviewSpans
import saien.someday.ui.notes.MockContentResult
import saien.someday.ui.notes.NoteEditorState
import saien.someday.ui.notes.NotesUiController
import saien.someday.ui.notes.NotesUiState
import saien.someday.ui.notes.markdownToolbarActions
import saien.someday.ui.notes.noteEditorMarkdownFieldValue
import saien.someday.ui.notes.renderMarkdownPreview
import saien.someday.ui.notes.syncedWithEditorMarkdown
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.*
import saien.someday.ui.resources.on_this_day_feedback_disabled
import saien.someday.ui.resources.on_this_day_feedback_enabled
import saien.someday.ui.resources.on_this_day_feedback_invalid_time
import saien.someday.ui.resources.on_this_day_feedback_permission_required
import saien.someday.ui.resources.on_this_day_feedback_time_updated
import saien.someday.ui.resources.on_this_day_feedback_unavailable
import saien.someday.ui.resources.on_this_day_settings_navigation_disabled
import saien.someday.ui.resources.on_this_day_settings_navigation_enabled
import saien.someday.ui.resources.on_this_day_settings_navigation_title
import saien.someday.ui.resources.on_this_day_settings_section_title
import saien.someday.ui.resources.on_this_day_settings_time_cancel
import saien.someday.ui.resources.on_this_day_settings_time_save
import saien.someday.ui.resources.on_this_day_settings_time_title
import saien.someday.ui.resources.on_this_day_settings_toggle_description
import saien.someday.ui.resources.on_this_day_settings_toggle_title
import saien.someday.ui.resources.sync_status_syncing
import saien.someday.ui.settings.AppliedTheme
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.OnThisDayNotificationStrings
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsFeedbackSeverity
import saien.someday.ui.settings.SettingsImportSummary
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.SettingsUiState
import saien.someday.ui.settings.SyncAccountFormMode
import saien.someday.ui.settings.SyncConnectionUi
import saien.someday.ui.settings.SyncIssueAction
import saien.someday.ui.settings.SyncIssueReason
import saien.someday.ui.settings.SyncUiOperation
import saien.someday.ui.settings.accountFormMode
import saien.someday.ui.settings.resolveAppliedTheme
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SomedayApp(
    platformName: String = "shared",
    windowChromeTopInset: Dp = 0.dp,
    developerOptionsEnabled: Boolean = false,
    initialSettings: ClientSettings = ClientSettings(),
    notesRepository: NotesRepository? = null,
    locationCaptureAdapter: LocationCaptureAdapter = UnavailableLocationCaptureAdapter,
    mediaUiPorts: MediaUiPorts = MediaUiPorts(),
    onThisDayNotificationScheduler: OnThisDayNotificationScheduler = UnavailableOnThisDayNotificationScheduler,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter =
        TwentyFourHourOnThisDayNotificationTimeFormatter,
    pendingOpenMemories: Boolean = false,
    onPendingOpenMemoriesConsumed: () -> Unit = {},
    onSettingsChanged: (ClientSettings) -> ClientSettings = { it },
    workspacePreferencesConflictResolver: WorkspacePreferencesConflictResolver? = null,
    onAppliedSettingsChanged: (ClientSettings) -> Unit = {},
    onLocalExport: () -> SettingsExportSummary = { SettingsExportSummary.unavailable() },
    dayOneImportRunner: DayOneImportRunner = DayOneImportRunner { onResult ->
        onResult(SettingsImportSummary.unavailable("Day One import is unavailable in this build."))
    },
    selfHostedSetupClient: SelfHostedSetupClient? = null,
    selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore? = null,
    manualSyncRunner: ManualSyncRunner? = null,
    workspacePairingInvitationCreator: WorkspacePairingInvitationCreator? = null,
    workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner? = null,
    workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller? = null,
    workspacePairingScanner: WorkspacePairingScanner = UnavailableWorkspacePairingScanner,
    appDispatchers: AppDispatchers = AppDispatchers(),
    foregroundSyncSignal: Int = 0,
    pullToRefreshSyncEnabled: Boolean = true,
    startupTrace: ((String) -> Unit)? = null,
) {
    var appSettings by remember { mutableStateOf(initialSettings) }
    remember {
        startupTrace?.invoke("SomedayApp.composition.start")
        Unit
    }

    AppLocaleEnvironment(language = appSettings.appLanguage) {
    SomedayTheme(theme = appSettings.theme) {
        val effectiveNotesRepository = notesRepository ?: remember { InMemoryNotesRepository() }
        val notesUiStrings = rememberNotesUiStrings()
        val memoriesUiStrings = rememberMemoriesUiStrings()
        val settingsUiStrings = rememberSettingsUiStrings()
        val notesController = remember(effectiveNotesRepository, locationCaptureAdapter) {
            startupTrace?.invoke("SomedayApp.notesController.start")
            NotesUiController(
                repository = effectiveNotesRepository,
                strings = notesUiStrings,
                locationCaptureAdapter = locationCaptureAdapter,
                initialNotebookId = initialSelectedNotebookId(appSettings),
                editorPreferences = appSettings.editorPreferences,
                backgroundDispatcher = appDispatchers.background,
            ).also {
                startupTrace?.invoke("SomedayApp.notesController.end")
            }
        }
        val memoriesController = remember(effectiveNotesRepository) {
            startupTrace?.invoke("SomedayApp.memoriesController.start")
            MemoriesUiController(
                repository = effectiveNotesRepository,
                strings = memoriesUiStrings,
                backgroundDispatcher = appDispatchers.background,
            ).also {
                startupTrace?.invoke("SomedayApp.memoriesController.end")
            }
        }
        val navController = rememberNavController()
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRouteKind = currentBackStackEntry?.somedayRouteKind() ?: SomedayRouteKind.Notes
        val selectedTab = currentRouteKind.primaryTab
        val notesSearchActive = currentRouteKind.showsNotesSearch
        var notebookSheetVisible by remember { mutableStateOf(false) }
        var recentlyDeletedSheetVisible by remember { mutableStateOf(false) }
        var discardEditorOnExit by remember { mutableStateOf(false) }
        val uiCoroutineScope = rememberCoroutineScope()
        val onThisDayNotificationStrings = OnThisDayNotificationStrings(
            unavailable = stringResource(Res.string.on_this_day_feedback_unavailable),
            permissionRequired = stringResource(Res.string.on_this_day_feedback_permission_required),
            enabled = stringResource(Res.string.on_this_day_feedback_enabled),
            disabled = stringResource(Res.string.on_this_day_feedback_disabled),
            invalidTime = stringResource(Res.string.on_this_day_feedback_invalid_time),
            timeUpdated = stringResource(Res.string.on_this_day_feedback_time_updated),
        )
        val selfHostedDeviceName = when (platformName.lowercase()) {
            "android" -> stringResource(Res.string.android_device_name)
            "ios" -> stringResource(Res.string.ios_device_name)
            "desktop" -> stringResource(Res.string.desktop_device_name)
            else -> stringResource(Res.string.someday_device_name)
        }
        val settingsController = remember(
            effectiveNotesRepository,
            appDispatchers.background,
            onThisDayNotificationScheduler,
            workspacePairingInvitationCreator,
            workspacePairingInvitationJoiner,
            workspacePairingInvitationCanceller,
        ) {
            startupTrace?.invoke("SomedayApp.settingsController.start")
            SettingsUiController(
                initialSettings = appSettings,
                notebooksProvider = { notesController.state.notebooks },
                persistSettings = onSettingsChanged,
                workspacePreferencesConflictResolver = workspacePreferencesConflictResolver,
                exportProvider = onLocalExport,
                dayOneImportRunner = dayOneImportRunner,
                onDataRestored = {
                    uiCoroutineScope.launch {
                        notesController.refreshAfterSync()
                        memoriesController.refresh()
                    }
                },
                selfHostedSetupClient = selfHostedSetupClient ?: SelfHostedSetupClient { input ->
                    saien.someday.domain.settings.SelfHostedSetupResult.failure(
                        reason = SelfHostedSetupReason.Unavailable,
                        diagnosticMessage = input.redactedDescription(),
                    )
                },
                selfHostedSessionCredentialStore = selfHostedSessionCredentialStore
                    ?: saien.someday.domain.settings.UnavailableSelfHostedSessionCredentialStore,
                selfHostedDeviceName = selfHostedDeviceName,
                selfHostedDevicePlatform = platformName,
                manualSyncRunner = manualSyncRunner ?: ManualSyncRunner {
                    saien.someday.domain.settings.ManualSyncResult.failure(
                        mode = appSettings.syncConfiguration.mode,
                        reason = ManualSyncReason.Unavailable,
                    )
                },
                workspacePairingInvitationCreator =
                    workspacePairingInvitationCreator ?: WorkspacePairingInvitationCreator {
                        WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Unavailable)
                    },
                workspacePairingInvitationJoiner =
                    workspacePairingInvitationJoiner ?: WorkspacePairingInvitationJoiner {
                        WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
                    },
                workspacePairingInvitationCanceller =
                    workspacePairingInvitationCanceller ?: WorkspacePairingInvitationCanceller {
                        WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
                    },
                onThisDayNotificationScheduler = onThisDayNotificationScheduler,
                onThisDayNotificationStrings = onThisDayNotificationStrings,
                uiStrings = settingsUiStrings,
                backgroundDispatcher = appDispatchers.background,
            ).also {
                startupTrace?.invoke("SomedayApp.settingsController.end")
            }
        }
        SideEffect {
            notesController.updateLocalizedStrings(notesUiStrings)
            memoriesController.updateLocalizedStrings(memoriesUiStrings)
            settingsController.updateLocalizedStrings(
                settings = settingsUiStrings,
                notifications = onThisDayNotificationStrings,
                hostDeviceName = selfHostedDeviceName,
            )
        }
        val notesState = notesController.state
        val memoriesState = memoriesController.state
        val settingsState = settingsController.state
        var pullSyncActive by remember { mutableStateOf(false) }
        val autoSyncScheduler = remember(settingsController, uiCoroutineScope) {
            AutoSyncScheduler(
                scope = uiCoroutineScope,
                syncReady = settingsController::canRunAutomaticSync,
                syncRunning = { settingsController.state.sync.syncing },
                runSync = settingsController::runAutomaticSync,
            )
        }
        var topToast by remember { mutableStateOf<SomedayToast?>(null) }
        var developerFeedbackId by remember { mutableStateOf(50_000L) }

        DisposableEffect(autoSyncScheduler) {
            onDispose { autoSyncScheduler.cancel() }
        }

        LaunchedEffect(notesController) {
            notesController.refresh()
        }

        LaunchedEffect(settingsController) {
            settingsController.refresh()
            autoSyncScheduler.request(AutoSyncTrigger.Launch)
        }

        LaunchedEffect(settingsState.settings) {
            appSettings = settingsState.settings
            onAppliedSettingsChanged(settingsState.settings)
        }

        LaunchedEffect(settingsState.settings.editorPreferences) {
            notesController.updateEditorPreferences(settingsState.settings.editorPreferences)
        }

        LaunchedEffect(Unit) {
            startupTrace?.invoke("SomedayApp.firstCompositionCommitted")
        }

        LaunchedEffect(foregroundSyncSignal) {
            if (foregroundSyncSignal > 0) {
                settingsController.refresh()
                autoSyncScheduler.request(AutoSyncTrigger.Foreground)
            }
        }

        LaunchedEffect(notesState.localChangeEventId) {
            if (notesState.localChangeEventId > 0L) {
                autoSyncScheduler.request(AutoSyncTrigger.LocalChange)
                if (settingsState.settings.onThisDayNotifications.enabled) {
                    settingsController.rescheduleOnThisDayNotifications()
                }
            }
        }

        LaunchedEffect(settingsState.feedbackEventId) {
            val message = settingsState.feedbackMessage?.trim()
            if (settingsState.feedbackEventId > 0L && !message.isNullOrBlank()) {
                topToast = SomedayToast(
                    id = settingsState.feedbackEventId,
                    text = message,
                    status = settingsState.feedbackSeverity.toToastStatus(),
                )
            }
        }

        var notesFeedbackId by remember { mutableStateOf(10_000L) }
        LaunchedEffect(notesState.feedbackMessage) {
            val message = notesState.feedbackMessage?.trim()
            if (!message.isNullOrBlank()) {
                notesFeedbackId += 1
                topToast = somedayToastFromFeedback(notesFeedbackId, message)
            }
        }

        LaunchedEffect(topToast?.id) {
            val toast = topToast ?: return@LaunchedEffect
            delay(toast.durationMillis)
            if (topToast?.id == toast.id) {
                topToast = null
            }
        }

        fun openSearch() {
            navController.navigate(NotesSearchRoute) {
                launchSingleTop = true
            }
        }

        fun closeSearch() {
            notesController.clearSearch()
            if (currentRouteKind == SomedayRouteKind.NotesSearch) {
                navController.popBackStack()
            } else {
                navController.navigatePrimaryTab(PrimaryTab.Notes)
            }
        }

        fun showNotebooks() {
            notebookSheetVisible = true
        }

        fun showRecentlyDeleted() {
            notebookSheetVisible = false
            recentlyDeletedSheetVisible = true
        }

        fun selectNotebook(notebookId: String) {
            uiCoroutineScope.launch {
                if (notesController.selectNotebook(notebookId)) {
                    settingsController.recordLastSelectedNotebook(notebookId)
                    notebookSheetVisible = false
                    navController.navigatePrimaryTab(PrimaryTab.Notes)
                }
            }
        }

        fun navigateToPrimaryTab(tab: PrimaryTab): Boolean {
            if (notesController.state.editor != null &&
                (currentRouteKind == SomedayRouteKind.NoteEditor || tab != PrimaryTab.Notes)
            ) {
                val editorSessionId = notesController.currentEditorSessionId()
                if (!notesController.requestCloseEditor()) {
                    return false
                }
                if (currentRouteKind != SomedayRouteKind.NoteEditor) {
                    notesController.closeEditorSession(editorSessionId)
                }
            }
            if (currentRouteKind == SomedayRouteKind.NotesSearch) {
                if (tab == PrimaryTab.Notes) {
                    closeSearch()
                    return true
                }
            }
            if (tab != PrimaryTab.Notes) {
                notesController.clearNoteSelection()
            }
            navController.navigatePrimaryTab(tab)
            return true
        }

        fun selectPrimaryTab(tab: PrimaryTab) {
            navigateToPrimaryTab(tab)
        }

        fun openPendingMemories() {
            if (navigateToPrimaryTab(PrimaryTab.Memories)) {
                onPendingOpenMemoriesConsumed()
            }
        }

        LaunchedEffect(pendingOpenMemories, notesState.editor) {
            if (pendingOpenMemories && notesState.editor == null) {
                openPendingMemories()
            }
        }

        fun openNewNote() {
            val targetNotebookId = notesState.selectedNotebookId ?: appSettings.defaultNotebookId
            if (notesController.canNavigateToNewNote(targetNotebookId)) {
                navController.navigate(
                    NoteEditorRoute(
                        noteId = null,
                        notebookId = targetNotebookId,
                    ),
                )
            }
        }

        fun openExistingNote(noteId: String): Boolean {
            val canNavigate = notesController.canNavigateToExistingNote(noteId)
            if (canNavigate) {
                notebookSheetVisible = false
                navController.navigate(NoteEditorRoute(noteId = noteId))
            }
            return canNavigate
        }

        fun openConflictResolution(details: ConflictDetails) {
            if (notesController.requestCloseEditor()) {
                navController.navigate(NoteConflictResolutionRoute(details.conflictNoteId))
            }
        }

        fun showDeveloperFeedback(message: String): String {
            developerFeedbackId += 1
            topToast = somedayToastFromFeedback(developerFeedbackId, message)
            return message
        }

        fun requestPullToRefreshSync() {
            if (pullSyncActive) {
                return
            }
            pullSyncActive = true
            uiCoroutineScope.launch {
                try {
                    val minimumSpinner = launch { delay(SomedayPullRefreshMinimumSpinnerMillis) }
                    val synced = autoSyncScheduler.requestPullToRefreshSync()
                    if (!synced) {
                        notesController.refresh()
                        memoriesController.refresh()
                    }
                    minimumSpinner.join()
                } finally {
                    pullSyncActive = false
                }
            }
        }

        val pullRefresh = if (pullToRefreshSyncEnabled) {
            SomedayPullRefreshUi(
                refreshing = pullSyncActive,
                onRefresh = ::requestPullToRefreshSync,
            )
        } else {
            null
        }

        fun developerMockMessage(
            action: String,
            result: MockContentResult,
        ): String =
            if (result.success) {
                result.summary
            } else {
                "Cannot $action demo content: ${result.errorMessage}"
            }

        val devLocalRefreshedMsg = stringResource(Res.string.dev_local_refreshed)
        val developerOptions = if (developerOptionsEnabled) {
            DeveloperOptionsUi(
                platformName = platformName,
                buildTypeLabel = stringResource(Res.string.settings_debug),
                notebookCount = notesState.notebooks.size,
                selectedNotebookTitle = notesState.selectedNotebook?.title ?: stringResource(Res.string.common_none),
                selectedNotebookNoteCount = notesState.notes.size,
                syncModeLabel = settingsState.settings.syncConfiguration.mode.name,
                seedMockContent = {
                    val result = notesController.createMockContent()
                    memoriesController.refresh()
                    settingsController.refresh()
                    showDeveloperFeedback(developerMockMessage("create", result))
                },
                clearMockContent = {
                    val result = notesController.clearMockContent()
                    memoriesController.refresh()
                    settingsController.refresh()
                    showDeveloperFeedback(developerMockMessage("clear", result))
                },
                refreshLocalState = {
                    notesController.refresh()
                    memoriesController.refresh()
                    settingsController.refresh()
                    showDeveloperFeedback(devLocalRefreshedMsg)
                },
            )
        } else {
            null
        }

        SomedayLiquidGlassHost {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val layoutSpec = somedayLayoutSpecFor(maxWidth)
                SomedayAdaptiveShell(
                    layoutSpec = layoutSpec,
                    navController = navController,
                    currentRouteKind = currentRouteKind,
                    selectedTab = selectedTab,
                    notesState = notesState,
                    memoriesState = memoriesState,
                    settingsState = settingsState,
                    notesController = notesController,
                    memoriesController = memoriesController,
                    settingsController = settingsController,
                    mediaUiPorts = mediaUiPorts,
                    onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                    notesSearchActive = notesSearchActive,
                    windowChromeTopInset = windowChromeTopInset,
                    developerOptions = developerOptions,
                    workspacePairingScanner = workspacePairingScanner,
                    pullRefresh = pullRefresh,
                    discardEditorOnExit = discardEditorOnExit,
                    onEditorExitDiscardConsumed = { discardEditorOnExit = false },
                    onSelectTab = ::selectPrimaryTab,
                    onCreateNote = ::openNewNote,
                    onOpenNote = ::openExistingNote,
                    onOpenConflictResolution = ::openConflictResolution,
                    onOpenSearch = ::openSearch,
                    onCloseSearch = ::closeSearch,
                    onShowNotebooks = ::showNotebooks,
                    onSelectNotebook = ::selectNotebook,
                )

                if (notebookSheetVisible) {
                    ModalBottomSheet(
                        onDismissRequest = { notebookSheetVisible = false },
                    ) {
                        NotebookSheet(
                            state = notesState,
                            onCreateNotebook = { title ->
                                uiCoroutineScope.launch { notesController.createNotebook(title) }
                            },
                            onRenameNotebook = { notebookId, title ->
                                uiCoroutineScope.launch { notesController.renameNotebook(notebookId, title) }
                            },
                            onDeleteNotebook = { notebookId ->
                                uiCoroutineScope.launch { notesController.deleteNotebook(notebookId) }
                            },
                            onResolveNotebookConflict = { notebookId, versionId ->
                                uiCoroutineScope.launch {
                                    notesController.resolveNotebookConflictBranch(notebookId, versionId)
                                }
                            },
                            onShowRecentlyDeleted = ::showRecentlyDeleted,
                            onSelectNotebook = ::selectNotebook,
                        )
                    }
                }

                if (recentlyDeletedSheetVisible) {
                    ModalBottomSheet(
                        onDismissRequest = { recentlyDeletedSheetVisible = false },
                    ) {
                        RecentlyDeletedSheet(
                            state = notesState,
                            onRestoreDeletedItem = { entityId ->
                                uiCoroutineScope.launch {
                                    notesController.restoreDeletedWorkspaceItem(entityId)
                                }
                            },
                        )
                    }
                }

                if (notesState.unsavedChangesDialogVisible) {
                    AlertDialog(
                        onDismissRequest = notesController::keepEditing,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (currentRouteKind == SomedayRouteKind.NoteEditor) {
                                        discardEditorOnExit = true
                                        notesController.confirmDiscardEditorForRouteExit()
                                        navController.popBackStack()
                                    } else {
                                        notesController.discardEditorChanges()
                                    }
                                },
                            ) {
                                Text(stringResource(Res.string.unsaved_discard))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = notesController::keepEditing) {
                                Text(stringResource(Res.string.unsaved_keep_editing))
                            }
                        },
                        title = { Text(stringResource(Res.string.unsaved_title)) },
                        text = { Text(stringResource(Res.string.unsaved_message)) },
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = 18.dp, top = 12.dp, end = 18.dp),
                ) {
                    SomedayToastHost(
                        toast = topToast,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SomedaySyncStatusPill(
                        visible = settingsState.sync.syncing && !pullSyncActive,
                    )
                }
            }
        }
    }
    }
}

private val NoteEditorKeyboardToolbarHeight = 46.dp
private val NoteEditorKeyboardToolbarButtonSize = 34.dp
private val NoteEditorKeyboardToolbarIconSize = 16.dp
private val SomedaySidebarMinWidth = 240.dp
private val SomedaySidebarDefaultWidth = 292.dp
private val SomedaySidebarMaxWidth = 400.dp
private const val SomedayPullRefreshMinimumSpinnerMillis = 450L

@Composable
fun SomedayTheme(
    theme: ClientTheme = ClientTheme.System,
    content: @Composable () -> Unit,
) {
    val appliedTheme = resolveAppliedTheme(theme, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = somedayColorScheme(appliedTheme),
        shapes = SomedayDesignDefaults.MaterialShapes,
        typography = SomedayDesignDefaults.MaterialTypography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

private fun somedayColorScheme(appliedTheme: AppliedTheme) =
    if (appliedTheme == AppliedTheme.Dark) {
        darkColorScheme(
            primary = Color(0xFF9DD8C7),
            onPrimary = Color(0xFF00382D),
            primaryContainer = Color(0xFF164F42),
            onPrimaryContainer = Color(0xFFBCEFE1),
            secondary = Color(0xFFC5CCD6),
            onSecondary = Color(0xFF2E3339),
            secondaryContainer = Color(0xFF444A52),
            onSecondaryContainer = Color(0xFFE1E7F0),
            tertiary = Color(0xFFD1C0E8),
            onTertiary = Color(0xFF382A4C),
            tertiaryContainer = Color(0xFF504064),
            onTertiaryContainer = Color(0xFFECDCFF),
            background = Color(0xFF101312),
            onBackground = Color(0xFFE3E5E7),
            surface = Color(0xFF171B19),
            onSurface = Color(0xFFE3E5E7),
            surfaceVariant = Color(0xFF222824),
            onSurfaceVariant = Color(0xFFC4C9CF),
            outline = Color(0xFF8E949B),
            outlineVariant = Color(0xFF303733),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF256D5A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD8EFE7),
            onPrimaryContainer = Color(0xFF08382D),
            secondary = Color(0xFF5A6470),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE0E7EF),
            onSecondaryContainer = Color(0xFF17212B),
            tertiary = Color(0xFF6F5B8F),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFEBDDFF),
            onTertiaryContainer = Color(0xFF291840),
            background = Color(0xFFF7F9F7),
            onBackground = Color(0xFF1A1C1E),
            surface = Color(0xFFFCFDFC),
            onSurface = Color(0xFF1A1C1E),
            surfaceVariant = Color(0xFFEEF2EF),
            onSurfaceVariant = Color(0xFF5F666E),
            outline = Color(0xFF747B83),
            outlineVariant = Color(0xFFE2E7E3),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
        )
    }

@Composable
private fun SomedayIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = SomedayDesignDefaults.IconShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun NavigationTitleText(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.height(46.dp).padding(horizontal = 8.dp),
    ) {
        Text(
            text = title.withLeadingCapital(),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = if (subtitle.isNullOrBlank()) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleSmall
            },
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SomedayListCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = SomedayDesignDefaults.ContentCardShape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun SomedayPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = SomedayDesignDefaults.ContentCardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

private enum class SomedayToastStatus {
    Success,
    Info,
    Warning,
    Error,
}

private data class SomedayToast(
    val id: Long,
    val text: String,
    val status: SomedayToastStatus,
    val icon: ImageVector? = null,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val durationMillis: Long = 2_800L,
)

private data class SomedayToastVisuals(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
)

@Composable
private fun SomedayToastHost(
    toast: SomedayToast?,
    modifier: Modifier = Modifier,
) {
    var presentedToast by remember { mutableStateOf<SomedayToast?>(null) }

    LaunchedEffect(toast?.id) {
        if (toast != null) {
            presentedToast = toast
        }
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        presentedToast?.let { visibleToast ->
            SomedayToastCard(toast = visibleToast)
        }
    }
}

@Composable
private fun SomedayToastCard(toast: SomedayToast) {
    val visuals = somedayToastVisuals(toast.status)
    val containerColor = toast.containerColor ?: visuals.containerColor
    val contentColor = toast.contentColor ?: visuals.contentColor
    Surface(
        shape = SomedayDesignDefaults.ToastShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = toast.icon ?: visuals.icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = toast.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SomedaySyncStatusPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = SomedayDesignDefaults.PillShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(Res.string.sync_status_syncing),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun somedayToastVisuals(status: SomedayToastStatus): SomedayToastVisuals =
    when (status) {
        SomedayToastStatus.Success -> SomedayToastVisuals(
            icon = Lucide.Cloud,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        SomedayToastStatus.Info -> SomedayToastVisuals(
            icon = Lucide.Server,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SomedayToastStatus.Warning -> SomedayToastVisuals(
            icon = Lucide.History,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SomedayToastStatus.Error -> SomedayToastVisuals(
            icon = Lucide.X,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

private fun somedayToastFromFeedback(
    id: Long,
    message: String,
): SomedayToast =
    SomedayToast(
        id = id,
        text = message,
        status = message.feedbackToastStatus(),
    )

private fun String.feedbackToastStatus(): SomedayToastStatus {
    val value = lowercase()
    return when {
        listOf(
            "failed",
            "failure",
            "cannot",
            "could not",
            "denied",
            "error",
            "invalid",
            "missing",
            "must",
            "not available",
            "unavailable",
            "required",
        ).any { value.contains(it) } -> SomedayToastStatus.Error
        listOf("choose", "conflict", "unsaved", "warning").any { value.contains(it) } -> SomedayToastStatus.Warning
        listOf(
            "cleared",
            "disabled",
            "enabled",
            "prepared",
            "removed",
            "saved",
            "started",
            "succeeded",
            "updated",
        ).any { value.contains(it) } -> SomedayToastStatus.Success
        else -> SomedayToastStatus.Info
    }
}

private fun SettingsFeedbackSeverity.toToastStatus(): SomedayToastStatus =
    when (this) {
        SettingsFeedbackSeverity.Info -> SomedayToastStatus.Info
        SettingsFeedbackSeverity.Success -> SomedayToastStatus.Success
        SettingsFeedbackSeverity.Warning -> SomedayToastStatus.Warning
        SettingsFeedbackSeverity.Error -> SomedayToastStatus.Error
    }

private data class DeveloperOptionsUi(
    val platformName: String,
    val buildTypeLabel: String,
    val notebookCount: Int,
    val selectedNotebookTitle: String,
    val selectedNotebookNoteCount: Int,
    val syncModeLabel: String,
    val seedMockContent: suspend () -> String,
    val clearMockContent: suspend () -> String,
    val refreshLocalState: suspend () -> String,
)

private enum class SomedayNavigationLayout {
    BottomBar,
    Rail,
    Sidebar,
}

private enum class SomedayContentLayout {
    SinglePane,
    ListDetail,
    ThreePane,
}

private enum class NoteEditorToolbarPlacement {
    Inline,
    KeyboardAccessory,
}

private data class SomedayLayoutSpec(
    val navigationLayout: SomedayNavigationLayout,
    val contentLayout: SomedayContentLayout,
) {
    val isCompact: Boolean = navigationLayout == SomedayNavigationLayout.BottomBar
}

private fun somedayLayoutSpecFor(width: Dp): SomedayLayoutSpec =
    when {
        width < 680.dp -> SomedayLayoutSpec(
            navigationLayout = SomedayNavigationLayout.BottomBar,
            contentLayout = SomedayContentLayout.SinglePane,
        )
        width < 1040.dp -> SomedayLayoutSpec(
            navigationLayout = SomedayNavigationLayout.Rail,
            contentLayout = SomedayContentLayout.ListDetail,
        )
        else -> SomedayLayoutSpec(
            navigationLayout = SomedayNavigationLayout.Sidebar,
            contentLayout = SomedayContentLayout.ThreePane,
        )
    }

@Composable
private fun SomedayAdaptiveShell(
    layoutSpec: SomedayLayoutSpec,
    navController: NavHostController,
    currentRouteKind: SomedayRouteKind,
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    memoriesController: MemoriesUiController,
    settingsController: SettingsUiController,
    mediaUiPorts: MediaUiPorts,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    notesSearchActive: Boolean,
    windowChromeTopInset: Dp,
    developerOptions: DeveloperOptionsUi?,
    workspacePairingScanner: WorkspacePairingScanner,
    pullRefresh: SomedayPullRefreshUi?,
    discardEditorOnExit: Boolean,
    onEditorExitDiscardConsumed: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    onSelectNotebook: (String) -> Unit,
) {
    if (layoutSpec.isCompact) {
        SomedayCompactShell(
            navController = navController,
            currentRouteKind = currentRouteKind,
            selectedTab = selectedTab,
            notesState = notesState,
            memoriesState = memoriesState,
            settingsState = settingsState,
            notesController = notesController,
            memoriesController = memoriesController,
            settingsController = settingsController,
            mediaUiPorts = mediaUiPorts,
            onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
            notesSearchActive = notesSearchActive,
            windowChromeTopInset = windowChromeTopInset,
            developerOptions = developerOptions,
            workspacePairingScanner = workspacePairingScanner,
            pullRefresh = pullRefresh,
            discardEditorOnExit = discardEditorOnExit,
            onEditorExitDiscardConsumed = onEditorExitDiscardConsumed,
            onSelectTab = onSelectTab,
            onCreateNote = onCreateNote,
            onOpenNote = onOpenNote,
            onOpenConflictResolution = onOpenConflictResolution,
            onOpenSearch = onOpenSearch,
            onCloseSearch = onCloseSearch,
            onShowNotebooks = onShowNotebooks,
        )
    } else {
        SomedayWideShell(
            layoutSpec = layoutSpec,
            navController = navController,
            currentRouteKind = currentRouteKind,
            selectedTab = selectedTab,
            notesState = notesState,
            memoriesState = memoriesState,
            settingsState = settingsState,
            notesController = notesController,
            memoriesController = memoriesController,
            settingsController = settingsController,
            mediaUiPorts = mediaUiPorts,
            onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
            notesSearchActive = notesSearchActive,
            windowChromeTopInset = windowChromeTopInset,
            developerOptions = developerOptions,
            workspacePairingScanner = workspacePairingScanner,
            pullRefresh = pullRefresh,
            discardEditorOnExit = discardEditorOnExit,
            onEditorExitDiscardConsumed = onEditorExitDiscardConsumed,
            onSelectTab = onSelectTab,
            onCreateNote = onCreateNote,
            onOpenNote = onOpenNote,
            onOpenConflictResolution = onOpenConflictResolution,
            onOpenSearch = onOpenSearch,
            onCloseSearch = onCloseSearch,
            onShowNotebooks = onShowNotebooks,
            onSelectNotebook = onSelectNotebook,
        )
    }
}

@Composable
private fun SomedayCompactShell(
    navController: NavHostController,
    currentRouteKind: SomedayRouteKind,
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    memoriesController: MemoriesUiController,
    settingsController: SettingsUiController,
    mediaUiPorts: MediaUiPorts,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    notesSearchActive: Boolean,
    windowChromeTopInset: Dp,
    developerOptions: DeveloperOptionsUi?,
    workspacePairingScanner: WorkspacePairingScanner,
    pullRefresh: SomedayPullRefreshUi?,
    discardEditorOnExit: Boolean,
    onEditorExitDiscardConsumed: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.padding(top = windowChromeTopInset),
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (shouldShowCreateNoteAction(selectedTab, currentRouteKind, notesState)) {
                SomedayLiquidGlassFloatingActionButton(
                    icon = Lucide.Plus,
                    contentDescription = stringResource(Res.string.nav_new_note),
                    onClick = onCreateNote,
                )
            }
        },
        bottomBar = {
            if (!currentRouteKind.showsNoteEditor) {
                SomedayLiquidGlassBottomBar {
                    primaryNavigationTabs.forEach { tab ->
                        SomedayLiquidGlassNavigationItem(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = primaryTabIcon(tab),
                            label = tab.localizedLabel(),
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        SomedayLiquidGlassBackdrop(
            modifier = Modifier.fillMaxSize(),
        ) {
            SomedayNavigationHost(
                layoutSpec = SomedayLayoutSpec(
                    navigationLayout = SomedayNavigationLayout.BottomBar,
                    contentLayout = SomedayContentLayout.SinglePane,
                ),
                navController = navController,
                notesState = notesState,
                memoriesState = memoriesState,
                settingsState = settingsState,
                notesController = notesController,
                memoriesController = memoriesController,
                settingsController = settingsController,
                mediaUiPorts = mediaUiPorts,
                onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                developerOptions = developerOptions,
                workspacePairingScanner = workspacePairingScanner,
                pullRefresh = pullRefresh,
                discardEditorOnExit = discardEditorOnExit,
                onEditorExitDiscardConsumed = onEditorExitDiscardConsumed,
                onCreateNote = onCreateNote,
                onOpenNote = onOpenNote,
                onOpenConflictResolution = onOpenConflictResolution,
                onOpenSearch = onOpenSearch,
                onCloseSearch = onCloseSearch,
                onShowNotebooks = onShowNotebooks,
                paddingValues = paddingValues,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SomedayWideShell(
    layoutSpec: SomedayLayoutSpec,
    navController: NavHostController,
    currentRouteKind: SomedayRouteKind,
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    memoriesController: MemoriesUiController,
    settingsController: SettingsUiController,
    mediaUiPorts: MediaUiPorts,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    notesSearchActive: Boolean,
    windowChromeTopInset: Dp,
    developerOptions: DeveloperOptionsUi?,
    workspacePairingScanner: WorkspacePairingScanner,
    pullRefresh: SomedayPullRefreshUi?,
    discardEditorOnExit: Boolean,
    onEditorExitDiscardConsumed: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    onSelectNotebook: (String) -> Unit,
) {
    val density = LocalDensity.current
    var sidebarWidth by remember { mutableStateOf(SomedaySidebarDefaultWidth) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = windowChromeTopInset),
        ) {
            when (layoutSpec.navigationLayout) {
                SomedayNavigationLayout.Rail -> SomedayNavigationRail(
                    selectedTab = selectedTab,
                    showCreateNote = shouldShowCreateNoteAction(selectedTab, currentRouteKind, notesState),
                    onSelectTab = onSelectTab,
                    onCreateNote = onCreateNote,
                )
                SomedayNavigationLayout.Sidebar -> SomedaySidebar(
                    selectedTab = selectedTab,
                    notesState = notesState,
                    showCreateNote = shouldShowCreateNoteAction(selectedTab, currentRouteKind, notesState),
                    width = sidebarWidth,
                    onSelectTab = onSelectTab,
                    onCreateNote = onCreateNote,
                    onShowNotebooks = onShowNotebooks,
                    onSelectNotebook = onSelectNotebook,
                )
                SomedayNavigationLayout.BottomBar -> Unit
            }
            if (layoutSpec.navigationLayout == SomedayNavigationLayout.Sidebar) {
                ResizableSidebarDivider(
                    onDrag = { deltaPx ->
                        val delta = with(density) { deltaPx.toDp() }
                        sidebarWidth = (sidebarWidth + delta).coerceIn(
                            SomedaySidebarMinWidth,
                            SomedaySidebarMaxWidth,
                        )
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            SomedayLiquidGlassBackdrop(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                SomedayNavigationHost(
                    layoutSpec = layoutSpec,
                    navController = navController,
                    notesState = notesState,
                    memoriesState = memoriesState,
                    settingsState = settingsState,
                    notesController = notesController,
                    memoriesController = memoriesController,
                    settingsController = settingsController,
                    mediaUiPorts = mediaUiPorts,
                    onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                    developerOptions = developerOptions,
                    workspacePairingScanner = workspacePairingScanner,
                    pullRefresh = pullRefresh,
                    discardEditorOnExit = discardEditorOnExit,
                    onEditorExitDiscardConsumed = onEditorExitDiscardConsumed,
                    onCreateNote = onCreateNote,
                    onOpenNote = onOpenNote,
                    onOpenConflictResolution = onOpenConflictResolution,
                    onOpenSearch = onOpenSearch,
                    onCloseSearch = onCloseSearch,
                    onShowNotebooks = onShowNotebooks,
                    paddingValues = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        SomedayStatusBar(
            selectedTab = selectedTab,
            notesState = notesState,
            settingsState = settingsState,
        )
    }
}

@Composable
private fun SomedayNavigationRail(
    selectedTab: PrimaryTab,
    showCreateNote: Boolean,
    onSelectTab: (PrimaryTab) -> Unit,
    onCreateNote: () -> Unit,
) {
    NavigationRail(
        header = {
            if (showCreateNote) {
                SomedayLiquidGlassFloatingActionButton(
                    icon = Lucide.Plus,
                    contentDescription = stringResource(Res.string.nav_new_note),
                    onClick = onCreateNote,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        },
        modifier = Modifier.fillMaxHeight(),
    ) {
        primaryNavigationTabs.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                icon = { Icon(primaryTabIcon(tab), contentDescription = null) },
                label = { Text(tab.localizedLabel()) },
            )
        }
    }
}

@Composable
private fun ResizableSidebarDivider(
    onDrag: (Float) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .width(8.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun SomedaySidebar(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    showCreateNote: Boolean,
    width: Dp,
    onSelectTab: (PrimaryTab) -> Unit,
    onCreateNote: () -> Unit,
    onShowNotebooks: () -> Unit,
    onSelectNotebook: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(width).fillMaxHeight(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            HeaderTitleBlock(
                title = "Someday",
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                primaryNavigationTabs.forEach { tab ->
                    PrimarySidebarItem(
                        tab = tab,
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                    )
                }
            }
            Button(
                onClick = onCreateNote,
                enabled = showCreateNote,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.nav_new_note))
            }
            HorizontalDivider()
            DesktopNotebookSection(
                state = notesState,
                onShowNotebooks = onShowNotebooks,
                onSelectNotebook = onSelectNotebook,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PrimarySidebarItem(
    tab: PrimaryTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(containerColor, SomedayDesignDefaults.CellShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            imageVector = primaryTabIcon(tab),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = tab.localizedLabel(),
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DesktopNotebookSection(
    state: NotesUiState,
    onShowNotebooks: () -> Unit,
    onSelectNotebook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(Res.string.nav_notebooks),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onShowNotebooks, modifier = Modifier.size(34.dp)) {
                Icon(Lucide.Plus, contentDescription = stringResource(Res.string.nav_new_notebook), modifier = Modifier.size(18.dp))
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.notebooks.isEmpty()) {
                Text(
                    text = stringResource(Res.string.nav_no_notebooks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                for (notebook in state.notebooks) {
                    NotebookSidebarItem(
                        title = notebook.title,
                        selected = notebook.id == state.selectedNotebookId,
                        syncBadge = notebook.syncBadge,
                        onClick = { onSelectNotebook(notebook.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookSidebarItem(
    title: String,
    selected: Boolean,
    syncBadge: NoteSyncBadge,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, SomedayDesignDefaults.CellShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Icon(
            imageVector = Lucide.NotebookText,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = title,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        NoteSyncIndicator(syncBadge = syncBadge, showText = false)
    }
}

@Composable
private fun SomedayNavigationHost(
    layoutSpec: SomedayLayoutSpec,
    navController: NavHostController,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    memoriesController: MemoriesUiController,
    settingsController: SettingsUiController,
    mediaUiPorts: MediaUiPorts,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    developerOptions: DeveloperOptionsUi?,
    workspacePairingScanner: WorkspacePairingScanner,
    pullRefresh: SomedayPullRefreshUi?,
    discardEditorOnExit: Boolean,
    onEditorExitDiscardConsumed: () -> Unit,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val routeCoroutineScope = rememberCoroutineScope()
    NavHost(
        navController = navController,
        startDestination = NotesRoute,
        enterTransition = {
            somedayNavEnterTransition(
                layoutSpec = layoutSpec,
                from = initialState.somedayRouteKind(),
                to = targetState.somedayRouteKind(),
            )
        },
        exitTransition = {
            somedayNavExitTransition(
                layoutSpec = layoutSpec,
                from = initialState.somedayRouteKind(),
                to = targetState.somedayRouteKind(),
            )
        },
        popEnterTransition = {
            somedayNavPopEnterTransition(
                layoutSpec = layoutSpec,
                from = initialState.somedayRouteKind(),
                to = targetState.somedayRouteKind(),
            )
        },
        popExitTransition = {
            somedayNavPopExitTransition(
                layoutSpec = layoutSpec,
                from = initialState.somedayRouteKind(),
                to = targetState.somedayRouteKind(),
            )
        },
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        composable<NotesRoute> {
            SomedayRouteSurface {
                if (layoutSpec.isCompact) {
                    NotesTabLazyContent(
                        selectedTab = PrimaryTab.Notes,
                        notesState = notesState,
                        memoriesState = memoriesState,
                        notesController = notesController,
                        notesSearchActive = false,
                        pullRefresh = pullRefresh,
                        onOpenNote = onOpenNote,
                        onOpenSearch = onOpenSearch,
                        onCloseSearch = onCloseSearch,
                        onShowNotebooks = onShowNotebooks,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    )
                } else {
                    NotesAdaptiveWorkspace(
                        layoutSpec = layoutSpec,
                        selectedTab = PrimaryTab.Notes,
                        notesState = notesState,
                        memoriesState = memoriesState,
                        settingsState = settingsState,
                        notesController = notesController,
                        mediaUiPorts = mediaUiPorts,
                        notesSearchActive = false,
                        pullRefresh = pullRefresh,
                        onCreateNote = onCreateNote,
                        onOpenNote = onOpenNote,
                        onOpenConflictResolution = onOpenConflictResolution,
                        onOpenSearch = onOpenSearch,
                        onCloseSearch = onCloseSearch,
                        onShowNotebooks = onShowNotebooks,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composable<NotesSearchRoute> {
            RouteLifecycleBoundary(
                routeKey = NotesSearchRoute,
            ) {
                SomedayRouteSurface {
                    if (layoutSpec.isCompact) {
                        NotesTabLazyContent(
                            selectedTab = PrimaryTab.Notes,
                            notesState = notesState,
                            memoriesState = memoriesState,
                            notesController = notesController,
                            notesSearchActive = true,
                            pullRefresh = pullRefresh,
                            onOpenNote = onOpenNote,
                            onOpenSearch = onOpenSearch,
                            onCloseSearch = onCloseSearch,
                            onShowNotebooks = onShowNotebooks,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                        )
                    } else {
                        NotesAdaptiveWorkspace(
                            layoutSpec = layoutSpec,
                            selectedTab = PrimaryTab.Notes,
                            notesState = notesState,
                            memoriesState = memoriesState,
                            settingsState = settingsState,
                            notesController = notesController,
                            mediaUiPorts = mediaUiPorts,
                            notesSearchActive = true,
                            pullRefresh = pullRefresh,
                            onCreateNote = onCreateNote,
                            onOpenNote = onOpenNote,
                            onOpenConflictResolution = onOpenConflictResolution,
                            onOpenSearch = onOpenSearch,
                            onCloseSearch = onCloseSearch,
                            onShowNotebooks = onShowNotebooks,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        composable<NoteEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<NoteEditorRoute>()
            var routeEditorSessionId by remember(route) { mutableStateOf<Long?>(null) }
            val routeOwnsCurrentEditor = routeEditorSessionId != null &&
                notesState.editor?.sessionId == routeEditorSessionId
            val routeNotesState = rememberRouteRetainedValue(
                value = notesState,
                retainLiveValue = routeOwnsCurrentEditor,
            )
            RouteLifecycleBoundary(
                routeKey = route,
                onEnter = {
                    val currentEditor = notesController.state.editor
                    val alreadyOpen = if (route.noteId == null) {
                        currentEditor?.noteId == null && currentEditor?.notebookId == route.notebookId
                    } else {
                        currentEditor?.noteId == route.noteId
                    }
                    val entered = if (alreadyOpen) {
                        true
                    } else {
                        val opened = route.noteId
                            ?.let { notesController.openExistingNote(it) }
                            ?: notesController.openNewNote(route.notebookId)
                        if (!opened) {
                            navController.popBackStack()
                        }
                        opened
                    }
                    routeEditorSessionId = if (entered) {
                        notesController.currentEditorSessionId()
                    } else {
                        null
                    }
                },
                onExit = {
                    notesController.closeEditorSession(
                        sessionId = routeEditorSessionId,
                        discarded = discardEditorOnExit,
                    )
                    if (discardEditorOnExit) {
                        onEditorExitDiscardConsumed()
                    }
                },
            ) {
                SomedayRouteSurface {
                    if (layoutSpec.isCompact) {
                        NoteEditorRouteContent(
                            navController = navController,
                            notesState = routeNotesState,
                            settingsState = settingsState,
                            notesController = notesController,
                            mediaUiPorts = mediaUiPorts,
                            onOpenConflictResolution = onOpenConflictResolution,
                            paddingValues = paddingValues,
                        )
                    } else {
                        NotesAdaptiveWorkspace(
                            layoutSpec = layoutSpec,
                            selectedTab = PrimaryTab.Notes,
                            notesState = routeNotesState,
                            memoriesState = memoriesState,
                            settingsState = settingsState,
                            notesController = notesController,
                            mediaUiPorts = mediaUiPorts,
                            notesSearchActive = false,
                            pullRefresh = pullRefresh,
                            onCreateNote = onCreateNote,
                            onOpenNote = onOpenNote,
                            onOpenConflictResolution = onOpenConflictResolution,
                            onOpenSearch = onOpenSearch,
                            onCloseSearch = onCloseSearch,
                            onShowNotebooks = onShowNotebooks,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        composable<NoteConflictResolutionRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<NoteConflictResolutionRoute>()
            RouteLifecycleBoundary(
                routeKey = route,
                onEnter = {
                    val opened = notesController.openConflictResolution(route.conflictNoteId)
                    if (!opened) {
                        navController.popBackStack()
                    }
                },
            ) {
                SomedayRouteSurface {
                    NoteConflictResolutionRouteContent(
                        navController = navController,
                        notesState = notesState,
                        notesController = notesController,
                        paddingValues = paddingValues,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composable<MemoriesRoute> {
            SomedayRouteSurface {
                LaunchedEffect(Unit) {
                    memoriesController.refresh()
                }
                if (layoutSpec.isCompact) {
                    MemoriesTabLazyContent(
                        selectedTab = PrimaryTab.Memories,
                        notesState = notesState,
                        memoriesState = memoriesState,
                        pullRefresh = pullRefresh,
                        onGoToMonth = { month ->
                            routeCoroutineScope.launch { memoriesController.goToMonth(month) }
                        },
                        onSelectDay = { day ->
                            routeCoroutineScope.launch { memoriesController.selectDay(day) }
                        },
                        onOpenNote = onOpenNote,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    )
                } else {
                    SomedayWideLazyPage(
                        title = pageHeaderTitle(PrimaryTab.Memories, notesState, notesSearchActive = false),
                        subtitle = pageHeaderSubtitle(PrimaryTab.Memories, notesState, memoriesState, notesSearchActive = false),
                        pullRefresh = pullRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        memoriesContentItems(
                            state = memoriesState,
                            onGoToMonth = { month ->
                                routeCoroutineScope.launch { memoriesController.goToMonth(month) }
                            },
                            onSelectDay = { day ->
                                routeCoroutineScope.launch { memoriesController.selectDay(day) }
                            },
                            onOpenNote = onOpenNote,
                        )
                    }
                }
            }
        }
        composable<SettingsRoute> {
            SomedayRouteSurface {
                SettingsContent(
                    page = null,
                    state = settingsState,
                    controller = settingsController,
                    onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                    developerOptions = developerOptions,
                    workspacePairingScanner = workspacePairingScanner,
                    actionScope = routeCoroutineScope,
                    onOpenPage = { page -> navController.navigate(SettingsDetailRoute(page.routeId)) },
                    onBack = { navController.popBackStack() },
                    modifier = settingsContentModifier(layoutSpec, paddingValues),
                )
            }
        }
        composable<SettingsDetailRoute> { backStackEntry ->
            SomedayRouteSurface {
                val route = backStackEntry.toRoute<SettingsDetailRoute>()
                val page = settingsPageFromRouteId(route.pageId)
                if (page == null) {
                    LaunchedEffect(route.pageId) {
                        navController.popBackStack()
                    }
                } else {
                    SettingsContent(
                        page = page,
                        state = settingsState,
                        controller = settingsController,
                        onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                        developerOptions = developerOptions,
                        workspacePairingScanner = workspacePairingScanner,
                        actionScope = routeCoroutineScope,
                        onOpenPage = { nextPage -> navController.navigate(SettingsDetailRoute(nextPage.routeId)) },
                        onBack = { navController.popBackStack() },
                        modifier = settingsContentModifier(layoutSpec, paddingValues),
                    )
                }
            }
        }
    }
}

@Composable
private fun SomedayRouteSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
    }
}

@Composable
private fun NoteEditorRouteContent(
    navController: NavController,
    notesState: NotesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    mediaUiPorts: MediaUiPorts,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    paddingValues: PaddingValues,
) {
    val coroutineScope = rememberCoroutineScope()
    if (notesState.editor != null) {
        NoteEditorContent(
            state = notesState,
            controller = notesController,
            mediaUiPorts = mediaUiPorts,
            editorPreferences = settingsState.settings.editorPreferences,
            toolbarPlacement = NoteEditorToolbarPlacement.KeyboardAccessory,
            contentHorizontalPadding = 24.dp,
            autoFocusBody = false,
            onOpenConflictResolution = onOpenConflictResolution,
            onSave = {
                coroutineScope.launch {
                    if (notesController.saveEditorForRouteExit()) {
                        navController.popBackStack()
                    }
                }
            },
            onClose = {
                if (notesController.requestCloseEditor()) {
                    navController.popBackStack()
                }
            },
            onDeleteNote = { noteId ->
                coroutineScope.launch {
                    val deleted = notesController.deleteNoteForRouteExit(noteId)
                    if (deleted) {
                        navController.popBackStack()
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        )
    }
}

@Composable
private fun NoteConflictResolutionRouteContent(
    navController: NavController,
    notesState: NotesUiState,
    notesController: NotesUiController,
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val details = notesState.conflictDetails
    Column(
        modifier = modifier
            .padding(top = paddingValues.calculateTopPadding())
            .padding(horizontal = 24.dp),
    ) {
        SomedayLiquidGlassTopBar(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(100.dp).fillMaxHeight(),
            ) {
                SomedayLiquidGlassIconButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = stringResource(Res.string.common_back),
                    onClick = { navController.popBackStack() },
                )
            }
            NavigationTitleText(
                title = stringResource(Res.string.conflict_resolve_title),
                subtitle = details?.sourceDeviceId,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(100.dp))
        }
        if (details == null) {
            EmptyState(
                icon = Lucide.History,
                title = stringResource(Res.string.conflict_resolved_title),
                body = stringResource(Res.string.conflict_resolved_empty),
            ) {
                Button(onClick = { navController.popBackStack() }) {
                    Text(stringResource(Res.string.common_back))
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp, bottom = 28.dp),
            ) {
                Text(
                    stringResource(Res.string.conflict_branch_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                details.versionBranches.forEachIndexed { index, branch ->
                    ConflictHistoryPanel(
                        title = stringResource(Res.string.conflict_branch_n, index + 1),
                        history = branch.history,
                        metadata = buildString {
                            append(if (branch.deleted) stringResource(Res.string.common_deletion) else stringResource(Res.string.common_content))
                            append(" · ")
                            append(branch.updatedAt)
                            branch.authorDeviceId?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        actionLabel = if (branch.deleted) {
                            stringResource(Res.string.conflict_keep_deletion)
                        } else {
                            stringResource(Res.string.conflict_use_this_branch)
                        },
                        onAction = {
                            coroutineScope.launch {
                                if (notesController.resolveConflictBranch(branch.versionId)) {
                                    navController.popBackStack()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun settingsContentModifier(
    layoutSpec: SomedayLayoutSpec,
    paddingValues: PaddingValues,
): Modifier =
    if (layoutSpec.isCompact) {
        Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = SomedayDesignDefaults.CompactPageHorizontalPadding)
    } else {
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    }

@Composable
private fun NotesAdaptiveWorkspace(
    layoutSpec: SomedayLayoutSpec,
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    settingsState: SettingsUiState,
    notesController: NotesUiController,
    mediaUiPorts: MediaUiPorts,
    notesSearchActive: Boolean,
    pullRefresh: SomedayPullRefreshUi?,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val listWidth = if (layoutSpec.contentLayout == SomedayContentLayout.ThreePane) 382.dp else 328.dp
    Row(modifier = modifier.fillMaxSize()) {
        NotesListPane(
            selectedTab = selectedTab,
            notesState = notesState,
            memoriesState = memoriesState,
            notesController = notesController,
            notesSearchActive = notesSearchActive,
            pullRefresh = pullRefresh,
            onOpenNote = onOpenNote,
            onOpenSearch = onOpenSearch,
            onCloseSearch = onCloseSearch,
            onShowNotebooks = onShowNotebooks,
            modifier = Modifier.width(listWidth).fillMaxHeight(),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (notesState.editor == null) {
            NotesDetailPlaceholder(
                notesState = notesState,
                onCreateNote = onCreateNote,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else {
            NoteEditorContent(
                state = notesState,
                controller = notesController,
                mediaUiPorts = mediaUiPorts,
                editorPreferences = settingsState.settings.editorPreferences,
                autoFocusBody = true,
                onOpenConflictResolution = onOpenConflictResolution,
                onSave = {
                    coroutineScope.launch { notesController.saveEditor() }
                },
                onClose = {
                    val editorSessionId = notesController.currentEditorSessionId()
                    if (notesController.requestCloseEditor()) {
                        notesController.closeEditorSession(editorSessionId)
                    }
                },
                onDeleteNote = { noteId ->
                    coroutineScope.launch { notesController.deleteNote(noteId) }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 28.dp, top = 22.dp, end = 28.dp),
            )
        }
    }
}

@Composable
private fun NotesListPane(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    notesController: NotesUiController,
    notesSearchActive: Boolean,
    pullRefresh: SomedayPullRefreshUi?,
    onOpenNote: (String) -> Boolean,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var primarySelectionModifierPressed by remember { mutableStateOf(false) }
    var extendSelectionPressed by remember { mutableStateOf(false) }
    var batchDialog by rememberSaveable { mutableStateOf<NotesBatchDialog?>(null) }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                primarySelectionModifierPressed = event.isMetaPressed || event.isCtrlPressed
                extendSelectionPressed = event.isShiftPressed
                when {
                    event.type == KeyEventType.KeyDown &&
                        primarySelectionModifierPressed && event.key == Key.A -> {
                        notesController.selectAllVisibleNotes()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                        notesState.noteSelectionActive -> {
                        notesController.clearNoteSelection()
                        true
                    }
                    else -> false
                }
            }
            .fillMaxHeight(),
    ) {
        NotesPageHeader(
            selectedTab = selectedTab,
            notesState = notesState,
            memoriesState = memoriesState,
            notesSearchActive = notesSearchActive,
            onOpenSearch = onOpenSearch,
            onCloseSearch = onCloseSearch,
            onShowNotebooks = onShowNotebooks,
            onSelectAllNotes = notesController::selectAllVisibleNotes,
            onClearNoteSelection = notesController::clearNoteSelection,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SomedayDesignDefaults.PageHorizontalPadding,
                    top = 24.dp,
                    end = SomedayDesignDefaults.CompactPageHorizontalPadding,
                ),
        )
        if (selectedTab == PrimaryTab.Notes && notesState.noteSelectionActive) {
            NotesBatchActionBar(
                operationInProgress = notesState.batchOperationInProgress,
                onAction = { batchDialog = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SomedayDesignDefaults.CompactPageHorizontalPadding),
            )
        }
        SomedaySyncPullToRefresh(
            pullRefresh = pullRefresh.takeUnless { notesSearchActive },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumnWithDragHandle(
                modifier = Modifier.fillMaxSize(),
                listStateKey = notesListStateKey(notesState, notesSearchActive),
                contentPadding = PaddingValues(
                    start = SomedayDesignDefaults.PageHorizontalPadding,
                    top = 12.dp,
                    end = SomedayDesignDefaults.CompactPageHorizontalPadding,
                    bottom = 24.dp,
                ),
                dragLabelForItemIndex = notesDragMonthLabelProvider(notesState, notesSearchActive),
            ) {
                if (notesSearchActive) {
                    notesSearchItems(
                        state = notesState,
                        onSearchQueryChange = { query ->
                            coroutineScope.launch { notesController.updateSearchQuery(query) }
                        },
                        onClearSearch = notesController::clearSearch,
                        onOpenNote = { noteId -> onOpenNote(noteId) },
                        showSelectionControls = true,
                        selectionOnClick = notesState.noteSelectionActive || primarySelectionModifierPressed,
                        extendSelection = extendSelectionPressed,
                        onToggleSelection = notesController::toggleNoteSelection,
                    )
                } else {
                    notesListItems(
                        state = notesState,
                        onShowNotebooks = onShowNotebooks,
                        onOpenNote = { noteId -> onOpenNote(noteId) },
                        showSelectionControls = true,
                        selectionOnClick = notesState.noteSelectionActive || primarySelectionModifierPressed,
                        extendSelection = extendSelectionPressed,
                        onToggleSelection = notesController::toggleNoteSelection,
                    )
                }
            }
        }
        NotesBatchUndoBar(
            deletedCount = notesState.batchDeleteUndoItems.size,
            operationInProgress = notesState.batchOperationInProgress,
            onUndo = { coroutineScope.launch { notesController.undoLastBatchDelete() } },
            onDismiss = notesController::dismissBatchDeleteUndo,
        )
    }
    NotesBatchDialogs(
        dialog = batchDialog,
        state = notesState,
        onDismiss = { batchDialog = null },
        onMove = { notebookId -> coroutineScope.launch { notesController.moveSelectedNotes(notebookId) } },
        onDelete = { coroutineScope.launch { notesController.deleteSelectedNotes() } },
        onChangeDate = { date -> coroutineScope.launch { notesController.changeSelectedNotesCreatedDate(date) } },
        onChangeTimeZone = { zone -> coroutineScope.launch { notesController.changeSelectedNotesTimeZone(zone) } },
        onClearLocation = { coroutineScope.launch { notesController.clearSelectedNotesLocation() } },
    )
}

@Composable
private fun NotesDetailPlaceholder(
    notesState: NotesUiState,
    onCreateNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(32.dp),
    ) {
        EmptyState(
            icon = Lucide.BookOpenText,
            title = if (notesState.notes.isEmpty()) stringResource(Res.string.notes_no_notes) else stringResource(Res.string.notes_select_a_note),
        ) {
            Button(onClick = onCreateNote, enabled = notesState.notebooks.isNotEmpty()) {
                Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.nav_new_note))
            }
        }
    }
}

@Composable
private fun SomedayWideScrollablePage(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 980.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            AppHeader(
                title = title,
                subtitle = subtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 34.dp, top = 26.dp, end = 34.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 34.dp, top = 16.dp, end = 34.dp, bottom = 26.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SomedayWideLazyPage(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    pullRefresh: SomedayPullRefreshUi? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 980.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            AppHeader(
                title = title,
                subtitle = subtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 34.dp, top = 26.dp, end = 34.dp),
            )
            SomedaySyncPullToRefresh(
                pullRefresh = pullRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 34.dp, top = 16.dp, end = 34.dp, bottom = 26.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SomedayStatusBar(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    settingsState: SettingsUiState,
) {
    val detail = when (selectedTab) {
        PrimaryTab.Notes -> notesState.selectedNotebook?.let { stringResource(Res.string.status_notes_in_notebook, notesState.notes.size, it.title) }
            ?: stringResource(Res.string.status_no_notebook_selected)
        PrimaryTab.Memories -> stringResource(Res.string.tab_memories_title)
        PrimaryTab.Settings -> when {
            settingsState.sync.syncing -> stringResource(Res.string.sync_status_syncing)
            settingsState.sync.issue != null -> stringResource(Res.string.sync_last_issue)
            settingsState.sync.connection is SyncConnectionUi.Connected -> stringResource(Res.string.common_signed_in)
            else -> stringResource(Res.string.common_not_configured)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().height(30.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        ) {
            Text(selectedTab.contentTitle(), style = MaterialTheme.typography.labelSmall)
            Text(detail, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun shouldShowCreateNoteAction(
    selectedTab: PrimaryTab,
    currentRouteKind: SomedayRouteKind,
    notesState: NotesUiState,
): Boolean =
    selectedTab != PrimaryTab.Settings &&
        !notesState.noteSelectionActive &&
        notesState.editor == null &&
        currentRouteKind != SomedayRouteKind.NotesSearch &&
        currentRouteKind != SomedayRouteKind.NoteEditor &&
        currentRouteKind != SomedayRouteKind.NoteConflictResolution

private val SomedayPageEnterEasing = CubicBezierEasing(0.18f, 0.86f, 0.28f, 1f)
private val SomedayPageExitEasing = CubicBezierEasing(0.34f, 0f, 0.66f, 1f)
private val SomedayPageFadeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val LazyListDragHandleOverlayWidth = 128.dp
private const val NotesSearchResultStartIndex = 3

private const val CALENDAR_PAGER_COUNT = 100_000
private const val CALENDAR_PAGER_CENTER = 50_000

@Composable
private fun PrimaryTab.contentTitle(): String =
    when (this) {
        PrimaryTab.Notes -> stringResource(Res.string.tab_notes_title)
        PrimaryTab.Memories -> stringResource(Res.string.tab_memories_title)
        PrimaryTab.Settings -> stringResource(Res.string.tab_settings_title)
    }

private fun NavController.navigatePrimaryTab(tab: PrimaryTab) {
    when (tab) {
        PrimaryTab.Notes -> navigate(NotesRoute) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        PrimaryTab.Memories -> navigate(MemoriesRoute) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        PrimaryTab.Settings -> navigate(SettingsRoute) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private fun NavBackStackEntry.somedayRouteKind(): SomedayRouteKind =
    when (val route = destination.route.orEmpty()) {
        "notes" -> SomedayRouteKind.Notes
        "notes_search" -> SomedayRouteKind.NotesSearch
        "memories" -> SomedayRouteKind.Memories
        "settings" -> SomedayRouteKind.Settings
        else -> when {
            route.startsWith("note_editor") -> SomedayRouteKind.NoteEditor
            route.startsWith("note_conflict") -> SomedayRouteKind.NoteConflictResolution
            route.startsWith("settings_detail") -> SomedayRouteKind.SettingsDetail
            else -> SomedayRouteKind.Notes
        }
    }

private fun somedayNavEnterTransition(
    layoutSpec: SomedayLayoutSpec,
    from: SomedayRouteKind,
    to: SomedayRouteKind,
): EnterTransition =
    when {
        !layoutSpec.isCompact && usesPrimaryTabTransition(from, to) ->
            fadeIn(animationSpec = tween(durationMillis = 90, easing = SomedayPageFadeEasing))
        !layoutSpec.isCompact -> EnterTransition.None
        usesPrimaryTabTransition(from, to) ->
            fadeIn(animationSpec = tween(durationMillis = 150, easing = SomedayPageFadeEasing))
        else ->
            slideInHorizontally(
                animationSpec = tween(durationMillis = 420, easing = SomedayPageEnterEasing),
                initialOffsetX = { width -> width / 3 },
            ) + fadeIn(
                animationSpec = tween(durationMillis = 220, delayMillis = 60, easing = SomedayPageFadeEasing),
            )
    }

private fun somedayNavExitTransition(
    layoutSpec: SomedayLayoutSpec,
    from: SomedayRouteKind,
    to: SomedayRouteKind,
): ExitTransition =
    when {
        !layoutSpec.isCompact && usesPrimaryTabTransition(from, to) ->
            fadeOut(animationSpec = tween(durationMillis = 60, easing = SomedayPageFadeEasing))
        !layoutSpec.isCompact -> ExitTransition.None
        usesPrimaryTabTransition(from, to) ->
            fadeOut(animationSpec = tween(durationMillis = 90, easing = SomedayPageFadeEasing))
        else ->
            slideOutHorizontally(
                animationSpec = tween(durationMillis = 300, easing = SomedayPageExitEasing),
                targetOffsetX = { width -> -width / 5 },
            )
    }

private fun somedayNavPopEnterTransition(
    layoutSpec: SomedayLayoutSpec,
    from: SomedayRouteKind,
    to: SomedayRouteKind,
): EnterTransition =
    when {
        !layoutSpec.isCompact && usesPrimaryTabTransition(from, to) ->
            fadeIn(animationSpec = tween(durationMillis = 90, easing = SomedayPageFadeEasing))
        !layoutSpec.isCompact -> EnterTransition.None
        usesPrimaryTabTransition(from, to) ->
            fadeIn(animationSpec = tween(durationMillis = 150, easing = SomedayPageFadeEasing))
        else -> EnterTransition.None
    }

private fun somedayNavPopExitTransition(
    layoutSpec: SomedayLayoutSpec,
    from: SomedayRouteKind,
    to: SomedayRouteKind,
): ExitTransition =
    when {
        !layoutSpec.isCompact && usesPrimaryTabTransition(from, to) ->
            fadeOut(animationSpec = tween(durationMillis = 60, easing = SomedayPageFadeEasing))
        !layoutSpec.isCompact -> ExitTransition.None
        usesPrimaryTabTransition(from, to) ->
            fadeOut(animationSpec = tween(durationMillis = 90, easing = SomedayPageFadeEasing))
        else ->
            slideOutHorizontally(
                animationSpec = tween(durationMillis = 300, easing = SomedayPageExitEasing),
                targetOffsetX = { width -> width },
            )
    }

private fun usesPrimaryTabTransition(
    from: SomedayRouteKind,
    to: SomedayRouteKind,
): Boolean =
    from != to && from.isPrimaryTabRoot && to.isPrimaryTabRoot

private val SomedayRouteKind.isPrimaryTabRoot: Boolean
    get() = when (this) {
        SomedayRouteKind.Notes,
        SomedayRouteKind.Memories,
        SomedayRouteKind.Settings -> true
        SomedayRouteKind.NotesSearch,
        SomedayRouteKind.NoteEditor,
        SomedayRouteKind.NoteConflictResolution,
        SomedayRouteKind.SettingsDetail -> false
    }

@Composable
private fun NotesTabLazyContent(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    notesController: NotesUiController,
    notesSearchActive: Boolean,
    pullRefresh: SomedayPullRefreshUi?,
    onOpenNote: (String) -> Boolean,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var primarySelectionModifierPressed by remember { mutableStateOf(false) }
    var extendSelectionPressed by remember { mutableStateOf(false) }
    var batchDialog by rememberSaveable { mutableStateOf<NotesBatchDialog?>(null) }
    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            primarySelectionModifierPressed = event.isMetaPressed || event.isCtrlPressed
            extendSelectionPressed = event.isShiftPressed
            when {
                event.type == KeyEventType.KeyDown && primarySelectionModifierPressed && event.key == Key.A -> {
                    notesController.selectAllVisibleNotes()
                    true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                    notesState.noteSelectionActive -> {
                    notesController.clearNoteSelection()
                    true
                }
                else -> false
            }
        },
    ) {
        NotesPageHeader(
            selectedTab = selectedTab,
            notesState = notesState,
            memoriesState = memoriesState,
            notesSearchActive = notesSearchActive,
            onOpenSearch = onOpenSearch,
            onCloseSearch = onCloseSearch,
            onShowNotebooks = onShowNotebooks,
            onSelectAllNotes = notesController::selectAllVisibleNotes,
            onClearNoteSelection = notesController::clearNoteSelection,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = SomedayDesignDefaults.PageHorizontalPadding),
        )
        SomedaySyncPullToRefresh(
            pullRefresh = pullRefresh.takeUnless { notesSearchActive },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumnWithDragHandle(
                modifier = Modifier.fillMaxSize(),
                listStateKey = notesListStateKey(notesState, notesSearchActive),
                contentPadding = PaddingValues(
                    start = SomedayDesignDefaults.PageHorizontalPadding,
                    top = 12.dp,
                    end = SomedayDesignDefaults.PageHorizontalPadding,
                    bottom = 24.dp,
                ),
                dragLabelForItemIndex = notesDragMonthLabelProvider(notesState, notesSearchActive),
            ) {
                if (notesSearchActive) {
                    notesSearchItems(
                        state = notesState,
                        onSearchQueryChange = { query ->
                            coroutineScope.launch { notesController.updateSearchQuery(query) }
                        },
                        onClearSearch = notesController::clearSearch,
                        onOpenNote = { noteId -> onOpenNote(noteId) },
                        showSelectionControls = false,
                        selectionOnClick = notesState.noteSelectionActive || primarySelectionModifierPressed,
                        extendSelection = extendSelectionPressed,
                        onToggleSelection = notesController::toggleNoteSelection,
                    )
                } else {
                    notesListItems(
                        state = notesState,
                        onShowNotebooks = onShowNotebooks,
                        onOpenNote = { noteId -> onOpenNote(noteId) },
                        showSelectionControls = false,
                        selectionOnClick = notesState.noteSelectionActive || primarySelectionModifierPressed,
                        extendSelection = extendSelectionPressed,
                        onToggleSelection = notesController::toggleNoteSelection,
                    )
                }
            }
        }
        NotesBatchUndoBar(
            deletedCount = notesState.batchDeleteUndoItems.size,
            operationInProgress = notesState.batchOperationInProgress,
            onUndo = { coroutineScope.launch { notesController.undoLastBatchDelete() } },
            onDismiss = notesController::dismissBatchDeleteUndo,
        )
        if (selectedTab == PrimaryTab.Notes && notesState.noteSelectionActive) {
            NotesBatchActionBar(
                operationInProgress = notesState.batchOperationInProgress,
                onAction = { batchDialog = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SomedayDesignDefaults.PageHorizontalPadding)
                    .padding(bottom = 8.dp),
            )
        }
    }
    NotesBatchDialogs(
        dialog = batchDialog,
        state = notesState,
        onDismiss = { batchDialog = null },
        onMove = { notebookId -> coroutineScope.launch { notesController.moveSelectedNotes(notebookId) } },
        onDelete = { coroutineScope.launch { notesController.deleteSelectedNotes() } },
        onChangeDate = { date -> coroutineScope.launch { notesController.changeSelectedNotesCreatedDate(date) } },
        onChangeTimeZone = { zone -> coroutineScope.launch { notesController.changeSelectedNotesTimeZone(zone) } },
        onClearLocation = { coroutineScope.launch { notesController.clearSelectedNotesLocation() } },
    )
}

private data class SomedayPullRefreshUi(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SomedaySyncPullToRefresh(
    pullRefresh: SomedayPullRefreshUi?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (pullRefresh == null) {
        Box(modifier = modifier) {
            content()
        }
        return
    }
    PullToRefreshBox(
        isRefreshing = pullRefresh.refreshing,
        onRefresh = pullRefresh.onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun LazyColumnWithDragHandle(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listStateKey: Any? = Unit,
    dragLabelForItemIndex: (Int) -> String? = { null },
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberSaveable(listStateKey, saver = LazyListState.Saver) { LazyListState() }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            content = content,
        )
        AutoHidingLazyListDragHandle(
            listState = listState,
            dragLabelForItemIndex = dragLabelForItemIndex,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private fun notesListStateKey(
    state: NotesUiState,
    notesSearchActive: Boolean,
): String =
    if (notesSearchActive) {
        "search"
    } else {
        "notebook:${state.selectedNotebookId.orEmpty()}"
    }

@Composable
private fun AutoHidingLazyListDragHandle(
    listState: LazyListState,
    dragLabelForItemIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var handleVisible by remember { mutableStateOf(false) }
    var handleDragging by remember { mutableStateOf(false) }
    var activeDragLabel by remember { mutableStateOf<String?>(null) }
    val minThumbSizePx = with(density) { 52.dp.toPx() }
    val metrics by remember(listState, minThumbSizePx) {
        derivedStateOf { listState.scrollbarMetrics(minThumbSizePx) }
    }
    val latestMetrics = rememberUpdatedState(metrics)

    LaunchedEffect(metrics.canScroll, metrics.totalItems, listState.isScrollInProgress, handleDragging) {
        if (!metrics.canScroll) {
            handleVisible = false
        } else if (listState.isScrollInProgress || handleDragging) {
            handleVisible = true
        } else {
            handleVisible = true
            delay(1_200)
            handleVisible = false
        }
    }

    Box(modifier = modifier.width(LazyListDragHandleOverlayWidth)) {
        val thumbShape = RoundedCornerShape(999.dp)
        val thumbHeight = with(density) { metrics.thumbSizePx.toDp() }
        val thumbOffset = with(density) { metrics.thumbOffsetPx.toDp() }
        AnimatedVisibility(
            visible = metrics.canScroll && handleVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = fadeOut(animationSpec = tween(durationMillis = 420)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbOffset)
                .padding(end = 4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = handleDragging && activeDragLabel != null,
                    enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 90)),
                ) {
                    activeDragLabel?.let { label ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        ) {
                            Text(
                                text = label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(thumbHeight)
                        .pointerInput(listState, dragLabelForItemIndex) {
                            var dragThumbOffsetPx = 0f
                            detectDragGestures(
                                onDragStart = {
                                    val dragMetrics = latestMetrics.value
                                    dragThumbOffsetPx = dragMetrics.thumbOffsetPx
                                    activeDragLabel = dragLabelForItemIndex(dragMetrics.firstVisibleItemIndex)
                                    handleDragging = true
                                    handleVisible = true
                                },
                                onDragCancel = { handleDragging = false },
                                onDragEnd = { handleDragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                val dragMetrics = latestMetrics.value
                                if (!dragMetrics.canScroll || dragMetrics.maxThumbOffsetPx <= 0f) {
                                    activeDragLabel = null
                                    return@detectDragGestures
                                }
                                dragThumbOffsetPx = (dragThumbOffsetPx + dragAmount.y)
                                    .coerceIn(0f, dragMetrics.maxThumbOffsetPx)
                                val scrollProgress = dragThumbOffsetPx / dragMetrics.maxThumbOffsetPx
                                val targetScrollOffset = scrollProgress * dragMetrics.maxScrollOffsetPx
                                val targetIndex = (targetScrollOffset / dragMetrics.averageItemSizePx)
                                    .toInt()
                                    .coerceIn(0, dragMetrics.totalItems - 1)
                                val targetItemOffset = (targetScrollOffset - targetIndex * dragMetrics.averageItemSizePx)
                                    .roundToInt()
                                    .coerceAtLeast(0)
                                activeDragLabel = dragLabelForItemIndex(targetIndex)
                                coroutineScope.launch {
                                    listState.scrollToItem(targetIndex, targetItemOffset)
                                }
                            }
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(8.dp)
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (handleDragging) 0.46f else 0.20f,
                                ),
                                thumbShape,
                            ),
                    )
                }
            }
        }
    }
}

private data class LazyListScrollbarMetrics(
    val canScroll: Boolean = false,
    val totalItems: Int = 0,
    val firstVisibleItemIndex: Int = 0,
    val averageItemSizePx: Float = 1f,
    val maxScrollOffsetPx: Float = 0f,
    val maxThumbOffsetPx: Float = 0f,
    val thumbSizePx: Float = 0f,
    val thumbOffsetPx: Float = 0f,
)

private fun LazyListState.scrollbarMetrics(minThumbSizePx: Float): LazyListScrollbarMetrics {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= 0 || visibleItems.isEmpty()) {
        return LazyListScrollbarMetrics(totalItems = totalItems)
    }

    val viewportSizePx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val firstVisibleItem = visibleItems.first()
    val lastVisibleItem = visibleItems.last()
    val visibleItemsSpanPx = (lastVisibleItem.offset + lastVisibleItem.size - firstVisibleItem.offset)
        .coerceAtLeast(1)
    val averageItemSizePx = (visibleItemsSpanPx.toFloat() / visibleItems.size).coerceAtLeast(1f)
    val estimatedContentSizePx = averageItemSizePx * totalItems
    if (estimatedContentSizePx <= viewportSizePx + 1f) {
        return LazyListScrollbarMetrics(totalItems = totalItems, averageItemSizePx = averageItemSizePx)
    }

    val maxScrollOffsetPx = (estimatedContentSizePx - viewportSizePx).coerceAtLeast(1f)
    val scrollOffsetPx = firstVisibleItemIndex * averageItemSizePx + firstVisibleItemScrollOffset
    val minBoundedThumbSizePx = minThumbSizePx.coerceAtMost(viewportSizePx.toFloat())
    val thumbSizePx = ((viewportSizePx * viewportSizePx) / estimatedContentSizePx)
        .coerceIn(minBoundedThumbSizePx, viewportSizePx.toFloat())
    val maxThumbOffsetPx = (viewportSizePx - thumbSizePx).coerceAtLeast(0f)
    val thumbOffsetPx = ((scrollOffsetPx / maxScrollOffsetPx) * maxThumbOffsetPx)
        .coerceIn(0f, maxThumbOffsetPx)

    return LazyListScrollbarMetrics(
        canScroll = maxThumbOffsetPx > 0f,
        totalItems = totalItems,
        firstVisibleItemIndex = firstVisibleItemIndex,
        averageItemSizePx = averageItemSizePx,
        maxScrollOffsetPx = maxScrollOffsetPx,
        maxThumbOffsetPx = maxThumbOffsetPx,
        thumbSizePx = thumbSizePx,
        thumbOffsetPx = thumbOffsetPx,
    )
}

@Composable
private fun MemoriesTabLazyContent(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    pullRefresh: SomedayPullRefreshUi?,
    onGoToMonth: (MemoryMonth) -> Unit,
    onSelectDay: (Int) -> Unit,
    onOpenNote: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        AppHeader(
            title = pageHeaderTitle(selectedTab, notesState, notesSearchActive = false),
            subtitle = pageHeaderSubtitle(selectedTab, notesState, memoriesState, notesSearchActive = false),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = SomedayDesignDefaults.PageHorizontalPadding),
        )
        SomedaySyncPullToRefresh(
            pullRefresh = pullRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SomedayDesignDefaults.PageHorizontalPadding,
                    top = 16.dp,
                    end = SomedayDesignDefaults.PageHorizontalPadding,
                    bottom = 24.dp,
                ),
            ) {
                memoriesContentItems(
                    state = memoriesState,
                    onGoToMonth = onGoToMonth,
                    onSelectDay = onSelectDay,
                    onOpenNote = onOpenNote,
                )
            }
        }
    }
}

@Composable
private fun NotesPageHeader(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    notesSearchActive: Boolean,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShowNotebooks: () -> Unit,
    onSelectAllNotes: () -> Unit,
    onClearNoteSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppHeader(
        title = if (selectedTab == PrimaryTab.Notes && notesState.noteSelectionActive) {
            stringResource(Res.string.notes_batch_selected_count, notesState.selectedNoteIds.size)
        } else {
            pageHeaderTitle(selectedTab, notesState, notesSearchActive)
        },
        subtitle = if (selectedTab == PrimaryTab.Notes && notesState.noteSelectionActive) {
            null
        } else {
            pageHeaderSubtitle(selectedTab, notesState, memoriesState, notesSearchActive)
        },
        modifier = modifier,
    ) {
        if (selectedTab == PrimaryTab.Notes && notesState.noteSelectionActive) {
            IconButton(onClick = onSelectAllNotes, enabled = !notesState.batchOperationInProgress) {
                Icon(Lucide.Check, contentDescription = stringResource(Res.string.common_select_all))
            }
            IconButton(onClick = onClearNoteSelection, enabled = !notesState.batchOperationInProgress) {
                Icon(Lucide.X, contentDescription = stringResource(Res.string.common_close))
            }
        } else if (notesSearchActive) {
            IconButton(onClick = onCloseSearch) {
                Icon(Lucide.X, contentDescription = stringResource(Res.string.notes_close_search))
            }
        } else {
            IconButton(onClick = onOpenSearch, enabled = notesState.notebooks.isNotEmpty()) {
                Icon(Lucide.Search, contentDescription = stringResource(Res.string.common_search))
            }
            IconButton(onClick = onShowNotebooks) {
                Icon(Lucide.NotebookTabs, contentDescription = stringResource(Res.string.nav_notebooks))
            }
        }
    }
}

private fun primaryTabIcon(tab: PrimaryTab): ImageVector =
    when (tab) {
        PrimaryTab.Notes -> Lucide.NotebookText
        PrimaryTab.Memories -> Lucide.CalendarDays
        PrimaryTab.Settings -> Lucide.Settings2
    }

@Composable
private fun pageHeaderTitle(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    notesSearchActive: Boolean,
): String =
    when (selectedTab) {
        PrimaryTab.Notes -> if (notesSearchActive) {
            stringResource(Res.string.common_search)
        } else {
            notesState.selectedNotebook?.title ?: stringResource(Res.string.tab_notes)
        }
        PrimaryTab.Memories -> stringResource(Res.string.tab_memories)
        PrimaryTab.Settings -> stringResource(Res.string.tab_settings)
    }

@Composable
private fun pageHeaderSubtitle(
    selectedTab: PrimaryTab,
    notesState: NotesUiState,
    memoriesState: MemoriesUiState,
    notesSearchActive: Boolean,
): String? =
    when (selectedTab) {
        PrimaryTab.Notes -> if (notesSearchActive) {
            null
        } else if (notesState.selectedNotebook == null) {
            null
        } else {
            stringResource(Res.string.status_notes_count, notesState.notes.size)
        }
        PrimaryTab.Memories -> memoriesState.month.label
        PrimaryTab.Settings -> null
    }

@Composable
private fun AppHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitleBlock(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

@Composable
private fun HeaderTitleBlock(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title.withLeadingCapital(),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun String.withLeadingCapital(): String =
    if (isEmpty()) this else first().uppercaseChar() + drop(1)

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
    ) {
        SomedayIconBadge(
            icon = icon,
            size = 54.dp,
            iconSize = 25.dp,
        )
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SomedayIconBadge(
            icon = icon,
            size = 34.dp,
            iconSize = 18.dp,
        )
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun LazyListScope.notesListItems(
    state: NotesUiState,
    onShowNotebooks: () -> Unit,
    onOpenNote: (String) -> Unit,
    showSelectionControls: Boolean,
    selectionOnClick: Boolean,
    extendSelection: Boolean,
    onToggleSelection: (String, Boolean) -> Unit,
) {
    if (state.notebooks.isEmpty()) {
        item(key = "notes-empty-notebooks", contentType = "empty-state") {
            Spacer(modifier = Modifier.height(24.dp))
            EmptyState(
                icon = Lucide.NotebookText,
                title = stringResource(Res.string.nav_no_notebooks),
            ) {
                Button(onClick = onShowNotebooks) {
                    Text(stringResource(Res.string.nav_create_notebook))
                }
            }
        }
        return
    }

    val notes = state.notes
    if (notes.isEmpty()) {
        item(key = "notes-empty", contentType = "empty-state") {
            EmptyState(
                icon = Lucide.BookOpenText,
                title = stringResource(Res.string.notes_no_notes),
            )
        }
    } else {
        items(
            items = notes,
            key = { note -> note.id },
            contentType = { "note-row" },
        ) { note ->
            NoteListItem(
                note = note,
                metadataText = formatNoteCreatedMetadata(note),
                onOpenNote = onOpenNote,
                selected = note.id in state.selectedNoteIds,
                showSelectionControl = showSelectionControls || state.noteSelectionActive,
                selectionOnClick = selectionOnClick,
                extendSelection = extendSelection,
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

private fun notesDragMonthLabelProvider(
    state: NotesUiState,
    notesSearchActive: Boolean,
): (Int) -> String? =
    if (notesSearchActive) {
        { itemIndex ->
            state.searchResults
                .getOrNull(itemIndex - NotesSearchResultStartIndex)
                ?.let(::formatNoteCreatedMonth)
        }
    } else {
        { itemIndex ->
            state.notes
                .getOrNull(itemIndex)
                ?.let(::formatNoteCreatedMonth)
        }
    }

private fun LazyListScope.notesSearchItems(
    state: NotesUiState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenNote: (String) -> Unit,
    showSelectionControls: Boolean,
    selectionOnClick: Boolean,
    extendSelection: Boolean,
    onToggleSelection: (String, Boolean) -> Unit,
) {
    item(key = "notes-search-field", contentType = "search-field") {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotBlank()) {
                    IconButton(onClick = onClearSearch) {
                        Icon(Lucide.X, contentDescription = stringResource(Res.string.notes_clear_search))
                    }
                }
            },
            label = { Text(stringResource(Res.string.notes_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "notes-search-field-spacer", contentType = "spacer") {
        Spacer(modifier = Modifier.height(12.dp))
    }
    val results = state.searchResults
    if (state.searchQuery.isBlank()) {
        item(key = "notes-search-empty-query", contentType = "empty-state") {
            EmptyState(
                icon = Lucide.Search,
                title = stringResource(Res.string.notes_search_prompt),
                body = stringResource(Res.string.notes_search_placeholder),
            )
        }
    } else if (results.isEmpty()) {
        item(key = "notes-search-no-matches", contentType = "empty-state") {
            EmptyState(
                icon = Lucide.Search,
                title = stringResource(Res.string.notes_no_matches),
            )
        }
    } else {
        item(key = "notes-search-count", contentType = "section-label") {
            Text(
                stringResource(Res.string.notes_matches_count, results.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(
            items = results,
            key = { note -> "search-${note.id}" },
            contentType = { "note-row" },
        ) { note ->
            NoteListItem(
                note = note,
                metadataText = formatNoteCreatedThenUpdatedMetadata(note),
                onOpenNote = onOpenNote,
                selected = note.id in state.selectedNoteIds,
                showSelectionControl = showSelectionControls || state.noteSelectionActive,
                selectionOnClick = selectionOnClick,
                extendSelection = extendSelection,
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

@Composable
private fun NoteListItem(
    note: NoteSummary,
    metadataText: String,
    onOpenNote: (String) -> Unit,
    selected: Boolean = false,
    showSelectionControl: Boolean = false,
    selectionOnClick: Boolean = false,
    extendSelection: Boolean = false,
    onToggleSelection: ((String, Boolean) -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    SomedayListCard(
        onClick = {
            if (selectionOnClick) {
                onToggleSelection?.invoke(note.id, extendSelection)
            } else {
                onOpenNote(note.id)
            }
        },
        onLongClick = onToggleSelection?.let { toggle ->
            {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                toggle(note.id, false)
            }
        },
        containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSelectionControl && onToggleSelection != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection.invoke(note.id, extendSelection) },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    metadataText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            NoteSyncIndicator(
                syncBadge = note.syncBadge,
                showText = false,
            )
        }
        val excerpt = note.excerpt.trim()
        if (excerpt.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = excerpt,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private enum class NotesBatchDialog {
    Move,
    Delete,
    ChangeDate,
    ChangeTimeZone,
    ClearLocation,
}

@Composable
private fun NotesBatchActionBar(
    operationInProgress: Boolean,
    onAction: (NotesBatchDialog) -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(
        shape = SomedayDesignDefaults.ContentCardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(
                onClick = { onAction(NotesBatchDialog.Move) },
                enabled = !operationInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Lucide.NotebookTabs, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.common_move))
            }
            TextButton(
                onClick = { onAction(NotesBatchDialog.Delete) },
                enabled = !operationInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Lucide.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.common_delete))
            }
            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { moreExpanded = true },
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Lucide.Ellipsis, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.common_more))
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.notes_batch_change_date)) },
                        leadingIcon = { Icon(Lucide.CalendarDays, contentDescription = null) },
                        onClick = {
                            moreExpanded = false
                            onAction(NotesBatchDialog.ChangeDate)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.notes_batch_change_timezone)) },
                        leadingIcon = { Icon(Lucide.Globe, contentDescription = null) },
                        onClick = {
                            moreExpanded = false
                            onAction(NotesBatchDialog.ChangeTimeZone)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.notes_batch_clear_location)) },
                        leadingIcon = { Icon(Lucide.MapPin, contentDescription = null) },
                        onClick = {
                            moreExpanded = false
                            onAction(NotesBatchDialog.ClearLocation)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesBatchUndoBar(
    deletedCount: Int,
    operationInProgress: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (deletedCount == 0) return
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = SomedayDesignDefaults.ContentCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SomedayDesignDefaults.PageHorizontalPadding, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
        ) {
            Text(
                stringResource(Res.string.notes_batch_undo_deleted, deletedCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, enabled = !operationInProgress) {
                Text(stringResource(Res.string.common_undo))
            }
            IconButton(onClick = onDismiss, enabled = !operationInProgress) {
                Icon(Lucide.X, contentDescription = stringResource(Res.string.common_close))
            }
        }
    }
}

@Composable
private fun NotesBatchDialogs(
    dialog: NotesBatchDialog?,
    state: NotesUiState,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onChangeDate: (LocalDate) -> Unit,
    onChangeTimeZone: (String?) -> Unit,
    onClearLocation: () -> Unit,
) {
    val selectedNotes = state.visibleNotes.filter { it.id in state.selectedNoteIds }
    when (dialog) {
        NotesBatchDialog.Move -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.notes_batch_move_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    state.notebooks
                        .filter { it.syncBadge !is NoteSyncBadge.Error }
                        .forEach { notebook ->
                            val changesAnyNote = selectedNotes.any { it.notebookId != notebook.id }
                            TextButton(
                                onClick = {
                                    onDismiss()
                                    onMove(notebook.id)
                                },
                                enabled = changesAnyNote && !state.batchOperationInProgress,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(notebook.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
        NotesBatchDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.notes_batch_delete_title)) },
            text = { Text(stringResource(Res.string.notes_batch_delete_body, selectedNotes.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        onDelete()
                    },
                    enabled = selectedNotes.isNotEmpty() && !state.batchOperationInProgress,
                ) { Text(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
        NotesBatchDialog.ChangeDate -> {
            val initialDate = selectedNotes.firstOrNull()
                ?.let { noteCalendarDate(it.createdAt, it.timeZoneId).toString() }
                .orEmpty()
            var dateText by remember(dialog, initialDate) { mutableStateOf(initialDate) }
            val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(Res.string.notes_batch_change_date_title)) },
                text = {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text(stringResource(Res.string.common_date)) },
                        supportingText = if (date == null) {
                            { Text(stringResource(Res.string.notes_fb_date_format)) }
                        } else {
                            null
                        },
                        isError = date == null,
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onChangeDate(checkNotNull(date))
                        },
                        enabled = date != null && !state.batchOperationInProgress,
                    ) { Text(stringResource(Res.string.common_save)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }
        NotesBatchDialog.ChangeTimeZone -> {
            val initialZone = selectedNotes.firstOrNull()?.timeZoneId.orEmpty()
            var zoneText by remember(dialog, initialZone) { mutableStateOf(initialZone) }
            val normalized = zoneText.trim().ifBlank { null }
            val valid = normalized == null || runCatching { TimeZone.of(normalized) }.isSuccess
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(Res.string.notes_batch_change_timezone_title)) },
                text = {
                    OutlinedTextField(
                        value = zoneText,
                        onValueChange = { zoneText = it },
                        label = { Text(stringResource(Res.string.notes_batch_change_timezone)) },
                        supportingText = {
                            Text(
                                if (valid) {
                                    stringResource(Res.string.notes_batch_timezone_hint)
                                } else {
                                    stringResource(Res.string.notes_batch_timezone_invalid)
                                },
                            )
                        },
                        isError = !valid,
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onChangeTimeZone(normalized)
                        },
                        enabled = valid && !state.batchOperationInProgress,
                    ) { Text(stringResource(Res.string.common_save)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }
        NotesBatchDialog.ClearLocation -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.notes_batch_clear_location)) },
            text = { Text(stringResource(Res.string.notes_batch_clear_location_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        onClearLocation()
                    },
                    enabled = !state.batchOperationInProgress,
                ) { Text(stringResource(Res.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun formatNoteCreatedMetadata(note: NoteSummary): String =
    stringResource(Res.string.note_created, noteCalendarDate(note.createdAt, note.timeZoneId).toString())

private fun formatNoteCreatedMonth(note: NoteSummary): String {
    val date = noteCalendarDate(note.createdAt, note.timeZoneId)
    val month = (date.month.ordinal + 1).toString().padStart(2, '0')
    return "${date.year}-$month"
}

@Composable
private fun formatNoteUpdatedMetadata(note: NoteSummary): String =
    formatNoteUpdatedAt(note.updatedAt)

@Composable
private fun formatNoteCreatedThenUpdatedMetadata(note: NoteSummary): String =
    stringResource(Res.string.note_created_updated, noteCalendarDate(note.createdAt, note.timeZoneId).toString(), formatNoteUpdatedAt(note.updatedAt))

@Composable
private fun formatNoteUpdatedAt(updatedAt: Instant): String {
    val local = updatedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return stringResource(Res.string.note_updated, local.date.toString(), "$hour:$minute")
}

private fun LazyListScope.memoriesContentItems(
    state: MemoriesUiState,
    onGoToMonth: (MemoryMonth) -> Unit,
    onSelectDay: (Int) -> Unit,
    onOpenNote: (String) -> Boolean,
) {
    item(key = "memories-calendar", contentType = "calendar") {
        val scope = rememberCoroutineScope()
        val initialMonth = remember { state.month }
        val pagerState = rememberPagerState(
            initialPage = CALENDAR_PAGER_CENTER,
            pageCount = { CALENDAR_PAGER_COUNT },
        )
        LaunchedEffect(pagerState.settledPage) {
            val target = initialMonth.shiftedBy(pagerState.settledPage - CALENDAR_PAGER_CENTER)
            if (target != state.month) onGoToMonth(target)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }) {
                Icon(Lucide.ChevronLeft, contentDescription = stringResource(Res.string.memories_previous_month))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.month.label, style = MaterialTheme.typography.titleMedium)
                state.feedbackMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                Icon(Lucide.ChevronRight, contentDescription = stringResource(Res.string.memories_next_month))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { page ->
            val month = initialMonth.shiftedBy(page - CALENDAR_PAGER_CENTER)
            val isCurrentMonth = month == state.month
            val dayCells = (1..month.daysInMonth).map { day ->
                val date = month.dateForDay(day)
                MemoryCalendarDay(
                    date = date,
                    noteCount = if (isCurrentMonth) state.dayCounts.firstOrNull { it.date == date }?.noteCount ?: 0 else 0,
                    selected = isCurrentMonth && date == state.selectedDate,
                )
            }
            val leadingBlanks = month.dateForDay(1).dayOfWeek.ordinal
            val cells: List<MemoryCalendarDay?> = List(leadingBlanks) { null } + dayCells
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(
                        stringResource(Res.string.memories_weekday_mon),
                        stringResource(Res.string.memories_weekday_tue),
                        stringResource(Res.string.memories_weekday_wed),
                        stringResource(Res.string.memories_weekday_thu),
                        stringResource(Res.string.memories_weekday_fri),
                        stringResource(Res.string.memories_weekday_sat),
                        stringResource(Res.string.memories_weekday_sun),
                    ).forEach { weekday ->
                        Text(
                            text = weekday,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                cells.chunked(7).forEach { week ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(modifier = Modifier.weight(1f).height(52.dp))
                            } else {
                                MemoryDayCell(
                                    day = day,
                                    onSelectDay = if (isCurrentMonth) onSelectDay else ({ _ -> }),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        repeat(7 - week.size) {
                            Spacer(modifier = Modifier.weight(1f).height(52.dp))
                        }
                    }
                }
            }
        }
    }
    item(key = "memories-selected-section-spacer", contentType = "spacer") {
        Spacer(modifier = Modifier.height(24.dp))
    }
    item(key = "memories-selected-section-header", contentType = "section-header") {
        SectionHeader(
            icon = Lucide.CalendarDays,
            title = stringResource(Res.string.memories_on_date, state.selectedDate.toString()),
            subtitle = stringResource(Res.string.memories_notes_count, state.selectedDayNotes.size),
        )
    }
    noteSummaryListItems(
        notes = state.selectedDayNotes,
        emptyMessage = Res.string.memories_no_notes_on_date,
        onOpenNote = onOpenNote,
        keyPrefix = "selected-day",
        metadataText = { formatNoteUpdatedMetadata(it) },
    )
    item(key = "memories-prior-section-spacer", contentType = "spacer") {
        Spacer(modifier = Modifier.height(20.dp))
    }
    item(key = "memories-prior-section-header", contentType = "section-header") {
        SectionHeader(
            icon = Lucide.History,
            title = stringResource(Res.string.memories_past_years),
            subtitle = stringResource(Res.string.memories_memories_count, state.priorYearNotes.size),
        )
    }
    noteSummaryListItems(
        notes = state.priorYearNotes,
        emptyMessage = Res.string.memories_no_past_memories,
        onOpenNote = onOpenNote,
        keyPrefix = "prior-year",
        metadataText = { formatNoteCreatedThenUpdatedMetadata(it) },
    )
}

@Composable
private fun MemoryDayCell(
    day: MemoryCalendarDay,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        day.selected -> MaterialTheme.colorScheme.primaryContainer
        day.noteCount > 0 -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.background
    }
    val contentColor = if (day.selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = { onSelectDay(day.date.day) },
        shape = SomedayDesignDefaults.CellShape,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                day.date.day.toString(),
                fontWeight = if (day.selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(if (day.noteCount == 0) " " else day.noteCount.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SettingsContent(
    page: SettingsPage?,
    state: SettingsUiState,
    controller: SettingsUiController,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    developerOptions: DeveloperOptionsUi? = null,
    workspacePairingScanner: WorkspacePairingScanner,
    actionScope: CoroutineScope,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier,
    ) {
        val pageModifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 760.dp)
        if (page == null) {
            SettingsPageScaffold(
                title = stringResource(Res.string.tab_settings),
                modifier = pageModifier,
            ) {
                SettingsMainContent(
                    state = state,
                    controller = controller,
                    onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                    developerOptions = developerOptions,
                    onOpenPage = onOpenPage,
                )
            }
        } else {
            SettingsPageScaffold(
                title = settingsPageTitle(page),
                onBack = onBack,
                modifier = pageModifier,
            ) {
                SettingsDetailContent(
                    page = page,
                    state = state,
                    controller = controller,
                    onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                    developerOptions = developerOptions,
                    workspacePairingScanner = workspacePairingScanner,
                    actionScope = actionScope,
                )
            }
        }
    }
}

@Composable
private fun WorkspacePreferencesStatusSection(
    state: SettingsUiState,
    controller: SettingsUiController,
) {
    val syncState = state.settings.workspacePreferencesState
    val conflict = syncState.conflict
    val warning = syncState.warning
    if (syncState.status != WorkspacePreferencesSyncStatus.Conflict || conflict == null) {
        if (warning != null && syncState.status == WorkspacePreferencesSyncStatus.Warning) {
            SettingsSection(title = stringResource(Res.string.settings_synchronized_preferences)) {
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        return
    }
    val coroutineScope = rememberCoroutineScope()
    SettingsSection(title = stringResource(Res.string.settings_preferences_conflict)) {
        Text(
            text = syncState.warning
                ?: stringResource(Res.string.settings_preferences_conflict_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        conflict.branches.forEachIndexed { index, branch ->
            if (index > 0) SettingsDivider()
            SettingsActionRow(
                icon = Lucide.History,
                title = stringResource(Res.string.settings_branch_theme, index + 1, branch.theme.localizedLabel()),
                subtitle = "preview=${branch.previewByDefault}, toolbar=${branch.markdownToolbarVisible}, " +
                    "default=${branch.defaultNotebookId ?: "none"}",
                actionText = stringResource(Res.string.common_use),
                onClick = {
                    coroutineScope.launch { controller.resolveWorkspacePreferencesBranch(branch.versionId) }
                },
            )
        }
    }
}

@Composable
private fun SettingsMainContent(
    state: SettingsUiState,
    controller: SettingsUiController,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    developerOptions: DeveloperOptionsUi?,
    onOpenPage: (SettingsPage) -> Unit,
) {
    WorkspacePreferencesStatusSection(state, controller)

    SettingsSection(title = stringResource(Res.string.settings_general)) {
        SettingsNavigationRow(
            icon = Lucide.Palette,
            title = stringResource(Res.string.settings_appearance),
            subtitle = stringResource(Res.string.settings_theme_subtitle, state.settings.theme.localizedLabel()),
            onClick = { onOpenPage(SettingsPage.Appearance) },
        )
        SettingsDivider()
        SettingsNavigationRow(
            icon = Lucide.Pencil,
            title = stringResource(Res.string.settings_editor),
            subtitle = if (state.settings.editorPreferences.previewByDefault) {
                stringResource(Res.string.settings_preview_opens_first)
            } else {
                stringResource(Res.string.settings_source_opens_first)
            },
            onClick = { onOpenPage(SettingsPage.Editor) },
        )
        if (controller.onThisDayNotificationsSupported) {
            SettingsDivider()
            val notifications = state.settings.onThisDayNotifications
            SettingsNavigationRow(
                icon = Lucide.Bell,
                title = stringResource(Res.string.on_this_day_settings_navigation_title),
                subtitle = if (notifications.enabled) {
                    stringResource(
                        Res.string.on_this_day_settings_navigation_enabled,
                        onThisDayNotificationTimeFormatter.format(notifications.hour, notifications.minute),
                    )
                } else {
                    stringResource(Res.string.on_this_day_settings_navigation_disabled)
                },
                onClick = { onOpenPage(SettingsPage.Notifications) },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.common_sync)) {
        SettingsNavigationRow(
            icon = Lucide.Cloud,
            title = stringResource(Res.string.common_sync),
            subtitle = when {
                state.sync.syncing -> stringResource(Res.string.sync_status_syncing)
                state.sync.issue != null -> stringResource(Res.string.sync_last_issue)
                state.sync.connection is SyncConnectionUi.Connected -> stringResource(Res.string.common_signed_in)
                else -> stringResource(Res.string.common_not_configured)
            },
            onClick = { onOpenPage(SettingsPage.Sync) },
        )
        SettingsDivider()
        SettingsNavigationRow(
            icon = Lucide.Download,
            title = stringResource(Res.string.common_import),
            subtitle = state.importSummary?.message,
            onClick = { onOpenPage(SettingsPage.Import) },
        )
        SettingsDivider()
        SettingsNavigationRow(
            icon = Lucide.Download,
            title = stringResource(Res.string.common_export),
            onClick = { onOpenPage(SettingsPage.Export) },
        )
    }

    if (developerOptions != null) {
        SettingsSection(title = stringResource(Res.string.settings_debug)) {
            SettingsNavigationRow(
                icon = Lucide.Server,
                title = stringResource(Res.string.settings_developer_options),
                subtitle = developerOptions.buildTypeLabel,
                onClick = { onOpenPage(SettingsPage.Developer) },
            )
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    SettingsListItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        },
    )
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SomedayLiquidGlassHost {
        Box(modifier = modifier.fillMaxSize()) {
            SomedayLiquidGlassBackdrop(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 78.dp, bottom = 24.dp),
                ) {
                    content()
                }
            }
            PageNavigationBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun PageNavigationBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    SomedayLiquidGlassTopBar(modifier = modifier.fillMaxWidth().height(64.dp)) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.width(54.dp).fillMaxHeight(),
        ) {
            if (onBack != null) {
                SomedayLiquidGlassIconButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = stringResource(Res.string.common_back),
                    onClick = onBack,
                )
            }
        }
        NavigationTitleText(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(54.dp))
    }
}

@Composable
private fun SettingsDetailContent(
    page: SettingsPage,
    state: SettingsUiState,
    controller: SettingsUiController,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
    developerOptions: DeveloperOptionsUi?,
    workspacePairingScanner: WorkspacePairingScanner,
    actionScope: CoroutineScope,
) {
    WorkspacePreferencesStatusSection(state, controller)
    when (page) {
        SettingsPage.Appearance -> AppearanceSettingsContent(state = state, controller = controller)
        SettingsPage.Editor -> EditorSettingsContent(state = state, controller = controller)
        SettingsPage.Notifications -> NotificationsSettingsContent(
            state = state,
            controller = controller,
            onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
        )
        SettingsPage.Sync -> SyncSettingsContent(
            state = state,
            controller = controller,
            workspacePairingScanner = workspacePairingScanner,
            actionScope = actionScope,
        )
        SettingsPage.Import -> ImportSettingsContent(state = state, controller = controller)
        SettingsPage.Export -> ExportSettingsContent(state = state, controller = controller)
        SettingsPage.Developer -> developerOptions?.let { DeveloperSettingsContent(options = it) }
    }
}

@Composable
private fun settingsPageTitle(page: SettingsPage): String =
    when (page) {
        SettingsPage.Appearance -> stringResource(Res.string.settings_appearance)
        SettingsPage.Editor -> stringResource(Res.string.settings_editor)
        SettingsPage.Notifications -> stringResource(Res.string.on_this_day_settings_navigation_title)
        SettingsPage.Sync -> stringResource(Res.string.common_sync)
        SettingsPage.Import -> stringResource(Res.string.common_import)
        SettingsPage.Export -> stringResource(Res.string.common_export)
        SettingsPage.Developer -> stringResource(Res.string.settings_developer_options)
    }

@Composable
private fun DeveloperSettingsContent(options: DeveloperOptionsUi) {
    val coroutineScope = rememberCoroutineScope()
    var activeAction by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    fun runDeveloperAction(
        key: String,
        action: suspend () -> String,
    ) {
        if (activeAction != null) {
            return
        }
        coroutineScope.launch {
            activeAction = key
            lastResult = runCatching { action() }.getOrElse { failure ->
                "Developer action failed: ${failure.message ?: "unknown error"}"
            }
            activeAction = null
        }
    }

    SettingsSection(title = stringResource(Res.string.dev_demo_data)) {
        DeveloperActionRow(
            icon = Lucide.NotebookTabs,
            title = stringResource(Res.string.dev_demo_title),
            subtitle = stringResource(Res.string.dev_demo_subtitle),
            buttonText = stringResource(Res.string.common_create),
            busy = activeAction == "seed",
            enabled = activeAction == null,
            onClick = { runDeveloperAction("seed", options.seedMockContent) },
        )
        HorizontalDivider()
        DeveloperActionRow(
            icon = Lucide.X,
            title = stringResource(Res.string.dev_clear_demo_title),
            subtitle = stringResource(Res.string.dev_clear_demo_subtitle),
            buttonText = stringResource(Res.string.common_clear),
            busy = activeAction == "clear",
            enabled = activeAction == null,
            onClick = { runDeveloperAction("clear", options.clearMockContent) },
        )
    }

    SettingsSection(title = stringResource(Res.string.dev_runtime)) {
        DeveloperActionRow(
            icon = Lucide.History,
            title = stringResource(Res.string.dev_refresh_title),
            subtitle = stringResource(Res.string.dev_refresh_subtitle),
            buttonText = stringResource(Res.string.common_refresh),
            busy = activeAction == "refresh",
            enabled = activeAction == null,
            onClick = { runDeveloperAction("refresh", options.refreshLocalState) },
        )
    }

    SettingsSection(title = stringResource(Res.string.dev_diagnostics)) {
        StatusLine(stringResource(Res.string.dev_platform), options.platformName)
        StatusLine(stringResource(Res.string.dev_build), options.buildTypeLabel)
        StatusLine(stringResource(Res.string.nav_notebooks), options.notebookCount.toString())
        StatusLine(stringResource(Res.string.dev_selected_notebook), options.selectedNotebookTitle)
        StatusLine(stringResource(Res.string.dev_selected_notes), options.selectedNotebookNoteCount.toString())
        StatusLine(stringResource(Res.string.settings_sync_mode), options.syncModeLabel)
        lastResult?.let { result ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = result,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DeveloperActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsActionRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        actionText = buttonText,
        busy = busy,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun AppearanceSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
) {
    val coroutineScope = rememberCoroutineScope()
    val workspaceEditable = state.settings.workspacePreferencesState.status != WorkspacePreferencesSyncStatus.Conflict
    var themeDialogVisible by remember { mutableStateOf(false) }
    var languageDialogVisible by remember { mutableStateOf(false) }
    var defaultNotebookDialogVisible by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.settings_preferences),
    ) {
        SettingsRow(
            icon = Lucide.Palette,
            title = stringResource(Res.string.settings_theme),
            subtitle = state.settings.theme.localizedLabel(),
            onClick = if (workspaceEditable) ({ themeDialogVisible = true }) else null,
        ) {
            SettingsChevron()
        }
        SettingsRow(
            icon = Lucide.Globe,
            title = stringResource(Res.string.settings_language),
            subtitle = state.settings.appLanguage.localizedLabel(),
            // Language is device-local and remains editable during workspace preference conflicts.
            onClick = { languageDialogVisible = true },
        ) {
            SettingsChevron()
        }
        SettingsRow(
            icon = Lucide.NotebookTabs,
            title = stringResource(Res.string.settings_default_notebook),
            subtitle = state.selectedDefaultNotebookTitle ?: stringResource(Res.string.settings_current_selection),
            onClick = if (workspaceEditable) ({ defaultNotebookDialogVisible = true }) else null,
        ) {
            SettingsChevron()
        }
    }

    if (themeDialogVisible) {
        ThemeSelectionDialog(
            selectedTheme = state.settings.theme,
            onSelect = { theme ->
                coroutineScope.launch { controller.selectTheme(theme) }
                themeDialogVisible = false
            },
            onDismiss = { themeDialogVisible = false },
        )
    }

    if (languageDialogVisible) {
        LanguageSelectionDialog(
            selectedLanguage = state.settings.appLanguage,
            onSelect = { language ->
                coroutineScope.launch { controller.selectLanguage(language) }
                languageDialogVisible = false
            },
            onDismiss = { languageDialogVisible = false },
        )
    }

    if (defaultNotebookDialogVisible) {
        DefaultNotebookSelectionDialog(
            state = state,
            onSelect = { notebookId ->
                coroutineScope.launch { controller.selectDefaultNotebook(notebookId) }
                defaultNotebookDialogVisible = false
            },
            onDismiss = { defaultNotebookDialogVisible = false },
        )
    }
}

@Composable
private fun NotificationsSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
    onThisDayNotificationTimeFormatter: OnThisDayNotificationTimeFormatter,
) {
    val coroutineScope = rememberCoroutineScope()
    var notificationTimeDialogVisible by remember { mutableStateOf(false) }
    val notifications = state.settings.onThisDayNotifications

    SettingsSection(
        title = stringResource(Res.string.on_this_day_settings_section_title),
    ) {
        SettingsRow(
            icon = Lucide.Bell,
            title = stringResource(Res.string.on_this_day_settings_toggle_title),
            subtitle = stringResource(Res.string.on_this_day_settings_toggle_description),
        ) {
            Switch(
                checked = notifications.enabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { controller.toggleOnThisDayNotifications(enabled) }
                },
            )
        }
        if (notifications.enabled) {
            HorizontalDivider()
            SettingsRow(
                icon = Lucide.Clock,
                title = stringResource(Res.string.on_this_day_settings_time_title),
                subtitle = onThisDayNotificationTimeFormatter.format(notifications.hour, notifications.minute),
                onClick = { notificationTimeDialogVisible = true },
            ) {
                SettingsChevron()
            }
        }
    }

    if (notificationTimeDialogVisible) {
        OnThisDayNotificationTimeDialog(
            hour = notifications.hour,
            minute = notifications.minute,
            is24Hour = onThisDayNotificationTimeFormatter.is24Hour,
            onConfirm = { hour, minute ->
                coroutineScope.launch { controller.setOnThisDayNotificationTime(hour, minute) }
                notificationTimeDialogVisible = false
            },
            onDismiss = { notificationTimeDialogVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnThisDayNotificationTimeDialog(
    hour: Int,
    minute: Int,
    is24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.on_this_day_settings_time_title)) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                },
            ) {
                Text(stringResource(Res.string.on_this_day_settings_time_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.on_this_day_settings_time_cancel))
            }
        },
    )
}

@Composable
private fun EditorSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
) {
    val coroutineScope = rememberCoroutineScope()
    val workspaceEditable = state.settings.workspacePreferencesState.status != WorkspacePreferencesSyncStatus.Conflict
    SettingsSection(
        title = stringResource(Res.string.settings_writing),
    ) {
        SettingsRow(
            icon = Lucide.BookOpenText,
            title = stringResource(Res.string.settings_open_preview_first),
        ) {
            Switch(
                checked = state.settings.editorPreferences.previewByDefault,
                enabled = workspaceEditable,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { controller.togglePreviewByDefault(enabled) }
                },
            )
        }
        SettingsRow(
            icon = Lucide.Pencil,
            title = stringResource(Res.string.settings_markdown_toolbar),
        ) {
            Switch(
                checked = state.settings.editorPreferences.markdownToolbarVisible,
                enabled = workspaceEditable,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { controller.toggleMarkdownToolbarVisible(enabled) }
                },
            )
        }
    }
}

@Composable
private fun SyncSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
    workspacePairingScanner: WorkspacePairingScanner,
    actionScope: CoroutineScope,
) {
    val connection = state.sync.connection
    val connected = connection as? SyncConnectionUi.Connected
    val unavailable = connection as? SyncConnectionUi.Unavailable
    val signedIn = connected != null
    val savedAccount = connected != null || unavailable != null
    val configuredEndpoint = when (connection) {
        is SyncConnectionUi.Connected -> connection.endpoint
        is SyncConnectionUi.LocalOnly -> connection.configuredEndpoint
        is SyncConnectionUi.Unavailable -> connection.configuredEndpoint
    }
    val accountEmail = connected?.accountEmail ?: unavailable?.accountEmail
    val deviceLabel = connected?.deviceLabel ?: unavailable?.deviceLabel
    val accountFormMode = state.sync.accountFormMode()
    var connectionFormVisible by remember {
        mutableStateOf(accountFormMode.initiallyVisible)
    }
    var selfHostedEndpoint by remember(configuredEndpoint) {
        mutableStateOf(configuredEndpoint ?: "http://127.0.0.1:3180")
    }
    var selfHostedEmail by remember(accountEmail) {
        mutableStateOf(accountEmail.orEmpty())
    }
    var selfHostedPassword by remember { mutableStateOf("") }

    LaunchedEffect(accountFormMode) {
        when {
            accountFormMode.initiallyVisible -> connectionFormVisible = true
            !accountFormMode.allowManualReauthentication -> {
                connectionFormVisible = false
                selfHostedPassword = ""
            }
        }
    }

    fun connect(createAccount: Boolean) {
        if (state.sync.busy) return
        actionScope.launch {
            val succeeded = controller.setupSelfHosted(
                endpoint = selfHostedEndpoint,
                email = selfHostedEmail,
                password = selfHostedPassword,
                createAccount = createAccount,
            )
            if (succeeded) {
                selfHostedPassword = ""
                connectionFormVisible = false
            }
        }
    }

    SettingsSection(title = stringResource(Res.string.common_status)) {
        SettingsRow(
            icon = if (signedIn) Lucide.Cloud else Lucide.Server,
            title = when {
                state.sync.syncing -> stringResource(Res.string.sync_status_syncing)
                unavailable != null || state.sync.issue?.action == SyncIssueAction.ReloadSession ->
                    stringResource(Res.string.sync_status_unavailable)
                signedIn -> stringResource(Res.string.common_signed_in)
                else -> stringResource(Res.string.common_not_configured)
            },
            subtitle = accountEmail ?: configuredEndpoint,
        )
        if (savedAccount) {
            HorizontalDivider()
            StatusLine(
                stringResource(Res.string.common_server),
                configuredEndpoint.orEmpty(),
            )
            StatusLine(stringResource(Res.string.common_device), deviceLabel.orEmpty())
        }
        state.sync.issue?.let { issue ->
            StatusLine(
                stringResource(Res.string.sync_last_issue),
                issue.reason.localizedMessage(),
            )
        }
        val issueAction = state.sync.issue?.action
        if (savedAccount || issueAction == SyncIssueAction.ReloadSession) {
            HorizontalDivider()
            if (
                (connected != null && state.sync.issue == null) ||
                issueAction == SyncIssueAction.RetrySync ||
                issueAction == SyncIssueAction.ReloadSession
            ) {
                val retrying = issueAction != null
                val actionBusy = if (issueAction == SyncIssueAction.ReloadSession) {
                    state.sync.operation == SyncUiOperation.ReloadingSession
                } else {
                    state.sync.operation == SyncUiOperation.Syncing
                }
                SettingsActionRow(
                    icon = Lucide.Cloud,
                    title = if (retrying) stringResource(Res.string.sync_retry) else stringResource(Res.string.sync_now),
                    subtitle = if (state.sync.syncing) stringResource(Res.string.sync_in_progress) else null,
                    actionText = if (retrying) stringResource(Res.string.sync_retry) else stringResource(Res.string.common_sync),
                    busy = actionBusy,
                    enabled = !state.sync.busy,
                    onClick = {
                        actionScope.launch {
                            if (retrying) controller.recoverSyncIssue() else controller.runUserSync()
                        }
                    },
                )
            }
            if (accountFormMode.allowManualReauthentication) {
                TextButton(
                    onClick = {
                        connectionFormVisible = !connectionFormVisible
                        if (!connectionFormVisible) selfHostedPassword = ""
                    },
                    enabled = !state.sync.busy,
                ) {
                    Text(
                        if (connectionFormVisible) {
                            stringResource(Res.string.common_cancel)
                        } else {
                            stringResource(Res.string.sync_reauthenticate)
                        },
                    )
                }
            }
        }
    }

    if (connectionFormVisible) {
        SettingsSection(title = stringResource(Res.string.common_account)) {
            OutlinedTextField(
                value = selfHostedEndpoint,
                onValueChange = { selfHostedEndpoint = it },
                label = { Text(stringResource(Res.string.common_server_url)) },
                enabled = !state.sync.busy,
                readOnly = accountFormMode.serverReadOnly,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = selfHostedEmail,
                onValueChange = { selfHostedEmail = it },
                label = { Text(stringResource(Res.string.common_email)) },
                enabled = !state.sync.busy,
                readOnly = accountFormMode.emailReadOnly,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = selfHostedPassword,
                onValueChange = { selfHostedPassword = it },
                label = { Text(stringResource(Res.string.common_password)) },
                enabled = !state.sync.busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { connect(createAccount = false) },
                enabled = !state.sync.busy,
            ) {
                ActionButtonLabel(
                    icon = Lucide.Server,
                    text = if (accountFormMode != SyncAccountFormMode.InitialSetup) {
                        stringResource(Res.string.sync_reauthenticate)
                    } else {
                        stringResource(Res.string.selfhosted_sign_in)
                    },
                    busy = state.sync.operation == SyncUiOperation.Authenticating,
                )
            }
            if (accountFormMode.allowCreateAccount) {
                TextButton(
                    onClick = { connect(createAccount = true) },
                    enabled = !state.sync.busy,
                ) {
                    ActionButtonLabel(
                        icon = Lucide.Plus,
                        text = stringResource(Res.string.sync_create_account),
                        busy = state.sync.operation == SyncUiOperation.CreatingAccount,
                    )
                }
            }
            if (
                state.sync.operation == SyncUiOperation.Authenticating ||
                state.sync.operation == SyncUiOperation.CreatingAccount
            ) {
                StatusLine(
                    stringResource(Res.string.common_now),
                    stringResource(Res.string.sync_connecting),
                )
            }
        }
    }

    if (state.sync.pairingAvailable) {
        WorkspacePairingContent(
            state = state,
            controller = controller,
            readinessSubtitle = stringResource(Res.string.self_hosted_pairing_help),
            scanner = workspacePairingScanner,
            actionScope = actionScope,
        )
    }
}

@Composable
private fun SyncIssueReason.localizedMessage(): String =
    when (this) {
        SyncIssueReason.SignInRequired -> stringResource(Res.string.settings_fb_sign_in_before_sync)
        SyncIssueReason.SecureSessionUnavailable -> stringResource(Res.string.sync_secure_session_unavailable)
        SyncIssueReason.SetupFailed -> stringResource(Res.string.settings_fb_selfhosted_setup_failed)
        SyncIssueReason.ConfigurationChanged -> stringResource(Res.string.settings_fb_sync_configuration_changed)
        SyncIssueReason.SyncUnavailable -> stringResource(Res.string.settings_fb_sync_unavailable)
        SyncIssueReason.AuthorityMismatch -> stringResource(Res.string.settings_fb_sync_authority_mismatch)
        SyncIssueReason.WorkspaceLocked -> stringResource(Res.string.settings_fb_sync_workspace_locked)
        SyncIssueReason.RemoteHistoryConflict -> stringResource(Res.string.settings_fb_sync_remote_history_conflict)
        SyncIssueReason.CheckpointInvalid -> stringResource(Res.string.settings_fb_sync_checkpoint_invalid)
        SyncIssueReason.RetryRequired -> stringResource(Res.string.settings_fb_sync_retry_required)
        SyncIssueReason.Blocked -> stringResource(Res.string.settings_fb_sync_blocked)
        SyncIssueReason.SyncFailed -> stringResource(Res.string.settings_fb_sync_failed)
    }

@Composable
private fun ThemeSelectionDialog(
    selectedTheme: ClientTheme,
    onSelect: (ClientTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(stringResource(Res.string.settings_theme)) },
        text = {
            Column {
                ClientTheme.entries.forEach { theme ->
                    DialogOptionRow(
                        title = theme.localizedLabel(),
                        selected = theme == selectedTheme,
                        onClick = { onSelect(theme) },
                    )
                }
            }
        },
    )
}

@Composable
private fun LanguageSelectionDialog(
    selectedLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(stringResource(Res.string.settings_language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    DialogOptionRow(
                        title = language.localizedLabel(),
                        selected = language == selectedLanguage,
                        onClick = { onSelect(language) },
                    )
                }
            }
        },
    )
}

@Composable
private fun DefaultNotebookSelectionDialog(
    state: SettingsUiState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(stringResource(Res.string.settings_default_notebook)) },
        text = {
            Column {
                DialogOptionRow(
                    title = stringResource(Res.string.settings_current_selection),
                    selected = state.settings.defaultNotebookId == null,
                    onClick = { onSelect(null) },
                )
                state.defaultNotebookOptions.forEach { notebook ->
                    DialogOptionRow(
                        title = notebook.title,
                        selected = notebook.selected,
                        onClick = { onSelect(notebook.id) },
                    )
                }
            }
        },
    )
}

@Composable
private fun DialogOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Text(stringResource(Res.string.common_selected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WorkspacePairingContent(
    state: SettingsUiState,
    controller: SettingsUiController,
    readinessSubtitle: String,
    scanner: WorkspacePairingScanner,
    actionScope: CoroutineScope,
) {
    var selectedMode by remember {
        mutableStateOf(
            if (state.sync.invitation != null) {
                WorkspacePairingMode.ShowInvitation
            } else {
                null
            },
        )
    }
    var joinPairingToken by remember { mutableStateOf("") }
    val actionRunning = state.sync.busy
    val tokenEntered = joinPairingToken.isNotBlank()
    val invitationExpiresAt = state.sync.invitation?.expiresAtEpochMillis
    var invitationExpired by remember(invitationExpiresAt) {
        mutableStateOf(
            invitationExpiresAt?.let {
                it <= kotlin.time.Clock.System.now().toEpochMilliseconds()
            } ?: false,
        )
    }

    LaunchedEffect(invitationExpiresAt) {
        invitationExpired = invitationExpiresAt?.let { expiresAt ->
            val remainingMillis =
                expiresAt - kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (remainingMillis > 0) {
                delay(remainingMillis)
            }
            controller.discardWorkspacePairingInvitationAtExpiry(expiresAt)
            true
        } ?: false
    }
    val displayedInvitation = state.sync.invitation
        ?.takeUnless { invitationExpired }

    LaunchedEffect(displayedInvitation?.expiresAtEpochMillis) {
        if (displayedInvitation != null) {
            selectedMode = WorkspacePairingMode.ShowInvitation
        }
    }

    fun runWorkspacePairingAction(
        clearJoinFormOnSuccess: Boolean = false,
        block: suspend () -> Boolean,
    ) {
        if (state.sync.busy) {
            return
        }
        actionScope.launch {
            val succeeded = block()
            if (succeeded && clearJoinFormOnSuccess) {
                joinPairingToken = ""
                selectedMode = null
            }
        }
    }

    SettingsSection(title = stringResource(Res.string.pairing_section)) {
        SettingsRow(
            icon = Lucide.LockKeyhole,
            title = stringResource(Res.string.pairing_title),
            subtitle = readinessSubtitle,
        )
        HorizontalDivider()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            CompactSettingsActionButton(
                text = stringResource(Res.string.pairing_create_invitation),
                busy = state.sync.operation == SyncUiOperation.CreatingInvitation,
                enabled = !actionRunning,
                onClick = {
                    selectedMode = WorkspacePairingMode.ShowInvitation
                    if (displayedInvitation == null) {
                        runWorkspacePairingAction {
                            controller.createWorkspacePairingInvitation()
                        }
                    }
                },
            )
            CompactSettingsActionButton(
                text = stringResource(Res.string.pairing_enter_token),
                enabled = !actionRunning,
                onClick = { selectedMode = WorkspacePairingMode.JoinWithToken },
            )
        }
        if (selectedMode == WorkspacePairingMode.ShowInvitation && displayedInvitation != null) {
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                WorkspacePairingQrCode(displayedInvitation.qrPayload)
                Text(
                    text = displayedInvitation.manualToken,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.pairing_expires),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        runWorkspacePairingAction {
                            controller.cancelWorkspacePairingInvitation()
                        }
                    },
                    enabled = !actionRunning,
                ) {
                    ActionButtonLabel(
                        icon = Lucide.X,
                        text = stringResource(Res.string.pairing_cancel_invitation),
                        busy = state.sync.operation == SyncUiOperation.CancellingInvitation,
                    )
                }
            }
        }
        if (selectedMode == WorkspacePairingMode.JoinWithToken) {
            HorizontalDivider()
            SettingsRow(
                icon = Lucide.Server,
                title = stringResource(Res.string.pairing_join_title),
                subtitle = stringResource(Res.string.pairing_join_subtitle),
            )
            OutlinedTextField(
                value = joinPairingToken,
                onValueChange = { input -> joinPairingToken = input.take(80) },
                label = { Text(stringResource(Res.string.pairing_token_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (scanner.available) {
                TextButton(
                    onClick = {
                        scanner.scan(
                            onResult = { scanned -> joinPairingToken = scanned },
                        )
                    },
                    enabled = !actionRunning,
                ) {
                    Text(stringResource(Res.string.pairing_scan_qr))
                }
            }
            Text(
                text = stringResource(Res.string.pairing_local_notes_stay),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            SettingsActionRow(
                icon = Lucide.LockKeyhole,
                title = stringResource(Res.string.pairing_confirm_join),
                subtitle = if (tokenEntered) {
                    stringResource(Res.string.pairing_confirm_join_subtitle)
                } else {
                    stringResource(Res.string.pairing_enter_token_prompt)
                },
                actionText = stringResource(Res.string.common_join),
                busy = state.sync.operation == SyncUiOperation.JoiningInvitation,
                enabled = !actionRunning && tokenEntered,
                onClick = {
                    runWorkspacePairingAction(clearJoinFormOnSuccess = true) {
                        controller.joinWorkspaceWithToken(joinPairingToken)
                    }
                },
            )
        }
    }
}

@Composable
private fun WorkspacePairingQrCode(payload: String) {
    val matrix = remember(payload) {
        QRCode.ofSquares()
            .withErrorCorrectionLevel(ErrorCorrectionLevel.HIGH)
            .build(payload)
            .rawData
            .map { row -> row.map { square -> square.dark } }
    }
    Canvas(
        modifier = Modifier
            .size(232.dp)
            .background(Color.White)
            .padding(8.dp),
    ) {
        val quietZoneModules = 4
        val moduleCount = matrix.size + quietZoneModules * 2
        val moduleSize = minOf(size.width, size.height) / moduleCount
        drawRect(Color.White)
        matrix.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, dark ->
                if (dark) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(
                            (columnIndex + quietZoneModules) * moduleSize,
                            (rowIndex + quietZoneModules) * moduleSize,
                        ),
                        size = Size(moduleSize + 0.35f, moduleSize + 0.35f),
                    )
                }
            }
        }
    }
}

private enum class WorkspacePairingMode {
    ShowInvitation,
    JoinWithToken,
}

@Composable
private fun ImportSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
) {
    SettingsSection(
        title = stringResource(Res.string.common_import),
    ) {
        SettingsActionRow(
            icon = Lucide.Download,
            title = stringResource(Res.string.import_day_one),
            subtitle = state.importSummary?.message,
            actionText = stringResource(Res.string.common_import),
            busy = state.importRunning,
            enabled = !state.importRunning,
            onClick = {
                if (!state.importRunning) {
                    controller.startDayOneImport()
                }
            },
        )
        state.importSummary?.let { summary ->
            StatusLine(stringResource(Res.string.common_source), summary.sourceName)
            StatusLine(stringResource(Res.string.common_journals), summary.journalsImported.toString())
            StatusLine(stringResource(Res.string.tab_notes), "${summary.notesImported} imported, ${summary.notesSkipped} skipped")
            StatusLine(stringResource(Res.string.nav_notebooks), "${summary.notebooksCreated} created, ${summary.notebooksReused} reused")
            if (summary.richTextConverted > 0) {
                StatusLine(stringResource(Res.string.common_rich_text), "${summary.richTextConverted} converted to Markdown")
            }
            if (summary.mediaReferenced > 0 || summary.unsupportedItems > 0) {
                StatusLine(stringResource(Res.string.common_unsupported), "${summary.mediaReferenced} media references, ${summary.unsupportedItems} objects")
            }
            if (summary.success && (!summary.includesMediaBytes || summary.assetReferencesMayBeUnresolved)) {
                Text(stringResource(Res.string.import_media_boundary), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExportSettingsContent(
    state: SettingsUiState,
    controller: SettingsUiController,
) {
    val coroutineScope = rememberCoroutineScope()
    var exportRunning by remember { mutableStateOf(false) }
    SettingsSection(
        title = stringResource(Res.string.common_export),
    ) {
        SettingsActionRow(
            icon = Lucide.Download,
            title = stringResource(Res.string.import_local_export),
            subtitle = state.exportSummary?.destinationLabel,
            actionText = stringResource(Res.string.common_export),
            busy = exportRunning,
            enabled = !exportRunning,
            onClick = {
                if (!exportRunning) {
                    exportRunning = true
                    coroutineScope.launch {
                        controller.runLocalExport()
                        exportRunning = false
                    }
                }
            },
        )
        state.exportSummary?.let { summary ->
            StatusLine(stringResource(Res.string.common_content), "${summary.notebookCount} notebooks, ${summary.noteCount} notes")
            summary.destinationLabel?.let { destination ->
                StatusLine(stringResource(Res.string.common_file), destination)
            }
            Text(stringResource(Res.string.export_secrets_excluded), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SomedayDesignDefaults.SectionSpacing),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Surface(
            shape = SomedayDesignDefaults.SectionShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        modifier = modifier.padding(start = 48.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    SettingsListItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = trailing,
    )
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionText: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
    ) {
        CompactSettingsActionButton(
            text = actionText,
            busy = busy,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

@Composable
private fun SettingsChevron() {
    Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
}

@Composable
private fun CompactSettingsActionButton(
    text: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 1.dp),
        modifier = Modifier.heightIn(min = 26.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ActionButtonLabel(
    icon: ImageVector,
    text: String,
    busy: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(text)
    }
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@Composable
private fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val hasSubtitle = !subtitle.isNullOrBlank()
    val rowClickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(rowClickModifier)
            .heightIn(min = if (hasSubtitle) 68.dp else 56.dp)
            .padding(vertical = 10.dp),
    ) {
        SettingsLeadingIcon(icon = icon)
        SettingsListItemText(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f).padding(end = if (trailing == null) 0.dp else 4.dp),
        )
        trailing?.let {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                it()
            }
        }
    }
}

@Composable
private fun SettingsLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        shape = SomedayDesignDefaults.IconShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun SettingsListItemText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
    }
}

private fun LazyListScope.noteSummaryListItems(
    notes: List<NoteSummary>,
    emptyMessage: org.jetbrains.compose.resources.StringResource,
    onOpenNote: (String) -> Boolean,
    keyPrefix: String,
    metadataText: @Composable (NoteSummary) -> String,
) {
    if (notes.isEmpty()) {
        item(key = "$keyPrefix-empty", contentType = "empty-state") {
            Text(
                stringResource(emptyMessage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        return
    }
    items(
        items = notes,
        key = { note -> "$keyPrefix-${note.id}" },
        contentType = { "note-row" },
    ) { note ->
        NoteListItem(
            note = note,
            metadataText = metadataText(note),
            onOpenNote = { noteId -> onOpenNote(noteId) },
        )
    }
}

@Composable
private fun NoteEditorContent(
    state: NotesUiState,
    controller: NotesUiController,
    mediaUiPorts: MediaUiPorts,
    editorPreferences: EditorPreferences,
    toolbarPlacement: NoteEditorToolbarPlacement = NoteEditorToolbarPlacement.Inline,
    contentHorizontalPadding: Dp = 0.dp,
    autoFocusBody: Boolean,
    onOpenConflictResolution: (ConflictDetails) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = state.editor ?: return
    val coroutineScope = rememberCoroutineScope()
    var detailsDialogVisible by remember { mutableStateOf(false) }
    var moreSheetVisible by remember { mutableStateOf(false) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var placeDraft by remember(editor.placeText) { mutableStateOf(editor.placeText) }
    var bodyFocused by remember(editor.sessionId) { mutableStateOf(false) }
    var mediaImportInProgress by remember(editor.sessionId) { mutableStateOf(false) }
    val imagePickerTitle = stringResource(Res.string.image_picker_title)
    val onMarkdownToolbarAction: (MarkdownToolbarAction) -> Unit = { action ->
        if (action == MarkdownToolbarAction.Image) {
            if (!mediaImportInProgress) {
                mediaImportInProgress = true
                val editorSessionId = editor.sessionId
                var callbackConsumed = false
                try {
                    mediaUiPorts.importRunner.start(imagePickerTitle) { result ->
                        if (!callbackConsumed) {
                            callbackConsumed = true
                            mediaImportInProgress = false
                            controller.applyMediaImportResult(editorSessionId, result)
                        }
                    }
                } catch (_: Exception) {
                    if (!callbackConsumed) {
                        callbackConsumed = true
                        mediaImportInProgress = false
                        controller.applyMediaImportResult(
                            editorSessionId,
                            MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed),
                        )
                    }
                }
            }
        } else {
            controller.applyMarkdownToolbarAction(action)
        }
    }
    val bodyFocusRequester = remember { FocusRequester() }
    var markdownFieldValue by remember(editor.noteId) {
        mutableStateOf(noteEditorMarkdownFieldValue(editor))
    }
    LaunchedEffect(
        editor.noteId,
        editor.markdownBody,
        editor.markdownSelectionStart,
        editor.markdownSelectionEnd,
    ) {
        markdownFieldValue = markdownFieldValue.syncedWithEditorMarkdown(editor)
    }
    LaunchedEffect(autoFocusBody, editor.noteId, editor.markdownPreviewVisible) {
        if (autoFocusBody && !editor.markdownPreviewVisible) {
            bodyFocusRequester.requestFocus()
        }
    }
    val markdownToolbarAvailable = editorPreferences.markdownToolbarVisible && !editor.markdownPreviewVisible
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottomPadding = if (imeBottom == 0) {
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    } else {
        0.dp
    }
    val keyboardToolbarVisible = toolbarPlacement == NoteEditorToolbarPlacement.KeyboardAccessory &&
        markdownToolbarAvailable &&
        bodyFocused &&
        imeBottom > 0
    val inlineToolbarVisible = toolbarPlacement == NoteEditorToolbarPlacement.Inline && markdownToolbarAvailable
    val editorBottomPadding = if (keyboardToolbarVisible) {
        20.dp + NoteEditorKeyboardToolbarHeight
    } else {
        28.dp + navigationBottomPadding
    }

    SomedayLiquidGlassHost {
        Box(modifier = modifier) {
            SomedayLiquidGlassBackdrop(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp),
                ) {
                    state.conflictDetails?.let { details ->
                        NoteConflictBanner(
                            details = details,
                            onClick = { onOpenConflictResolution(details) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = contentHorizontalPadding,
                                    top = 4.dp,
                                    end = contentHorizontalPadding,
                                ),
                        )
                    } ?: noteEditorSyncIssueBannerBadge(editor.syncBadge)?.let { syncBadge ->
                        NoteSyncIssueBanner(
                            syncBadge = syncBadge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = contentHorizontalPadding,
                                    top = 4.dp,
                                    end = contentHorizontalPadding,
                                ),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .imePadding(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = contentHorizontalPadding,
                                    top = 10.dp,
                                    end = contentHorizontalPadding,
                                    bottom = editorBottomPadding,
                                ),
                        ) {
                            editor.validationMessage?.let { message ->
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (state.versionHistory?.visible == true) {
                                VersionHistoryContent(
                                    versions = state.versionHistory.versions,
                                    onRestoreVersion = { versionId ->
                                        coroutineScope.launch { controller.restoreVersion(versionId) }
                                    },
                                )
                            }
                            EditorTitleInput(
                                value = editor.title,
                                onValueChange = { controller.updateDraft(title = it) },
                            )
                            if (inlineToolbarVisible) {
                                MarkdownToolbar(
                                    inputActionsEnabled = markdownFieldValue.composition == null,
                                    imageActionEnabled = !mediaImportInProgress,
                                    onToolbarAction = onMarkdownToolbarAction,
                                )
                            }
                            if (editor.markdownPreviewVisible) {
                                MarkdownPreviewContent(
                                    source = editor.markdownBody,
                                    mediaUiPorts = mediaUiPorts,
                                )
                            } else {
                                EditorBodyInput(
                                    value = markdownFieldValue,
                                    onValueChange = { value ->
                                        markdownFieldValue = value
                                        controller.updateDraft(markdownBody = value.text)
                                        controller.updateMarkdownSelection(value.selection.start, value.selection.end)
                                    },
                                    focusRequester = bodyFocusRequester,
                                    onFocusChanged = { focused -> bodyFocused = focused },
                                )
                            }
                        }
                    }
                }
            }
            NoteEditorTopBar(
                editor = editor,
                dateText = noteEditorDateLabel(editor),
                onClose = onClose,
                onSave = onSave,
                onMore = { moreSheetVisible = true },
                mutationEnabled = state.conflictDetails == null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = contentHorizontalPadding),
            )
            if (keyboardToolbarVisible) {
                MarkdownKeyboardAccessoryBar(
                    inputActionsEnabled = markdownFieldValue.composition == null,
                    imageActionEnabled = !mediaImportInProgress,
                    onToolbarAction = onMarkdownToolbarAction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding(),
                )
            }
        }
    }

    if (moreSheetVisible) {
        NoteEditorMoreSheet(
            editor = editor,
            historyVisible = state.versionHistory?.visible == true,
            onDismiss = { moreSheetVisible = false },
            onShowDetails = {
                moreSheetVisible = false
                placeDraft = editor.placeText
                detailsDialogVisible = true
            },
            onToggleHistory = {
                moreSheetVisible = false
                if (state.versionHistory?.visible == true) {
                    controller.hideVersionHistory()
                } else {
                    coroutineScope.launch { controller.showVersionHistory() }
                }
            },
            onTogglePreview = {
                moreSheetVisible = false
                controller.toggleMarkdownPreview()
            },
            onDelete = {
                moreSheetVisible = false
                deleteDialogVisible = true
            },
        )
    }

    val deleteNoteId = editor.noteId
    if (deleteDialogVisible && deleteNoteId != null) {
        ConfirmActionDialog(
            title = stringResource(Res.string.note_delete_title),
            text = stringResource(Res.string.note_delete_message),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                onDeleteNote(deleteNoteId)
                deleteDialogVisible = false
            },
            onDismiss = { deleteDialogVisible = false },
        )
    }

    if (detailsDialogVisible) {
        NoteDetailsDialog(
            state = state,
            editor = editor,
            dateText = noteEditorDateLabel(editor),
            placeDraft = placeDraft,
            onPlaceDraftChange = { placeDraft = it },
            onSelectNotebook = { notebookId -> controller.updateDraft(notebookId = notebookId) },
            onCaptureCurrentLocation = controller::captureCurrentLocation,
            onConfirm = {
                controller.updateDraft(placeText = placeDraft)
                detailsDialogVisible = false
            },
            onDismiss = { detailsDialogVisible = false },
        )
    }
}

@Composable
private fun NoteEditorTopBar(
    editor: NoteEditorState,
    dateText: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
    mutationEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SomedayLiquidGlassTopBar(modifier = modifier.fillMaxWidth().height(64.dp)) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.width(100.dp).fillMaxHeight(),
        ) {
            SomedayLiquidGlassIconButton(
                icon = Lucide.X,
                contentDescription = stringResource(Res.string.note_close),
                onClick = onClose,
            )
        }
        NavigationTitleText(
            title = dateText,
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.width(100.dp).fillMaxHeight(),
        ) {
            SomedayLiquidGlassActionGroup {
                SomedayLiquidGlassGroupedIconButton(
                    icon = Lucide.Ellipsis,
                    contentDescription = stringResource(Res.string.note_more_actions),
                    onClick = onMore,
                )
                SomedayLiquidGlassGroupedIconButton(
                    icon = Lucide.Check,
                    contentDescription = stringResource(Res.string.common_done),
                    onClick = onSave,
                    enabled = mutationEnabled && editor.title.isNotBlank(),
                    emphasized = true,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoteEditorMoreSheet(
    editor: NoteEditorState,
    historyVisible: Boolean,
    onDismiss: () -> Unit,
    onShowDetails: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                stringResource(Res.string.note_actions),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            NoteEditorSheetActionRow(
                icon = Lucide.Info,
                title = stringResource(Res.string.common_details),
                subtitle = stringResource(Res.string.note_details_subtitle),
                onClick = onShowDetails,
            )
            HorizontalDivider()
            NoteEditorSheetActionRow(
                icon = if (editor.markdownPreviewVisible) Lucide.Code else Lucide.Eye,
                title = if (editor.markdownPreviewVisible) stringResource(Res.string.note_edit_markdown) else stringResource(Res.string.common_preview),
                subtitle = if (editor.markdownPreviewVisible) stringResource(Res.string.note_return_source) else stringResource(Res.string.note_render_preview),
                onClick = onTogglePreview,
            )
            if (editor.noteId != null) {
                HorizontalDivider()
                NoteEditorSheetActionRow(
                    icon = Lucide.History,
                    title = if (historyVisible) stringResource(Res.string.note_hide_version_history) else stringResource(Res.string.note_version_history),
                    subtitle = if (historyVisible) stringResource(Res.string.note_close_version_panel) else stringResource(Res.string.note_review_versions),
                    onClick = onToggleHistory,
                )
                HorizontalDivider()
                NoteEditorSheetActionRow(
                    icon = Lucide.Trash,
                    title = stringResource(Res.string.note_delete_title),
                    subtitle = stringResource(Res.string.note_remove_from_syncs),
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun NoteEditorSheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                subtitle,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.82f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EditorTitleInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (value.isBlank()) {
                    Text(
                        text = stringResource(Res.string.common_title),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun EditorBodyInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
) {
    val markdownEditPreviewTransformation = rememberMarkdownEditPreviewTransformation()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        visualTransformation = markdownEditPreviewTransformation,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) },
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(vertical = 8.dp)) {
                if (value.text.isBlank()) {
                    Text(
                        text = stringResource(Res.string.note_write_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun rememberMarkdownEditPreviewTransformation(): VisualTransformation {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val styles = MarkdownEditPreviewStyles(
        syntax = SpanStyle(color = colorScheme.onSurfaceVariant.copy(alpha = 0.66f)),
        heading1 = SpanStyle(
            color = colorScheme.onSurface,
            fontSize = typography.headlineSmall.fontSize,
            fontWeight = FontWeight.Bold,
        ),
        heading2 = SpanStyle(
            color = colorScheme.onSurface,
            fontSize = typography.titleLarge.fontSize,
            fontWeight = FontWeight.Bold,
        ),
        heading3 = SpanStyle(
            color = colorScheme.onSurface,
            fontSize = typography.titleMedium.fontSize,
            fontWeight = FontWeight.Bold,
        ),
        bold = SpanStyle(fontWeight = FontWeight.Bold),
        italic = SpanStyle(fontStyle = FontStyle.Italic),
        linkLabel = SpanStyle(
            color = colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        linkDestination = SpanStyle(color = colorScheme.onSurfaceVariant.copy(alpha = 0.78f)),
        quoteText = SpanStyle(
            color = colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        ),
        inlineCode = SpanStyle(
            color = colorScheme.onSurface,
            background = colorScheme.surfaceVariant,
            fontFamily = FontFamily.Monospace,
        ),
        codeFence = SpanStyle(
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
        ),
        codeBlock = SpanStyle(
            color = colorScheme.onSurface,
            background = colorScheme.surfaceVariant.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
        ),
    )
    return remember(styles) { MarkdownEditPreviewVisualTransformation(styles) }
}

private data class MarkdownEditPreviewStyles(
    val syntax: SpanStyle,
    val heading1: SpanStyle,
    val heading2: SpanStyle,
    val heading3: SpanStyle,
    val bold: SpanStyle,
    val italic: SpanStyle,
    val linkLabel: SpanStyle,
    val linkDestination: SpanStyle,
    val quoteText: SpanStyle,
    val inlineCode: SpanStyle,
    val codeFence: SpanStyle,
    val codeBlock: SpanStyle,
)

private class MarkdownEditPreviewVisualTransformation(
    private val styles: MarkdownEditPreviewStyles,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val transformed = buildAnnotatedString {
            append(text.text)
            markdownEditPreviewSpans(text.text).forEach { span ->
                addStyle(span.kind.toSpanStyle(styles), span.start, span.end)
            }
        }
        return TransformedText(transformed, OffsetMapping.Identity)
    }
}

private fun MarkdownEditSpanKind.toSpanStyle(styles: MarkdownEditPreviewStyles): SpanStyle =
    when (this) {
        MarkdownEditSpanKind.Syntax -> styles.syntax
        MarkdownEditSpanKind.Heading1 -> styles.heading1
        MarkdownEditSpanKind.Heading2 -> styles.heading2
        MarkdownEditSpanKind.Heading3 -> styles.heading3
        MarkdownEditSpanKind.Bold -> styles.bold
        MarkdownEditSpanKind.Italic -> styles.italic
        MarkdownEditSpanKind.LinkLabel -> styles.linkLabel
        MarkdownEditSpanKind.LinkDestination -> styles.linkDestination
        MarkdownEditSpanKind.QuoteText -> styles.quoteText
        MarkdownEditSpanKind.InlineCode -> styles.inlineCode
        MarkdownEditSpanKind.CodeFence -> styles.codeFence
        MarkdownEditSpanKind.CodeBlock -> styles.codeBlock
    }

@Composable
private fun NoteDetailsDialog(
    state: NotesUiState,
    editor: NoteEditorState,
    dateText: String,
    placeDraft: String,
    onPlaceDraftChange: (String) -> Unit,
    onSelectNotebook: (String) -> Unit,
    onCaptureCurrentLocation: () -> Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(stringResource(Res.string.note_details)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                NoteDetailsSectionTitle(stringResource(Res.string.common_overview))
                NoteDetailsRow(
                    icon = Lucide.CalendarDays,
                    label = stringResource(Res.string.common_date),
                    value = dateText,
                )
                NoteDetailsRow(
                    icon = Lucide.NotebookTabs,
                    label = stringResource(Res.string.note_notebook),
                    value = noteEditorNotebookTitle(state, editor),
                )
                NoteDetailsRow(
                    icon = Lucide.MapPin,
                    label = stringResource(Res.string.common_location),
                    value = placeDraft.ifBlank { noteEditorLocationLabel(editor).ifBlank { stringResource(Res.string.note_no_location) } },
                )
                if (editor.noteId == null || shouldShowSyncBadge(editor.syncBadge)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Lucide.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(Res.string.common_status),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(74.dp),
                        )
                        if (editor.noteId == null) {
                            Text(
                                stringResource(Res.string.common_draft),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                NoteSyncIndicator(
                                    syncBadge = editor.syncBadge,
                                    showText = true,
                                )
                                syncBadgeDetailsText(editor.syncBadge)?.let { details ->
                                    Text(
                                        details,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                NoteDetailsSectionTitle(stringResource(Res.string.note_notebook))
                state.notebooks.forEach { notebook ->
                    DialogOptionRow(
                        title = notebook.title,
                        selected = notebook.id == editor.notebookId,
                        onClick = { onSelectNotebook(notebook.id) },
                    )
                }

                HorizontalDivider()
                NoteDetailsSectionTitle(stringResource(Res.string.common_location))
                OutlinedTextField(
                    value = placeDraft,
                    onValueChange = onPlaceDraftChange,
                    label = { Text(stringResource(Res.string.common_place)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onCaptureCurrentLocation() }) {
                    Icon(Lucide.MapPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (editor.latitudeText.isNotBlank()) {
                            stringResource(Res.string.note_update_current_location)
                        } else {
                            stringResource(Res.string.note_use_current_location)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NoteDetailsSectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun NoteDetailsRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(74.dp),
        )
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun noteEditorNotebookTitle(
    state: NotesUiState,
    editor: NoteEditorState,
): String =
    state.notebooks.firstOrNull { it.id == editor.notebookId }?.title ?: stringResource(Res.string.note_notebook)

@Composable
private fun noteEditorDateLabel(editor: NoteEditorState): String =
    editor.createdDateText.ifBlank { stringResource(Res.string.common_today) }

@Composable
private fun noteEditorLocationLabel(editor: NoteEditorState): String =
    when {
        editor.placeText.isNotBlank() -> editor.placeText
        editor.latitudeText.isNotBlank() && editor.longitudeText.isNotBlank() -> stringResource(Res.string.note_location_added)
        else -> ""
    }

private fun noteEditorSyncIssueBannerBadge(syncBadge: NoteSyncBadge): NoteSyncBadge? =
    when (syncBadge) {
        is NoteSyncBadge.Conflict -> syncBadge
        is NoteSyncBadge.Error ->
            syncBadge.takeIf { it.details.contains("conflict", ignoreCase = true) }
        NoteSyncBadge.Pending,
        NoteSyncBadge.Synced,
        -> null
    }

@Composable
private fun NoteConflictBanner(
    details: ConflictDetails,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = SomedayDesignDefaults.CellShape,
        modifier = modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Icon(Lucide.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.conflict_sync_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    details.conflictHistory.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NoteSyncIssueBanner(
    syncBadge: NoteSyncBadge,
    modifier: Modifier = Modifier,
) {
    val isConflict = syncBadge is NoteSyncBadge.Conflict ||
        syncBadge.details.orEmpty().contains("conflict", ignoreCase = true)
    Surface(
        color = if (isConflict) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (isConflict) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        shape = SomedayDesignDefaults.CellShape,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Icon(syncBadgeIcon(syncBadge), contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isConflict) stringResource(Res.string.conflict_sync_title) else syncBadge.label,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                syncBadge.details?.let { details ->
                    Text(
                        details,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionHistoryContent(
    versions: List<NoteVersionSummary>,
    onRestoreVersion: (String) -> Unit,
) {
    SomedayPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(Res.string.note_version_history),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        if (versions.isEmpty()) {
            Text(stringResource(Res.string.note_no_versions), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            versions.asReversed().forEach { version ->
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(Res.string.note_saved_version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    version.mergeMetadata?.let {
                        Text(stringResource(Res.string.note_includes_merged), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(version.title, fontWeight = FontWeight.Bold)
                    Text(
                        version.markdownBody.lineSequence().joinToString(" ").trim().take(160).ifBlank { stringResource(Res.string.note_no_body_yet) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onRestoreVersion(version.versionId) }) {
                        Text(stringResource(Res.string.common_restore))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictHistoryPanel(
    title: String,
    history: ConflictHistory,
    metadata: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    SomedayPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            history.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        metadata?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        val latestVersions = history.versions.asReversed().take(3)
        if (latestVersions.isEmpty()) {
            Text(
                stringResource(Res.string.note_no_saved_versions),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            latestVersions.forEach { version ->
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        version.createdAt.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        version.markdownBody.lineSequence().joinToString(" ").trim().take(220).ifBlank { stringResource(Res.string.note_no_body) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    inputActionsEnabled: Boolean,
    imageActionEnabled: Boolean,
    onToolbarAction: (MarkdownToolbarAction) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    itemSpacing: Dp = 2.dp,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        SomedayLiquidGlassActionGroup(spacing = itemSpacing) {
            markdownToolbarActions.forEach { action ->
                MarkdownToolbarButton(
                    action = action,
                    enabled = inputActionsEnabled &&
                        (action != MarkdownToolbarAction.Image || imageActionEnabled),
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                    onClick = { onToolbarAction(action) },
                )
            }
        }
    }
}

@Composable
private fun MarkdownToolbarButton(
    action: MarkdownToolbarAction,
    enabled: Boolean,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val contentDescription = if (action == MarkdownToolbarAction.Image) {
        stringResource(Res.string.image_toolbar_action)
    } else {
        action.label
    }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize)
            .clip(SomedayDesignDefaults.IconShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = markdownToolbarIcon(action),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun MarkdownKeyboardAccessoryBar(
    inputActionsEnabled: Boolean,
    imageActionEnabled: Boolean,
    onToolbarAction: (MarkdownToolbarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(NoteEditorKeyboardToolbarHeight),
    ) {
        MarkdownToolbar(
            inputActionsEnabled = inputActionsEnabled,
            imageActionEnabled = imageActionEnabled,
            onToolbarAction = onToolbarAction,
            buttonSize = NoteEditorKeyboardToolbarButtonSize,
            iconSize = NoteEditorKeyboardToolbarIconSize,
            itemSpacing = 0.dp,
        )
    }
}

private fun markdownToolbarIcon(action: MarkdownToolbarAction): ImageVector =
    when (action) {
        MarkdownToolbarAction.Heading -> Lucide.Heading
        MarkdownToolbarAction.Bold -> Lucide.Bold
        MarkdownToolbarAction.Italic -> Lucide.Italic
        MarkdownToolbarAction.List -> Lucide.ListIcon
        MarkdownToolbarAction.Quote -> Lucide.Quote
        MarkdownToolbarAction.CodeBlock -> Lucide.Code
        MarkdownToolbarAction.Link -> Lucide.Link
        MarkdownToolbarAction.Image -> Lucide.ImageIcon
    }

@Composable
private fun MarkdownPreviewContent(
    source: String,
    mediaUiPorts: MediaUiPorts,
) {
    val blocks = renderMarkdownPreview(source)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        if (blocks.isEmpty()) {
            Text(
                stringResource(Res.string.common_nothing_yet),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            blocks.forEach { block ->
                MarkdownPreviewBlockContent(block, mediaUiPorts)
            }
        }
    }
}

@Composable
private fun MarkdownPreviewBlockContent(
    block: MarkdownPreviewBlock,
    mediaUiPorts: MediaUiPorts,
) {
    when (block) {
        is MarkdownPreviewBlock.Heading -> Text(
            text = block.plainText,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
        )

        is MarkdownPreviewBlock.Paragraph -> MarkdownInlineText(block.inlines)
        is MarkdownPreviewBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("-")
            MarkdownInlineText(block.inlines)
        }

        is MarkdownPreviewBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("|", color = MaterialTheme.colorScheme.primary)
            MarkdownInlineText(block.inlines)
        }

        is MarkdownPreviewBlock.CodeBlock -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    SomedayDesignDefaults.CellShape,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = block.code.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
            )
        }
        is MarkdownPreviewBlock.Image -> MarkdownImagePreviewBlock(block, mediaUiPorts)
    }
}

private sealed interface LocalImagePreviewState {
    data object Loading : LocalImagePreviewState
    data class Ready(val bitmap: ImageBitmap) : LocalImagePreviewState
    data object Missing : LocalImagePreviewState
    data class Failed(val safeMessage: String) : LocalImagePreviewState
}

@Composable
private fun MarkdownImagePreviewBlock(
    block: MarkdownPreviewBlock.Image,
    mediaUiPorts: MediaUiPorts,
) {
    val assetUri = block.localAssetUri
    val defaultAltText = stringResource(Res.string.image_preview_default_alt)
    val title = block.altText.ifBlank { defaultAltText }

    if (assetUri == null) {
        MarkdownImagePreviewFrame(title = title) {
            Text(
                text = stringResource(Res.string.image_preview_remote),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    val assetId = assetUri.assetId
    val previewTooLargeMessage = stringResource(Res.string.image_preview_too_large)
    val previewLoadFailedMessage = stringResource(Res.string.image_preview_load_failed)
    val previewDecodeFailedMessage = stringResource(Res.string.image_preview_decode_failed)
    val materializationCancelledMessage = stringResource(Res.string.image_preview_download_cancelled)
    val materializationFailedMessage = stringResource(Res.string.image_preview_download_failed)
    var loadGeneration by remember(assetId) { mutableStateOf(0) }
    var previewState by remember(assetId, mediaUiPorts.previewLoader) {
        mutableStateOf<LocalImagePreviewState>(LocalImagePreviewState.Loading)
    }
    var materializing by remember(assetId) { mutableStateOf(false) }
    var materializationMessage by remember(assetId) { mutableStateOf<String?>(null) }

    LaunchedEffect(assetId, mediaUiPorts.previewLoader, loadGeneration) {
        previewState = LocalImagePreviewState.Loading
        previewState = try {
            when (val result = mediaUiPorts.previewLoader.loadPreview(assetId)) {
                is MediaPreviewUiResult.Loaded -> {
                    try {
                        val bitmap = withContext(Dispatchers.Default) {
                            result.copyBytes().decodeToImageBitmap()
                        }
                        LocalImagePreviewState.Ready(bitmap)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        LocalImagePreviewState.Failed(previewDecodeFailedMessage)
                    }
                }

                MediaPreviewUiResult.Missing -> LocalImagePreviewState.Missing
                is MediaPreviewUiResult.Failed -> LocalImagePreviewState.Failed(
                    when (result.reason) {
                        MediaUiFailureReason.PreviewTooLarge -> previewTooLargeMessage
                        MediaUiFailureReason.Unavailable,
                        MediaUiFailureReason.ImportFailed,
                        MediaUiFailureReason.PreviewLoadFailed,
                        MediaUiFailureReason.MaterializationFailed,
                        -> previewLoadFailedMessage
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalImagePreviewState.Failed(previewLoadFailedMessage)
        }
    }

    fun requestMaterialization() {
        if (materializing) return
        materializing = true
        materializationMessage = null
        var callbackConsumed = false
        try {
            mediaUiPorts.materializationRunner.start(assetId) { result ->
                if (!callbackConsumed) {
                    callbackConsumed = true
                    materializing = false
                    when (result) {
                        MediaMaterializationUiResult.Materialized -> loadGeneration += 1
                        MediaMaterializationUiResult.Cancelled -> {
                            materializationMessage = materializationCancelledMessage
                        }
                        is MediaMaterializationUiResult.Failed -> {
                            materializationMessage = materializationFailedMessage
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (!callbackConsumed) {
                callbackConsumed = true
                materializing = false
                materializationMessage = materializationFailedMessage
            }
        }
    }

    MarkdownImagePreviewFrame(title = title) {
        when (val current = previewState) {
            LocalImagePreviewState.Loading -> Text(
                text = stringResource(Res.string.image_preview_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            is LocalImagePreviewState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = block.altText.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            )

            LocalImagePreviewState.Missing -> {
                Text(
                    text = stringResource(Res.string.image_preview_missing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                materializationMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = !materializing,
                    onClick = ::requestMaterialization,
                ) {
                    Text(
                        if (materializing) {
                            stringResource(Res.string.image_preview_downloading)
                        } else {
                            stringResource(Res.string.image_preview_download)
                        },
                    )
                }
            }

            is LocalImagePreviewState.Failed -> Text(
                text = current.safeMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MarkdownImagePreviewFrame(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                SomedayDesignDefaults.CellShape,
            )
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        content()
    }
}

@Composable
private fun MarkdownInlineText(inlines: List<MarkdownPreviewInline>) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline.kind) {
                MarkdownInlineKind.Text -> append(inline.text)
                MarkdownInlineKind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inline.text)
                }

                MarkdownInlineKind.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inline.text)
                }

                MarkdownInlineKind.Link -> withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(inline.text)
                }
            }
        }
    }
    Text(annotated)
}

@Composable
private fun NotebookSheet(
    state: NotesUiState,
    onCreateNotebook: (String) -> Unit,
    onRenameNotebook: (String, String) -> Unit,
    onDeleteNotebook: (String) -> Unit,
    onResolveNotebookConflict: (String, String) -> Unit,
    onShowRecentlyDeleted: () -> Unit,
    onSelectNotebook: (String) -> Unit,
) {
    var creatingNotebook by remember { mutableStateOf(false) }
    var newNotebookTitle by remember { mutableStateOf("") }
    var renamingNotebookId by remember { mutableStateOf<String?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteNotebookId by remember { mutableStateOf<String?>(null) }
    val deleteNotebook = state.notebooks.firstOrNull { it.id == deleteNotebookId }

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(Res.string.nav_notebooks),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShowRecentlyDeleted) {
                    Icon(Lucide.Trash, contentDescription = stringResource(Res.string.deleted_title))
                }
                IconButton(onClick = { creatingNotebook = true }) {
                    Icon(Lucide.Plus, contentDescription = stringResource(Res.string.nav_new_notebook))
                }
            }
        }
        AnimatedVisibility(visible = creatingNotebook) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            ) {
                OutlinedTextField(
                    value = newNotebookTitle,
                    onValueChange = { newNotebookTitle = it },
                    label = { Text(stringResource(Res.string.notebook_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onCreateNotebook(newNotebookTitle)
                            newNotebookTitle = ""
                            creatingNotebook = false
                        },
                        enabled = newNotebookTitle.isNotBlank(),
                    ) {
                        Text(stringResource(Res.string.common_create))
                    }
                    TextButton(
                        onClick = {
                            newNotebookTitle = ""
                            creatingNotebook = false
                        },
                    ) {
                        Text(stringResource(Res.string.common_cancel))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        if (state.notebooks.isEmpty()) {
            EmptyState(
                icon = Lucide.NotebookText,
                title = stringResource(Res.string.nav_no_notebooks),
            ) {
                Button(onClick = { creatingNotebook = true }) {
                    Text(stringResource(Res.string.nav_create_notebook))
                }
            }
        } else {
            state.notebooks.forEach { notebook ->
                val selected = notebook.id == state.selectedNotebookId
                val conflict = state.notebookConflicts[notebook.id]
                NotebookSheetRow(
                    title = notebook.title,
                    selected = selected,
                    syncBadge = notebook.syncBadge,
                    onSelect = { onSelectNotebook(notebook.id) },
                    onRename = {
                        renamingNotebookId = notebook.id
                        renameTitle = notebook.title
                    },
                    onDelete = { deleteNotebookId = notebook.id },
                    mutationEnabled = conflict == null,
                )
                if (conflict != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 34.dp, end = 8.dp, bottom = 12.dp),
                    ) {
                        Text(
                            stringResource(Res.string.notebook_conflict_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        conflict.branches.forEachIndexed { index, branch ->
                            Button(
                                onClick = { onResolveNotebookConflict(notebook.id, branch.versionId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (branch.deleted) stringResource(Res.string.notebook_use_branch_deleted, index + 1)
                                    else stringResource(Res.string.notebook_use_branch, index + 1, branch.title ?: "", branch.sortOrder ?: 0L),
                                )
                            }
                        }
                    }
                }
                if (renamingNotebookId == notebook.id) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 34.dp, bottom = 10.dp),
                    ) {
                        OutlinedTextField(
                            value = renameTitle,
                            onValueChange = { renameTitle = it },
                            label = { Text(stringResource(Res.string.notebook_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onRenameNotebook(notebook.id, renameTitle)
                                    renamingNotebookId = null
                                },
                                enabled = renameTitle.isNotBlank(),
                            ) {
                                Text(stringResource(Res.string.common_save))
                            }
                            TextButton(onClick = { renamingNotebookId = null }) {
                                Text(stringResource(Res.string.common_cancel))
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (deleteNotebook != null) {
        ConfirmActionDialog(
            title = stringResource(Res.string.notebook_delete_title, deleteNotebook.title),
            text = stringResource(Res.string.notebook_delete_message),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                onDeleteNotebook(deleteNotebook.id)
                deleteNotebookId = null
            },
            onDismiss = { deleteNotebookId = null },
        )
    }
}

@Composable
private fun RecentlyDeletedSheet(
    state: NotesUiState,
    onRestoreDeletedItem: (String) -> Unit,
) {
    val deletedNotes = state.deletedWorkspaceItems.filter { it.type == DeletedWorkspaceItemType.Note }
    val deletedNotebooks = state.deletedWorkspaceItems.filter { it.type == DeletedWorkspaceItemType.Notebook }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text = stringResource(Res.string.deleted_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(Res.string.deleted_items_count, state.deletedWorkspaceItems.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            stringResource(Res.string.deleted_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        HorizontalDivider()
        if (state.deletedWorkspaceItems.isEmpty()) {
            EmptyState(
                icon = Lucide.Trash,
                title = stringResource(Res.string.deleted_empty),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            ) {
                deletedWorkspaceSection(
                    key = "notes",
                    title = Res.string.deleted_notes_section,
                    items = deletedNotes,
                    onRestoreDeletedItem = onRestoreDeletedItem,
                )
                deletedWorkspaceSection(
                    key = "notebooks",
                    title = Res.string.deleted_notebooks_section,
                    items = deletedNotebooks,
                    onRestoreDeletedItem = onRestoreDeletedItem,
                )
            }
        }
    }
}

private fun LazyListScope.deletedWorkspaceSection(
    key: String,
    title: StringResource,
    items: List<DeletedWorkspaceItem>,
    onRestoreDeletedItem: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "deleted-section-$key", contentType = "section-label") {
        Text(
            text = stringResource(title),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
    items(
        items = items,
        key = { item -> "deleted-${item.type}-${item.entityId}" },
        contentType = { "deleted-item" },
    ) { item ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        Res.string.deleted_on,
                        item.deletedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                enabled = item.canRestore,
                onClick = { onRestoreDeletedItem(item.entityId) },
            ) {
                Text(
                    if (item.canRestore) {
                        stringResource(Res.string.deleted_undelete)
                    } else {
                        stringResource(Res.string.deleted_snapshot_expired)
                    },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun NotebookSheetRow(
    title: String,
    selected: Boolean,
    syncBadge: NoteSyncBadge,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    mutationEnabled: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, SomedayDesignDefaults.CellShape)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).clickable(onClick = onSelect),
        ) {
            Icon(
                imageVector = Lucide.NotebookText,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            NoteSyncIndicator(syncBadge = syncBadge, showText = false)
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Lucide.Ellipsis,
                    contentDescription = stringResource(Res.string.notebook_actions),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_rename)) },
                        enabled = mutationEnabled,
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_delete)) },
                        enabled = mutationEnabled,
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun NoteSyncIndicator(
    syncBadge: NoteSyncBadge,
    showText: Boolean = true,
) {
    if (!shouldShowSyncBadge(syncBadge)) {
        return
    }
    val icon = syncBadgeIcon(syncBadge)
    val label = syncBadgeShortLabel(syncBadge)
    val containerColor = when {
        syncBadge is NoteSyncBadge.Error -> MaterialTheme.colorScheme.errorContainer
        syncBadge is NoteSyncBadge.Conflict -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        syncBadge is NoteSyncBadge.Error -> MaterialTheme.colorScheme.onErrorContainer
        syncBadge is NoteSyncBadge.Conflict -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = SomedayDesignDefaults.CellShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (showText) 8.dp else 7.dp, vertical = 5.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp))
            if (showText) {
                Text(label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun shouldShowSyncBadge(syncBadge: NoteSyncBadge): Boolean =
    syncBadge is NoteSyncBadge.Error || syncBadge is NoteSyncBadge.Conflict

private fun syncBadgeIcon(syncBadge: NoteSyncBadge): ImageVector =
    when (syncBadge) {
        NoteSyncBadge.Synced -> Lucide.Cloud
        NoteSyncBadge.Pending -> Lucide.Cloud
        is NoteSyncBadge.Error -> Lucide.X
        is NoteSyncBadge.Conflict -> Lucide.History
    }
