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
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.ui.SomedayApp
import saien.someday.ui.SomedayBootstrapScreen
import saien.someday.ui.media.MediaImportRunner
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaMaterializationRunner
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewLoader
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.SettingsImportSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File

fun main() = application {
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
                    val repositories = createDesktopClientRepositories()
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
                        importCoroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                file.importSelectedImage(clientRepositories.localMediaAssetStore)
                            }
                            onResult(result)
                        }
                    }
                },
                previewLoader = MediaPreviewLoader { assetId ->
                    withContext(Dispatchers.IO) {
                        clientRepositories.localMediaAssetStore.loadMediaPreview(assetId)
                    }
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
                val dialog = FileDialog(window, "Import Day One export", FileDialog.LOAD).apply {
                    file = "*.zip"
                    isVisible = true
                }
                val selectedDirectory = dialog.directory
                val selectedFile = dialog.file
                if (selectedDirectory == null || selectedFile == null) {
                    onResult(SettingsImportSummary.failure("Day One", "Day One import cancelled."))
                } else {
                    val file = File(selectedDirectory, selectedFile)
                    importCoroutineScope.launch {
                        val summary = withContext(Dispatchers.Default) {
                            runCatching { clientRepositories.importDayOneArchive(file) }.getOrElse { failure ->
                                SettingsImportSummary.failure(
                                    sourceName = "Day One",
                                    message = "Day One import failed: ${failure.message ?: "unknown error"}",
                                )
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
