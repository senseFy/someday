@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package saien.someday.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.jetbrains.skia.Image
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceRecoveryCode
import saien.someday.domain.settings.WorkspaceRecoveryCodeResult
import saien.someday.domain.settings.WorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryRestoreResult
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoveryStatusResult
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.ui.i18n.SettingsUiStrings
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.common_cancel
import saien.someday.ui.resources.recovery_cancel_action
import saien.someday.ui.resources.recovery_copy_action
import saien.someday.ui.resources.recovery_copy_failed
import saien.someday.ui.resources.recovery_copy_success
import saien.someday.ui.resources.recovery_confirm_action
import saien.someday.ui.resources.recovery_confirm_code_label
import saien.someday.ui.resources.recovery_replace_action
import saien.someday.ui.resources.recovery_restore_action
import saien.someday.ui.resources.recovery_restore_dialog_confirm
import saien.someday.ui.resources.settings_fb_recovery_code_format_invalid
import saien.someday.ui.resources.settings_fb_recovery_data_invalid
import saien.someday.ui.resources.settings_fb_recovery_decryption_failed
import saien.someday.ui.resources.settings_fb_recovery_key_verification_failed
import saien.someday.ui.resources.settings_fb_recovery_crypto_operation_failed
import saien.someday.ui.settings.SettingsUiController
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceRecoveryContentTest {
    @Test
    fun preparedCodeRequiresInteractiveReentryBeforeItIsPublished() = runComposeUiTest {
        val confirmationLabel = getString(Res.string.recovery_confirm_code_label)
        val confirmationAction = getString(Res.string.recovery_confirm_action)
        val copyAction = getString(Res.string.recovery_copy_action)
        val copySuccess = getString(Res.string.recovery_copy_success)
        val replaceAction = getString(Res.string.recovery_replace_action)
        val clipboard = RecordingClipboard()
        val manager = RecordingWorkspaceRecoveryManager()
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        runBlocking {
            controller.refresh()
            assertTrue(controller.prepareWorkspaceRecoveryCode())
        }

        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    WorkspaceRecoveryContent(
                        state = controller.state,
                        controller = controller,
                        actionScope = rememberCoroutineScope(),
                    )
                }
            }
        }

        onNodeWithText(RECOVERY_CODE).assertExists()
        onNodeWithText(confirmationLabel).assertExists()
        onNodeWithText(confirmationAction).assertIsNotEnabled()
        assertEquals(0, clipboard.copyCalls)

        onNodeWithText(copyAction).performClick()
        waitUntil { clipboard.copyCalls == 1 }
        assertEquals(RECOVERY_CODE, clipboard.copiedText())
        assertEquals(0, manager.publishCalls)
        onNodeWithText(copySuccess).assertExists()
        onNodeWithText(confirmationAction).assertIsNotEnabled()
        onNodeWithText(RECOVERY_CODE).assertExists()

        onNode(hasSetTextAction()).performTextInput("SOMEDAY-WRONG-CODE")
        onNodeWithText(confirmationAction).performClick()
        waitUntil { manager.confirmationCandidates.size == 1 }
        assertEquals(0, manager.publishCalls)
        onNodeWithText(RECOVERY_CODE).assertExists()

        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(RECOVERY_CODE)
        onNodeWithText(confirmationAction).performClick()
        waitUntil { manager.publishCalls == 1 }

        assertEquals(
            listOf("SOMEDAY-WRONG-CODE", RECOVERY_CODE),
            manager.confirmationCandidates,
        )
        onNodeWithText(RECOVERY_CODE).assertDoesNotExist()
        onNodeWithText(copyAction).assertDoesNotExist()
        onNodeWithText(copySuccess).assertDoesNotExist()
        onAllNodes(hasText(replaceAction)).assertCountEquals(2)
    }

    @Test
    fun copyFailureKeepsPreparedCodeVisibleAndDoesNotPublish() = runComposeUiTest {
        val copyAction = getString(Res.string.recovery_copy_action)
        val copyFailure = getString(Res.string.recovery_copy_failed)
        val clipboard = RecordingClipboard(setFailure = IllegalStateException("clipboard unavailable"))
        val manager = RecordingWorkspaceRecoveryManager()
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        runBlocking {
            controller.refresh()
            assertTrue(controller.prepareWorkspaceRecoveryCode())
        }

        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    WorkspaceRecoveryContent(
                        state = controller.state,
                        controller = controller,
                        actionScope = rememberCoroutineScope(),
                    )
                }
            }
        }

        onNodeWithText(copyAction).performClick()
        waitUntil { clipboard.copyCalls == 1 }

        onNodeWithText(copyFailure).assertExists()
        onNodeWithText(RECOVERY_CODE).assertExists()
        assertEquals(0, manager.publishCalls)
    }

    @Test
    fun unfinishedCopyIsCancelledWhenThePreparedCodeIsDiscarded() = runComposeUiTest {
        val confirmationAction = getString(Res.string.recovery_confirm_action)
        val copyAction = getString(Res.string.recovery_copy_action)
        val cancelAction = getString(Res.string.recovery_cancel_action)
        val clipboard = SuspendingClipboard()
        val manager = RecordingWorkspaceRecoveryManager()
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        runBlocking {
            controller.refresh()
            assertTrue(controller.prepareWorkspaceRecoveryCode())
        }

        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    WorkspaceRecoveryContent(
                        state = controller.state,
                        controller = controller,
                        actionScope = rememberCoroutineScope(),
                    )
                }
            }
        }

        onNode(hasSetTextAction()).performTextInput(RECOVERY_CODE)
        onNodeWithText(confirmationAction).assertIsEnabled()
        onNodeWithText(cancelAction).assertIsEnabled()

        onNodeWithText(copyAction).performClick()
        waitUntil { clipboard.started.isCompleted }
        onNodeWithText(confirmationAction).assertIsNotEnabled()
        onNodeWithText(cancelAction).assertIsNotEnabled()

        runBlocking { controller.discardPreparedWorkspaceRecoveryCode() }
        waitUntil { clipboard.cancelled.isCompleted }

        onNodeWithText(copyAction).assertDoesNotExist()
        onNodeWithText(RECOVERY_CODE).assertDoesNotExist()
        assertEquals(0, clipboard.completedCopies)
        assertEquals(0, manager.publishCalls)
    }

    @Test
    fun recoveryRequiresDestructiveConfirmationBeforeRestoringWorkspace() = runComposeUiTest {
        val restoreAction = getString(Res.string.recovery_restore_action)
        val copyAction = getString(Res.string.recovery_copy_action)
        val cancelAction = getString(Res.string.common_cancel)
        val confirmAction = getString(Res.string.recovery_restore_dialog_confirm)
        val clipboard = RecordingClipboard()
        val manager = RecordingWorkspaceRecoveryManager(
            recoveryState = WorkspaceRecoveryState.RecoveryAvailable,
        )
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        runBlocking { controller.refresh() }

        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    WorkspaceRecoveryContent(
                        state = controller.state,
                        controller = controller,
                        actionScope = rememberCoroutineScope(),
                    )
                }
            }
        }

        onNodeWithText(copyAction).assertDoesNotExist()
        onNode(hasSetTextAction()).performTextInput(RECOVERY_CODE)
        onNodeWithText(restoreAction).performClick()
        assertEquals(emptyList(), manager.recoveryCalls)

        onNodeWithText(cancelAction).performClick()
        assertEquals(emptyList(), manager.recoveryCalls)

        onNodeWithText(restoreAction).performClick()
        onNodeWithText(confirmAction).performClick()
        waitUntil { manager.recoveryCalls.size == 1 }

        assertEquals(
            listOf(RecoveryCall(RECOVERY_CODE, replaceExistingWorkspace = true)),
            manager.recoveryCalls,
        )
    }

    @Test
    fun leavingRecoveryAvailableClearsTheCodeAndDismissesConfirmation() = runComposeUiTest {
        val restoreAction = getString(Res.string.recovery_restore_action)
        val confirmAction = getString(Res.string.recovery_restore_dialog_confirm)
        val manager = RecordingWorkspaceRecoveryManager(
            recoveryState = WorkspaceRecoveryState.RecoveryAvailable,
        )
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        runBlocking { controller.refresh() }

        setContent {
            MaterialTheme {
                WorkspaceRecoveryContent(
                    state = controller.state,
                    controller = controller,
                    actionScope = rememberCoroutineScope(),
                )
            }
        }

        onNode(hasSetTextAction()).performTextInput(RECOVERY_CODE)
        onNodeWithText(restoreAction).performClick()
        onNodeWithText(confirmAction).assertExists()

        manager.recoveryState = WorkspaceRecoveryState.Configured
        runBlocking { assertTrue(controller.retryWorkspaceRecoveryStatus()) }
        onNodeWithText(confirmAction).assertDoesNotExist()

        manager.recoveryState = WorkspaceRecoveryState.RecoveryAvailable
        runBlocking { assertTrue(controller.retryWorkspaceRecoveryStatus()) }
        onNodeWithText(restoreAction).assertIsNotEnabled()
        assertEquals(emptyList(), manager.recoveryCalls)
    }

    @Test
    fun recoveryFailureMessagesRemainInTheFormAndClearAfterASuccessfulRetry() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                Locale.setDefault(locale)
                for (reason in listOf(
                    WorkspaceRecoveryReason.InvalidCodeFormat,
                    WorkspaceRecoveryReason.InvalidRecoveryData,
                    WorkspaceRecoveryReason.DecryptionFailed,
                    WorkspaceRecoveryReason.KeyVerificationFailed,
                    WorkspaceRecoveryReason.CryptoOperationFailed,
                )) {
                    renderRecoveryFailure(reason, locale.language)
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun renderRecoveryFailure(reason: WorkspaceRecoveryReason, language: String) = runComposeUiTest {
        val messages = mapOf(
            WorkspaceRecoveryReason.InvalidCodeFormat to getString(Res.string.settings_fb_recovery_code_format_invalid),
            WorkspaceRecoveryReason.InvalidRecoveryData to getString(Res.string.settings_fb_recovery_data_invalid),
            WorkspaceRecoveryReason.DecryptionFailed to getString(Res.string.settings_fb_recovery_decryption_failed),
            WorkspaceRecoveryReason.KeyVerificationFailed to getString(Res.string.settings_fb_recovery_key_verification_failed),
            WorkspaceRecoveryReason.CryptoOperationFailed to getString(Res.string.settings_fb_recovery_crypto_operation_failed),
        )
        val manager = RecordingWorkspaceRecoveryManager(
            recoveryState = WorkspaceRecoveryState.RecoveryAvailable,
            restoreFailure = reason,
        )
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = signedInCredentialStore(),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
            uiStrings = SettingsUiStrings(
                recoveryCodeFormatInvalid = messages.getValue(WorkspaceRecoveryReason.InvalidCodeFormat),
                recoveryDataInvalid = messages.getValue(WorkspaceRecoveryReason.InvalidRecoveryData),
                recoveryDecryptionFailed = messages.getValue(WorkspaceRecoveryReason.DecryptionFailed),
                recoveryKeyVerificationFailed = messages.getValue(WorkspaceRecoveryReason.KeyVerificationFailed),
                recoveryCryptoOperationFailed = messages.getValue(WorkspaceRecoveryReason.CryptoOperationFailed),
            ),
        )
        runBlocking { controller.refresh() }
        setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(360.dp).padding(16.dp).testTag("recovery-review")) {
                        WorkspaceRecoveryContent(controller.state, controller, rememberCoroutineScope())
                    }
                }
            }
        }
        val restore = getString(Res.string.recovery_restore_action)
        val confirm = getString(Res.string.recovery_restore_dialog_confirm)
        onNode(hasSetTextAction()).performTextInput(RECOVERY_CODE)
        onNodeWithText(restore).performClick()
        onNodeWithText(confirm).performClick()
        waitUntil { controller.state.sync.recovery.failureMessage != null }
        mainClock.advanceTimeBy(5_000)
        val error = onNodeWithText(messages.getValue(reason)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val input = onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(error.bottom <= input.top, "Failure text must not overlap the recovery field")
        System.getenv("SOMEDAY_REVIEW_ARTIFACTS")?.let { directory ->
            val bitmap = onNodeWithTag("recovery-review").captureToImage().asSkiaBitmap()
            Image.makeFromBitmap(bitmap).use { image ->
                image.encodeToData()!!.use { data ->
                    File(directory, "recovery-failure-$language-${reason.name}.png").writeBytes(data.bytes)
                }
            }
        }
        manager.restoreFailure = null
        onNodeWithText(restore).performClick()
        onNodeWithText(confirm).performClick()
        waitUntil { controller.state.sync.recovery.failureMessage == null }
        onNodeWithText(messages.getValue(reason)).assertDoesNotExist()
    }

    private companion object {
        const val RECOVERY_CODE = "SOMEDAY-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"

        fun connectedSettings(): ClientSettings = ClientSettings(
            activeDeviceId = "device-123",
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "https://sync.example.test",
                selfHostedSession = SelfHostedSessionSummary(
                    loggedIn = true,
                    userEmail = "alice@example.test",
                    deviceId = "device-123",
                    deviceName = "Desktop device",
                    devicePlatform = "desktop",
                ),
            ),
        )

        fun signedInCredentialStore(): SelfHostedSessionCredentialStore =
            TestSelfHostedSessionCredentialStore(
                SelfHostedSessionCredentials(
                    endpoint = "https://sync.example.test",
                    userId = "user-123",
                    userEmail = "alice@example.test",
                    deviceId = "device-123",
                    deviceName = "Desktop device",
                    devicePlatform = "desktop",
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                ),
            )
    }
}

