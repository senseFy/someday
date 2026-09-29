package saien.someday.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import saien.someday.domain.settings.AccountDataResetManager
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.ui.SomedayApp
import saien.someday.ui.SomedayBootstrapScreen
import saien.someday.ui.media.MediaImportRunner
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaMaterializationRunner
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaPreviewLoader
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.SettingsImportOutcome
import saien.someday.ui.settings.SettingsImportSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File

fun main() = launchDesktopApp()

internal fun launchDesktopApp(
    accountDataResetManagerOverride: AccountDataResetManager? = null,
    repositoryFactory: () -> DesktopClientRepositories = ::createDesktopClientRepositories,
) = application {
    println(DesktopShellEntrypoint.startupLog())
    val windowState = rememberWindowState(width = 1220.dp, height = 820.dp)
    val usesImmersiveMacChrome = isMacOs()
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Someday",
    ) {
        LaunchedEffect(Unit) {
            if (usesImmersiveMacChrome) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        var bootstrap by remember { mutableStateOf<DesktopAppBootstrap?>(null) }
        var bootstrapError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            bootstrap = runCatching {
                withContext(Dispatchers.Default) {
                    val repositories = repositoryFactory()
                    try {
                        val initialSettings = repositories.settingsRepository.load()
                        DesktopAppBootstrap(
                            repositories = repositories,
                            initialSettings = initialSettings,
                        )
                    } catch (failure: Throwable) {
                        runCatching(repositories::close)
                        throw failure
                    }
                }
            }.onFailure { failure ->
                bootstrapError = failure.message ?: "unknown error"
            }.getOrNull()
        }

        val loaded = bootstrap
        if (loaded == null) {
            // Localized "Preparing…" is the default inside SomedayBootstrapScreen.
            SomedayBootstrapScreen(message = bootstrapError)
            return@Window
        }
        val clientRepositories = loaded.repositories
        DisposableEffect(clientRepositories) {
            onDispose(clientRepositories::close)
        }
        val importCoroutineScope = rememberCoroutineScope()
        val mediaUiPorts = remember(clientRepositories, window) {
            MediaUiPorts(
                importRunner = MediaImportRunner { pickerTitle, onResult ->
                    importCoroutineScope.launch {
                        val captured = withContext(Dispatchers.IO) {
                            runCatching { clientRepositories.localMediaAssetStore.captureWorkspace() }
                        }.getOrElse {
                            onResult(MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed))
                            return@launch
                        }
                        val dialog = FileDialog(window, pickerTitle, FileDialog.LOAD).apply {
                            isMultipleMode = false
                            isVisible = true
                        }
                        val selectedDirectory = dialog.directory
                        val selectedFile = dialog.file
                        if (selectedDirectory == null || selectedFile == null) {
                            onResult(MediaImportUiResult.Cancelled)
                        } else {
                            val file = File(selectedDirectory, selectedFile)
                            val result = withContext(Dispatchers.IO) {
                                file.importSelectedImage(clientRepositories.localMediaAssetStore, captured)
                            }
                            onResult(result)
                        }
                    }
                },
                previewLoader = MediaPreviewLoader { assetId ->
                    val (snapshot, preview) = withContext(Dispatchers.IO) {
                        clientRepositories.localMediaAssetStore.captureWorkspace() to
                            clientRepositories.localMediaAssetStore.loadMediaPreview(assetId)
                    }
                    if (clientRepositories.workspaceProductAccess.isCurrent(snapshot)) preview else MediaPreviewUiResult.Missing
                },
                materializationRunner = MediaMaterializationRunner { assetId, onResult ->
                    importCoroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                clientRepositories.mediaCoordinator.materialize(assetId)
                                MediaMaterializationUiResult.Materialized
                            }.getOrElse {
                                MediaMaterializationUiResult.Failed(
                                    MediaUiFailureReason.MaterializationFailed,
                                )
                            }
                        }
                        onResult(result)
                    }
                },
            )
        }
        SomedayApp(
            platformName = DesktopShellEntrypoint.platformName,
            windowChromeTopInset = if (usesImmersiveMacChrome) 32.dp else 0.dp,
            developerOptionsEnabled = DesktopBuildConfig.DEVELOPER_OPTIONS_ENABLED,
            initialSettings = loaded.initialSettings,
            notesRepository = clientRepositories.notesRepository,
            loadSettings = clientRepositories.settingsRepository::load,
            onSettingsChanged = clientRepositories.settingsRepository::save,
            workspacePreferencesConflictResolver =
                clientRepositories.settingsRepository as? WorkspacePreferencesConflictResolver,
            onLocalExport = clientRepositories::exportLocalDataSummary,
            dayOneImportRunner = DayOneImportRunner { onResult ->
                importCoroutineScope.launch {
                    val captured = withContext(Dispatchers.IO) {
                        runCatching { clientRepositories.workspaceProductAccess.capture() }
                    }.getOrElse {
                        onResult(SettingsImportSummary(SettingsImportOutcome.Failed))
                        return@launch
                    }
                    val dialog = FileDialog(window, "Import Day One export", FileDialog.LOAD).apply {
                        file = "*.zip"
                        isVisible = true
                    }
                    val selectedDirectory = dialog.directory
                    val selectedFile = dialog.file
                    if (selectedDirectory == null || selectedFile == null) {
                        onResult(SettingsImportSummary(SettingsImportOutcome.Cancelled))
                    } else {
                        val file = File(selectedDirectory, selectedFile)
                        val summary = withContext(Dispatchers.Default) {
                            runCatching { clientRepositories.importDayOneArchive(file, captured) }.getOrElse {
                                SettingsImportSummary(SettingsImportOutcome.Failed)
                            }
                        }
                        onResult(summary)
                    }
                }
            },
            mediaUiPorts = mediaUiPorts,
            selfHostedSetupClient = clientRepositories.selfHostedSetupClient,
            selfHostedSessionCredentialStore = clientRepositories.selfHostedSessionCredentialStore,
            selfHostedConnectionSwitcher = clientRepositories.selfHostedConnectionSwitcher,
            manualSyncRunner = clientRepositories.manualSyncRunner,
            automaticSyncEligible = clientRepositories.automaticSyncEligible,
            workspacePairingInvitationCreator = clientRepositories.workspacePairingInvitationCreator,
            workspacePairingInvitationJoiner = clientRepositories.workspacePairingInvitationJoiner,
            workspacePairingInvitationCanceller = clientRepositories.workspacePairingInvitationCanceller,
            workspaceRecoveryManager = clientRepositories.workspaceRecoveryManager,
            accountDataResetManager = accountDataResetManagerOverride ?: clientRepositories.accountDataResetManager,
            workspaceProductAccess = clientRepositories.workspaceProductAccess,
            pullToRefreshSyncEnabled = false,
        )
    }
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").contains("Mac", ignoreCase = true)

private data class DesktopAppBootstrap(
    val repositories: DesktopClientRepositories,
    val initialSettings: ClientSettings,
)
