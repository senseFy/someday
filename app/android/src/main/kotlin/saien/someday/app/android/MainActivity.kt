package saien.someday.app.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.ui.SomedayApp
import saien.someday.ui.SomedayBootstrapScreen
import saien.someday.ui.i18n.applyAppLanguageTag
import saien.someday.ui.media.MediaImportRunner
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaMaterializationRunner
import saien.someday.ui.media.MediaMaterializationUiResult
import saien.someday.ui.media.MediaPreviewLoader
import saien.someday.ui.media.MediaUiFailureReason
import saien.someday.ui.media.MediaUiPorts
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.SettingsImportSummary
import saien.someday.ui.settings.WorkspacePairingScanner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.source

class MainActivity : ComponentActivity() {
    private val clientRepositories: AndroidClientRepositories
        get() = (application as SomedayApplication).clientRepositories
    private var hasDeliveredInitialResume = false
    private var foregroundSyncSignal by mutableIntStateOf(0)
    private var pendingOpenMemories by mutableStateOf(false)
    private var pendingDayOneImportCallback: ((SettingsImportSummary) -> Unit)? = null
    private var pendingMediaImportCallback: ((MediaImportUiResult) -> Unit)? = null
    private var pendingPairingScanResult: ((String) -> Unit)? = null
    private var pendingPairingScanCancelled: (() -> Unit)? = null
    private val notificationPermissionBridge = AndroidNotificationPermissionBridge()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionBridge.complete(granted)
        }
    private val pairingScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val onResult = pendingPairingScanResult
            val onCancelled = pendingPairingScanCancelled
            pendingPairingScanResult = null
            pendingPairingScanCancelled = null
            val value = result.data?.getStringExtra(WorkspacePairingScanActivity.EXTRA_RESULT)
            if (result.resultCode == RESULT_OK && !value.isNullOrBlank()) {
                onResult?.invoke(value)
            } else {
                onCancelled?.invoke()
            }
        }
    private val pairingCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchPairingScanner()
            } else {
                pendingPairingScanResult = null
                pendingPairingScanCancelled?.invoke()
                pendingPairingScanCancelled = null
            }
        }
    private val workspacePairingScanner = object : WorkspacePairingScanner {
        override val available: Boolean = true

        override fun scan(
            onResult: (String) -> Unit,
            onCancelled: () -> Unit,
        ) {
            pendingPairingScanResult = onResult
            pendingPairingScanCancelled = onCancelled
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchPairingScanner()
            } else {
                pairingCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    private val dayOneImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingDayOneImportCallback ?: return@registerForActivityResult
        pendingDayOneImportCallback = null
        if (uri == null) {
            callback(SettingsImportSummary.failure("Day One", "Day One import cancelled."))
            return@registerForActivityResult
        }
        Thread {
            val summary = runCatching {
                val archiveBytes = contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                    ?: error("Could not read selected Day One export.")
                clientRepositories.importDayOneArchive(
                    archiveBytes = archiveBytes,
                    fallbackJournalTitle = uri.lastPathSegment ?: "Day One",
                )
            }.getOrElse { failure ->
                SettingsImportSummary.failure(
                    sourceName = "Day One",
                    message = "Day One import failed: ${failure.message ?: "unknown error"}",
                )
            }
            runOnUiThread { callback(summary) }
        }.start()
    }
    private val mediaImportLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = pendingMediaImportCallback ?: return@registerForActivityResult
        pendingMediaImportCallback = null
        if (uri == null) {
            callback(MediaImportUiResult.Cancelled)
            return@registerForActivityResult
        }
        Thread {
            val result = contentResolver.importSelectedImage(uri, clientRepositories.localMediaAssetStore)
            runOnUiThread { callback(result) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentRequestsMemories = intent.consumeOpenMemoriesRequest()
        pendingOpenMemories =
            savedInstanceState?.getBoolean(StatePendingOpenMemories, false) == true ||
            intentRequestsMemories
        val startupTrace = AndroidStartupTrace(enabled = BuildConfig.DEBUG)
        startupTrace.mark("activity.onCreate.start")
        Log.i("Someday", AndroidShellEntrypoint.startupLog())
        val onThisDayNotificationScheduler = AndroidOnThisDayNotificationScheduler(
            context = applicationContext,
            requestPermission = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    true
                } else {
                    notificationPermissionBridge.awaitPermission {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
        )
        val onThisDayNotificationTimeFormatter =
            AndroidOnThisDayNotificationTimeFormatter(applicationContext)
        setContent {
            val traceMark = remember {
                startupTrace.mark("setContent.content.start")
                startupTrace::mark
            }
            var bootstrap by remember { mutableStateOf<AndroidAppBootstrap?>(null) }
            var bootstrapError by remember { mutableStateOf<String?>(null) }
            var currentTheme by remember { mutableStateOf(ClientTheme.System) }

            LaunchedEffect(Unit) {
                bootstrap = runCatching {
                    withContext(Dispatchers.Default) {
                        val repositories = startupTrace.measure("clientRepositories") { clientRepositories }
                        val initialSettings = startupTrace.measure("settings.load") {
                            repositories.settingsRepository.load()
                        }
                        AndroidAppBootstrap(
                            repositories = repositories,
                            initialSettings = initialSettings,
                        )
                    }
                }.onSuccess { loaded ->
                    currentTheme = loaded.initialSettings.theme
                }.onFailure { failure ->
                    bootstrapError = failure.message ?: "unknown error"
                }.getOrNull()
            }

            val loaded = bootstrap
            if (loaded == null) {
                val systemDark = resources.configuration.isSystemDarkTheme()
                SideEffect {
                    window.applySomedaySystemBars(currentTheme.isDarkTheme(systemDark))
                }
                // Localized default "Preparing…" is resolved inside SomedayBootstrapScreen.
                // Startup failures pass the raw error (rare; not part of normal UI chrome).
                SomedayBootstrapScreen(
                    theme = currentTheme,
                    message = bootstrapError,
                )
                return@setContent
            }
            val systemDark = resources.configuration.isSystemDarkTheme()
            val darkTheme = currentTheme.isDarkTheme(systemDark)
            val locationCaptureAdapter = remember {
                AndroidLocationCaptureAdapter(this@MainActivity)
            }
            SideEffect {
                window.applySomedaySystemBars(darkTheme)
            }
            SomedayApp(
                platformName = AndroidShellEntrypoint.platformName,
                developerOptionsEnabled = BuildConfig.DEBUG,
                initialSettings = loaded.initialSettings,
                notesRepository = loaded.repositories.notesRepository,
                locationCaptureAdapter = locationCaptureAdapter,
                onThisDayNotificationScheduler = onThisDayNotificationScheduler,
                onThisDayNotificationTimeFormatter = onThisDayNotificationTimeFormatter,
                pendingOpenMemories = pendingOpenMemories,
                onPendingOpenMemoriesConsumed = { pendingOpenMemories = false },
                loadSettings = loaded.repositories.settingsRepository::load,
                onSettingsChanged = loaded.repositories.settingsRepository::save,
                workspacePreferencesConflictResolver =
                    loaded.repositories.settingsRepository as? WorkspacePreferencesConflictResolver,
                onAppliedSettingsChanged = { appliedSettings ->
                    currentTheme = appliedSettings.theme
                    applyAppLanguageTag(appliedSettings.appLanguage.languageTag)
                    applyActivityLocale(appliedSettings.appLanguage)
                },
                onLocalExport = loaded.repositories::exportLocalDataSummary,
                dayOneImportRunner = DayOneImportRunner { onResult ->
                    pendingDayOneImportCallback = onResult
                    dayOneImportLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                        ),
                    )
                },
                mediaUiPorts = remember(loaded.repositories) {
                    MediaUiPorts(
                        importRunner = MediaImportRunner { _, onResult ->
                            pendingMediaImportCallback = onResult
                            mediaImportLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        previewLoader = MediaPreviewLoader { assetId ->
                            withContext(Dispatchers.IO) {
                                loaded.repositories.localMediaAssetStore.loadMediaPreview(assetId)
                            }
                        },
                        materializationRunner = MediaMaterializationRunner { assetId, onResult ->
                            Thread {
                                val result = runCatching {
                                    loaded.repositories.mediaCoordinator.materialize(assetId)
                                    MediaMaterializationUiResult.Materialized
                                }.getOrElse {
                                    MediaMaterializationUiResult.Failed(
                                        MediaUiFailureReason.MaterializationFailed,
                                    )
                                }
                                runOnUiThread { onResult(result) }
                            }.start()
                        },
                    )
                },
                selfHostedSetupClient = loaded.repositories.selfHostedSetupClient,
                selfHostedSessionCredentialStore = loaded.repositories.selfHostedSessionCredentialStore,
                manualSyncRunner = loaded.repositories.manualSyncRunner,
                automaticSyncEligible = loaded.repositories.automaticSyncEligible,
                workspacePairingInvitationCreator = loaded.repositories.workspacePairingInvitationCreator,
                workspacePairingInvitationJoiner = loaded.repositories.workspacePairingInvitationJoiner,
                workspacePairingInvitationCanceller = loaded.repositories.workspacePairingInvitationCanceller,
                workspacePairingScanner = workspacePairingScanner,
                foregroundSyncSignal = foregroundSyncSignal,
                startupTrace = traceMark,
            )
        }
        startupTrace.mark("activity.onCreate.end")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.consumeOpenMemoriesRequest()) {
            pendingOpenMemories = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(StatePendingOpenMemories, pendingOpenMemories)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (hasDeliveredInitialResume) {
            foregroundSyncSignal += 1
        } else {
            hasDeliveredInitialResume = true
        }
    }

    private fun launchPairingScanner() {
        pairingScanLauncher.launch(Intent(this, WorkspacePairingScanActivity::class.java))
    }
}

private fun Intent.consumeOpenMemoriesRequest(): Boolean =
    getBooleanExtra(OnThisDayNotificationContract.ExtraOpenMemories, false).also { requested ->
        if (requested) {
            removeExtra(OnThisDayNotificationContract.ExtraOpenMemories)
        }
    }

private data class AndroidAppBootstrap(
    val repositories: AndroidClientRepositories,
    val initialSettings: ClientSettings,
)

private const val SomedayLightSystemBarColor = 0xFFFAFBFC.toInt()
private const val SomedayDarkSystemBarColor = 0xFF111315.toInt()
private const val StatePendingOpenMemories = "saien.someday.app.state.PENDING_OPEN_MEMORIES"

private fun ClientTheme.isDarkTheme(systemDark: Boolean): Boolean =
    when (this) {
        ClientTheme.System -> systemDark
        ClientTheme.Light -> false
        ClientTheme.Dark -> true
    }

private fun Configuration.isSystemDarkTheme(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * Updates this activity's configuration locales so Android resources track the
 * in-app language preference.
 */
@Suppress("DEPRECATION")
private fun ComponentActivity.applyActivityLocale(language: AppLanguage) {
    val locale = language.languageTag?.let(Locale::forLanguageTag)
        ?: Resources.getSystem().configuration.locales[0]
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))
    resources.updateConfiguration(config, resources.displayMetrics)
}

@Suppress("DEPRECATION")
private fun Window.applySomedaySystemBars(darkTheme: Boolean) {
    val systemBarColor = if (darkTheme) SomedayDarkSystemBarColor else SomedayLightSystemBarColor
    val useDarkSystemBarIcons = !darkTheme
    statusBarColor = systemBarColor
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val lightSystemBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        insetsController?.setSystemBarsAppearance(
            if (useDarkSystemBarIcons) lightSystemBars else 0,
            lightSystemBars,
        )
    } else {
        val lightSystemBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility = if (useDarkSystemBarIcons) {
            decorView.systemUiVisibility or lightSystemBars
        } else {
            decorView.systemUiVisibility and lightSystemBars.inv()
        }
    }
}

private class AndroidStartupTrace(
    private val enabled: Boolean,
) {
    private val startedAtMillis: Long = SystemClock.elapsedRealtime()

    fun mark(label: String) {
        if (enabled) {
            Log.i("SomedayStartup", "${SystemClock.elapsedRealtime() - startedAtMillis}ms $label")
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
