@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package saien.someday.app.ios

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.isSafeOriginalFileName
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetLocalState
import saien.someday.data.media.MediaAssetVerificationResult
import saien.someday.ui.SomedayApp
import saien.someday.ui.SomedayBootstrapScreen
import saien.someday.ui.media.MAX_MEDIA_PREVIEW_BYTE_COUNT
import saien.someday.ui.media.MediaImportRunner
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaMaterializationRunner
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewLoader
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.SettingsImportSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.time.Clock
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeArchive
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.posix.memcpy
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    val startupTrace = IosStartupTrace(enabled = IosBuildConfig.DEVELOPER_OPTIONS_ENABLED)
    startupTrace.mark("MainViewController.start")
    var rootController: UIViewController? = null
    val openMemoriesFromNotification = mutableStateOf(false)
    val notificationDelegate = IosOnThisDayNotificationDelegate {
        openMemoriesFromNotification.value = true
    }
    val onThisDayNotificationTimeFormatter = IosOnThisDayNotificationTimeFormatter()
    UNUserNotificationCenter.currentNotificationCenter().delegate = notificationDelegate
    val controller = ComposeUIViewController(
        configure = {
            onFocusBehavior = OnFocusBehavior.DoNothing
        },
    ) {
        val traceMark = remember {
            startupTrace.mark("ComposeUIViewController.content.start")
            startupTrace::mark
        }
        var bootstrap by remember { mutableStateOf<IosAppBootstrap?>(null) }
        var bootstrapError by remember { mutableStateOf<String?>(null) }
        var pendingOpenMemories by remember { openMemoriesFromNotification }

        LaunchedEffect(Unit) {
            bootstrap = runCatching {
                withContext(Dispatchers.Default) {
                    val clientRepositories = startupTrace.measure("clientRepositories") {
                        createIosClientRepositories()
                    }
                    try {
                        val initialSettings = startupTrace.measure("settings.load") {
                            clientRepositories.settingsRepository.load()
                        }
                        IosAppBootstrap(
                            repositories = clientRepositories,
                            initialSettings = initialSettings,
                        )
                    } catch (failure: Throwable) {
                        runCatching(clientRepositories::close)
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
            return@ComposeUIViewController
        }

        val clientRepositories = loaded.repositories
        DisposableEffect(clientRepositories) {
            onDispose(clientRepositories::close)
        }
        val initialSettings = loaded.initialSettings
        var foregroundSyncSignal by remember { mutableStateOf(0) }
        val onThisDayNotificationScheduler = remember(clientRepositories.notesRepository) {
            IosOnThisDayNotificationScheduler(notesRepository = clientRepositories.notesRepository)
        }
        val dayOneImportRunner = remember(clientRepositories) {
            IosDayOneImportRunner(
                rootControllerProvider = { rootController },
                clientRepositories = clientRepositories,
            )
        }
        val workspacePairingScanner = remember {
            IosWorkspacePairingScanner(rootControllerProvider = { rootController })
        }
        val mediaCoroutineScope = rememberCoroutineScope()
        val mediaUiPorts = remember(clientRepositories) {
            MediaUiPorts(
                importRunner = IosMediaImportRunner(
                    rootControllerProvider = { rootController },
                    clientRepositories = clientRepositories,
                ),
                previewLoader = MediaPreviewLoader { assetId ->
                    withContext(Dispatchers.Default) {
                        clientRepositories.localMediaAssetStore.loadBoundedPreview(assetId)
                    }
                },
                materializationRunner = MediaMaterializationRunner { assetId, onResult ->
                    mediaCoroutineScope.launch {
                        val result = withContext(Dispatchers.Default) {
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
        DisposableEffect(notificationDelegate) {
            val observer = NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = null,
                queue = null,
            ) {
                foregroundSyncSignal += 1
            }
            onDispose {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
            }
        }
        SomedayApp(
            platformName = IosShellEntrypoint.platformName,
            developerOptionsEnabled = IosBuildConfig.DEVELOPER_OPTIONS_ENABLED,
            initialSettings = initialSettings,
            notesRepository = clientRepositories.notesRepository,
            locationCaptureAdapter = IosLocationCaptureAdapter(),
            onThisDayNotificationScheduler = onThisDayNotificationScheduler,
            onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
            pendingOpenMemories = pendingOpenMemories,
            onPendingOpenMemoriesConsumed = { pendingOpenMemories = false },
            onSettingsChanged = clientRepositories.settingsRepository::save,
            workspacePreferencesConflictResolver =
                clientRepositories.settingsRepository as? WorkspacePreferencesConflictResolver,
            onLocalExport = clientRepositories::exportLocalDataSummary,
            dayOneImportRunner = dayOneImportRunner,
            mediaUiPorts = mediaUiPorts,
            selfHostedSetupClient = clientRepositories.selfHostedSetupClient,
            selfHostedSessionCredentialStore = clientRepositories.selfHostedSessionCredentialStore,
            manualSyncRunner = clientRepositories.manualSyncRunner,
            bindManualSyncProgressListener = clientRepositories.bindManualSyncProgressListener,
            workspacePairingInvitationCreator = clientRepositories.workspacePairingInvitationCreator,
            workspacePairingInvitationJoiner = clientRepositories.workspacePairingInvitationJoiner,
            workspacePairingInvitationCanceller = clientRepositories.workspacePairingInvitationCanceller,
            workspacePairingScanner = workspacePairingScanner,
            foregroundSyncSignal = foregroundSyncSignal,
            startupTrace = traceMark,
        )
    }
    rootController = controller
    return controller
}

private class IosOnThisDayNotificationDelegate(
    private val onOpenMemories: () -> Unit,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        if (iosLaunchWantsOpenMemories(userInfo)) {
            onOpenMemories()
        }
        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (ULong) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or
                UNNotificationPresentationOptionList or
                UNNotificationPresentationOptionSound,
        )
    }
}

private data class IosAppBootstrap(
    val repositories: IosClientRepositories,
    val initialSettings: ClientSettings,
)

@OptIn(ExperimentalForeignApi::class)
private class IosMediaImportRunner(
    private val rootControllerProvider: () -> UIViewController?,
    private val clientRepositories: IosClientRepositories,
) : MediaImportRunner {
    private var activeDelegate: IosMediaDocumentPickerDelegate? = null

    @Suppress("UNUSED_PARAMETER")
    override fun start(
        pickerTitle: String,
        onResult: (MediaImportUiResult) -> Unit,
    ) {
        val rootController = rootControllerProvider()
        if (rootController == null) {
            onResult(MediaImportUiResult.Failed(MediaUiFailureReason.Unavailable))
            return
        }
        val delegate = IosMediaDocumentPickerDelegate(clientRepositories) { result ->
            activeDelegate = null
            onResult(result)
        }
        activeDelegate = delegate
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeImage),
            asCopy = true,
        )
        picker.setDelegate(delegate)
        rootController.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosMediaDocumentPickerDelegate(
    private val clientRepositories: IosClientRepositories,
    private val onComplete: (MediaImportUiResult) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onComplete(MediaImportUiResult.Cancelled)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    url.importInto(clientRepositories.localMediaAssetStore)
                }.getOrElse {
                    MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed)
                }
            }
            onComplete(result)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onComplete(MediaImportUiResult.Cancelled)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.importInto(store: AuthorityCoordinatedMediaAssetStore): MediaImportUiResult {
    val didAccess = startAccessingSecurityScopedResource()
    try {
        val localPath = path ?: error("The selected image has no local file path.")
        val originalName = lastPathComponent?.takeIf(::isSafeOriginalFileName)
        val source = FileSystem.SYSTEM.source(localPath.toPath())
        val imported = try {
            store.importAsset(
                source = source,
                request = MediaAssetImportRequest(
                    originalFileName = originalName,
                    maxBytes = MAX_MEDIA_PREVIEW_BYTE_COUNT.toLong(),
                    maxDecodedPixelCount = MAX_MEDIA_PREVIEW_PIXEL_COUNT,
                ),
            )
        } finally {
            source.close()
        }
        return MediaImportUiResult.Imported(
            imported.asset.metadata.id,
            originalName?.substringBeforeLast('.')?.take(120).orEmpty(),
        )
    } finally {
        if (didAccess) stopAccessingSecurityScopedResource()
    }
}

private fun AuthorityCoordinatedMediaAssetStore.loadBoundedPreview(assetId: MediaAssetId): MediaPreviewUiResult {
    val asset = getAsset(assetId) ?: return MediaPreviewUiResult.Missing
    when (asset.localState) {
        MediaAssetLocalState.Missing -> return MediaPreviewUiResult.Missing
        MediaAssetLocalState.Corrupt -> return MediaPreviewUiResult.Missing
        MediaAssetLocalState.Available -> Unit
    }
    if (asset.metadata.byteSize > MAX_MEDIA_PREVIEW_BYTE_COUNT ||
        asset.metadata.decodedPixelCount > MAX_MEDIA_PREVIEW_PIXEL_COUNT
    ) {
        return MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewTooLarge)
    }
    when (runCatching { verifyAsset(assetId) }.getOrElse {
        return MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewLoadFailed)
    }) {
        is MediaAssetVerificationResult.Verified -> Unit
        is MediaAssetVerificationResult.Missing,
        is MediaAssetVerificationResult.Corrupt,
        -> return MediaPreviewUiResult.Missing
    }
    return runCatching {
        val source = openSource(assetId).buffer()
        val bytes = try {
            source.readByteArray()
        } finally {
            source.close()
        }
        MediaPreviewUiResult.Loaded(bytes)
    }.getOrElse {
        if (getAsset(assetId)?.localState == MediaAssetLocalState.Available) {
            MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewLoadFailed)
        } else {
            MediaPreviewUiResult.Missing
        }
    }
}