private class RecordingClipboard(
    private val setFailure: Throwable? = null,
) : Clipboard {
    private var entry: ClipEntry? = null

    var copyCalls: Int = 0
        private set

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        copyCalls += 1
        setFailure?.let { throw it }
        entry = clipEntry
    }

    override val nativeClipboard: Any = Unit

    fun copiedText(): String? {
        val transferable = entry?.nativeClipEntry as? Transferable ?: return null
        return transferable.getTransferData(DataFlavor.stringFlavor) as? String
    }
}

private class SuspendingClipboard : Clipboard {
    private val release = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var completedCopies: Int = 0
        private set

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        started.complete(Unit)
        try {
            release.await()
            completedCopies += 1
        } finally {
            cancelled.complete(Unit)
        }
    }

    override val nativeClipboard: Any = Unit
}

private class TestSelfHostedSessionCredentialStore(
    private var credentials: SelfHostedSessionCredentials?,
) : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? = credentials

    override fun save(credentials: SelfHostedSessionCredentials) {
        this.credentials = credentials
    }

    override fun clear() {
        credentials = null
    }
}

private class RecordingWorkspaceRecoveryManager(
    var recoveryState: WorkspaceRecoveryState = WorkspaceRecoveryState.NotConfigured,
    var restoreFailure: WorkspaceRecoveryReason? = null,
) : WorkspaceRecoveryManager {
    val confirmationCandidates = mutableListOf<String>()
    val recoveryCalls = mutableListOf<RecoveryCall>()
    var publishCalls: Int = 0
        private set

    override fun status(): WorkspaceRecoveryStatusResult = WorkspaceRecoveryStatusResult.ready(
        state = recoveryState,
        syncGate = when (recoveryState) {
            WorkspaceRecoveryState.NotConfigured,
            WorkspaceRecoveryState.Configured,
            -> WorkspaceRecoverySyncGate.Allowed
            WorkspaceRecoveryState.RecoveryAvailable -> WorkspaceRecoverySyncGate.RecoveryRequired
            WorkspaceRecoveryState.Unavailable -> WorkspaceRecoverySyncGate.VerificationUnavailable
        },
        reason = when (recoveryState) {
            WorkspaceRecoveryState.NotConfigured -> WorkspaceRecoveryReason.NotConfigured
            WorkspaceRecoveryState.Configured -> WorkspaceRecoveryReason.Configured
            WorkspaceRecoveryState.RecoveryAvailable -> WorkspaceRecoveryReason.RecoveryAvailable
            WorkspaceRecoveryState.Unavailable -> WorkspaceRecoveryReason.Unavailable
        },
    )

    override fun prepareCode(): WorkspaceRecoveryCodeResult = WorkspaceRecoveryCodeResult.prepared(
        WorkspaceRecoveryCode.fromUserVisibleValue(
            "SOMEDAY-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF",
        ),
    )

    override fun confirmPreparedCode(candidate: String): WorkspaceRecoveryCodeResult {
        confirmationCandidates += candidate
        return if (candidate == "SOMEDAY-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF") {
            publishCalls += 1
            WorkspaceRecoveryCodeResult.created()
        } else {
            WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.InvalidCode)
        }
    }

    override fun discardPreparedCode() = Unit

    override fun recover(
        recoveryCode: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceRecoveryRestoreResult {
        recoveryCalls += RecoveryCall(recoveryCode, replaceExistingWorkspace)
        restoreFailure?.let { return WorkspaceRecoveryRestoreResult.failure(it) }
        recoveryState = WorkspaceRecoveryState.Configured
        return WorkspaceRecoveryRestoreResult.recovered()
    }
}

private data class RecoveryCall(
    val recoveryCode: String,
    val replaceExistingWorkspace: Boolean,
)