private const val MAX_MEDIA_PREVIEW_PIXEL_COUNT = 12_000_000L

@OptIn(ExperimentalForeignApi::class)
private class IosDayOneImportRunner(
    private val rootControllerProvider: () -> UIViewController?,
    private val clientRepositories: IosClientRepositories,
) : DayOneImportRunner {
    private var activeDelegate: DayOneDocumentPickerDelegate? = null

    override fun start(onResult: (SettingsImportSummary) -> Unit) {
        val rootController = rootControllerProvider()
        if (rootController == null) {
            onResult(SettingsImportSummary.failure("Day One", "Day One import is unavailable on this screen."))
            return
        }
        val delegate = DayOneDocumentPickerDelegate(
            clientRepositories = clientRepositories,
            onComplete = { summary ->
                activeDelegate = null
                onResult(summary)
            },
        )
        activeDelegate = delegate
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = dayOneImportContentTypes(),
            asCopy = true,
        )
        picker.setDelegate(delegate)
        rootController.presentViewController(picker, animated = true, completion = null)
    }

    private fun dayOneImportContentTypes(): List<*> =
        listOf(
            UTTypeZIP,
            UTTypeArchive,
            UTTypeData,
        )
}

@OptIn(ExperimentalForeignApi::class)
private class DayOneDocumentPickerDelegate(
    private val clientRepositories: IosClientRepositories,
    private val onComplete: (SettingsImportSummary) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onComplete(SettingsImportSummary.failure("Day One", "Day One import cancelled."))
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val summary = withContext(Dispatchers.Default) {
                runCatching {
                    clientRepositories.importDayOneArchive(
                        archiveBytes = url.readBytes(),
                        fallbackJournalTitle = url.lastPathComponent ?: "Day One",
                    )
                }.getOrElse { failure ->
                    SettingsImportSummary.failure(
                        sourceName = "Day One",
                        message = "Day One import failed: ${failure.message ?: "unknown error"}",
                    )
                }
            }
            onComplete(summary)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onComplete(SettingsImportSummary.failure("Day One", "Day One import cancelled."))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readBytes(): ByteArray {
    val didAccess = startAccessingSecurityScopedResource()
    try {
        val data = NSData.create(contentsOfURL = this)
            ?: error("Could not read selected Day One export.")
        return data.toByteArray()
    } finally {
        if (didAccess) {
            stopAccessingSecurityScopedResource()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val output = ByteArray(size)
    if (size == 0) {
        return output
    }
    val source = bytes ?: error("Selected Day One export could not be read.")
    output.usePinned { pinned ->
        memcpy(pinned.addressOf(0), source, size.convert())
    }
    return output
}

private class IosStartupTrace(
    private val enabled: Boolean,
) {
    private val startedAtMillis: Long = Clock.System.now().toEpochMilliseconds()

    fun mark(label: String) {
        if (enabled) {
            println("SomedayStartup ${Clock.System.now().toEpochMilliseconds() - startedAtMillis}ms $label")
        }
    }

    inline fun <T> measure(
        label: String,
        block: () -> T,
    ): T {
        mark("$label.start")
        return block().also {
            mark("$label.end")
        }
    }
}
