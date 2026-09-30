@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import saien.someday.domain.settings.AccountDataResetIssue
import org.jetbrains.skia.Image
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.ui.i18n.AppLocaleEnvironment
import saien.someday.ui.settings.LocalExportResult
import saien.someday.ui.settings.LocalExportRunner
import saien.someday.ui.settings.SettingsUiController

class AccountResetDesktopRenderTest {
    @Test
    fun narrowResetFormRequiresBothPasswordAndExactPhraseAndCanFocusEachField() = runDesktopComposeUiTest(width = 320, height = 640) {
        render("ready")
        onNodeWithText("Reset account data").performScrollTo().performClick()
        onNodeWithText("Reset server data now").assertIsNotEnabled()
        capture("desktop-narrow-warning")
        val password = onNode(hasSetTextAction() and hasText("Account password"))
        password.performScrollTo().performClick().assertIsFocused().performTextInput("renderpassword")
        onNodeWithText("Reset server data now").assertIsNotEnabled()
        val phrase = onNode(hasSetTextAction() and hasText("Type the confirmation phrase exactly"))
        phrase.performScrollTo().performClick().assertIsFocused().performTextInput("RESET ALL ACCOUNT")
        onNodeWithText("Reset server data now").assertIsNotEnabled()
        phrase.performTextReplacement("RESET ALL ACCOUNT DATA")
        onNodeWithText("Reset server data now").assertIsEnabled()
        phrase.performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
        password.assertIsFocused()
        // IME Next must traverse the dialog's focus owner, not the outer account screen.
        phrase.performScrollTo().performClick().assertIsFocused().performImeAction()
        password.assertIsFocused()
        capture("desktop-narrow-confirmation")
        onNodeWithText("Back").performClick()
        onNodeWithText("Close").performClick()
        onNodeWithText("Reset account data").assertExists()
    }

    @Test
    fun persistentOutcomesAndLocalFailureRenderWithoutPermittingAnAutomaticReset() {
        for (scenario in listOf("unknown", "offline", "committed", "reset-required", "local-failure")) {
            runDesktopComposeUiTest(width = 320, height = 640) {
                render(scenario)
                onNodeWithText("Review local copy").assertExists()
                onNodeWithText("Reset account data").assertDoesNotExist()
                onNodeWithText("Review reset").assertDoesNotExist()
                capture("desktop-$scenario")
            }
        }
    }

    @Test
    fun ordinaryAndUnavailableStatesStayCompactAndDoNotImplyAPendingReset() {
        for (scenario in listOf("ready", "unavailable")) {
            runDesktopComposeUiTest(width = 320, height = 640) {
                render(scenario)
                onNodeWithText("Reset account data").assertExists()
                onNodeWithText("Export local data").assertDoesNotExist()
                onNodeWithText("Refresh account status").assertDoesNotExist()
                onNodeWithText("Reset outcome unconfirmed").assertDoesNotExist()
                onNodeWithText("reset-review@example.test").assertDoesNotExist()
                onNodeWithText("http://127.0.0.1:18080").assertDoesNotExist()
                capture("desktop-$scenario-summary")
                if (scenario == "unavailable") {
                    onNodeWithText("Reset account data").performClick()
                    onNodeWithText("Refresh account status").assertIsEnabled()
                    onNodeWithText("Reset server data now").assertDoesNotExist()
                    onNodeWithText("Retry the same request").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun openingDetailsDiscoversResetAvailabilityOnceWithoutStartingAnAccountOperation() = runDesktopComposeUiTest(width = 320, height = 640) {
        val manager = DesktopRenderAccountResetManager("undiscovered")
        render("undiscovered", manager = manager)
        assertEquals(0, manager.refreshCalls)
        onNodeWithText("Reset account data").performClick()
        onNodeWithText("Review reset").assertIsEnabled()
        assertEquals(1, manager.refreshCalls)
        onNodeWithText("Review reset").performClick()
        onNodeWithText("Reset server data now").assertIsNotEnabled()
        assertEquals(1, manager.refreshCalls)
        assertEquals(0, manager.submitCalls)
        assertEquals(0, manager.reconcileCalls)
        assertEquals(0, manager.authenticateCalls)
    }

    @Test
    fun pendingRequestKeepsOutcomeCheckAvailableAndLocalReplacementNeedsSeparateConsent() {
        runDesktopComposeUiTest(width = 320, height = 640) {
            val manager = DesktopRenderAccountResetManager("unknown")
            render("unknown", manager = manager)
            onNodeWithText("Reset outcome unconfirmed").assertExists()
            onNodeWithText("Review next steps").performScrollTo().performClick()
            onNodeWithText("Check outcome").assertIsEnabled()
            onNodeWithText("Retry the same request").performScrollTo().assertIsEnabled()
            onNodeWithText("Reset server data now").assertDoesNotExist()
            assertEquals(1, manager.refreshCalls)
            assertEquals(0, manager.reconcileCalls)
            assertEquals(0, manager.submitCalls)
            assertEquals(0, manager.authenticateCalls)
            capture("desktop-unknown-details")
        }
        runDesktopComposeUiTest(width = 320, height = 640) {
            render("committed")
            onNodeWithText("Review next steps").performScrollTo().performClick()
            onNodeWithText("Start with an empty workspace").performScrollTo().performClick()
            onNodeWithText("Confirm local replacement").assertIsNotEnabled()
            onNodeWithText("I agree to discard this device’s current workspace without merging.")
                .performScrollTo().performClick()
            onNodeWithText("Confirm local replacement").assertIsEnabled()
            capture("desktop-local-replacement-consent")
        }
    }

    @Test
    fun largeChineseTextKeepsTheDestructiveReviewReachable() = runDesktopComposeUiTest(width = 320, height = 640) {
        render("ready", fontScale = 1.5f, language = AppLanguage.Chinese)
        // The exact localized button is sourced from the production composable.
        onNodeWithText("重置账号数据").performScrollTo().performClick()
        capture("desktop-large-zh-warning")
    }

    @Test
    fun remoteResetStaysOpenAndContinuesToIndependentLocalConsent() = runDesktopComposeUiTest(width = 320, height = 640) {
        val retentionMessage = "Data retention or cleanup verification from the previous reset is not complete. This only prevents another reset; you can still finish setting up this device. Some retention policies may prevent further resets indefinitely."
        val manager = DesktopRenderAccountResetManager("ready").apply { commitOnSubmit = true; nextReplacementIssue = null }
        render("ready", manager = manager)
        onNodeWithText("Reset account data").performClick()
        onNode(hasSetTextAction() and hasText("Type the confirmation phrase exactly")).performScrollTo().performTextInput("RESET ALL ACCOUNT DATA")
        onNode(hasSetTextAction() and hasText("Account password")).performScrollTo().performTextInput("password")
        onNodeWithText("Reset server data now").performClick()
        onNodeWithText("Start with an empty workspace").performScrollTo().assertIsEnabled()
        onNodeWithText("Sign in for account recovery").assertDoesNotExist()
        onNodeWithText("Check outcome").assertDoesNotExist()
        onNodeWithText(retentionMessage).assertDoesNotExist()
        capture("desktop-committed-next-step")
        assertEquals(1, manager.submitCalls)
        assertEquals(0, manager.replacementCalls)
        onNodeWithText("Start with an empty workspace").performClick()
        onNodeWithText("Confirm local replacement").assertIsNotEnabled()
        onNodeWithText("I agree to discard this device’s current workspace without merging.").performScrollTo().performClick()
        onNodeWithText("Confirm local replacement").performClick()
        waitForIdle()
        assertEquals(1, manager.replacementCalls)
        onNodeWithText("Close").assertExists()
        onNodeWithText("Confirm local replacement").assertDoesNotExist()
        onNodeWithText(retentionMessage).performScrollTo().assertExists()
        onNodeWithText("Review reset").assertDoesNotExist()
        onNodeWithText("Close").performClick()
        onNodeWithText("This device’s workspace has been replaced.").assertExists()
        onNodeWithText("Reset account data").performScrollTo().performClick()
        onNodeWithText(retentionMessage).performScrollTo().assertExists()
        onNodeWithText("Review reset").assertDoesNotExist()
        capture("desktop-local-ready-next-reset-unavailable")
    }

    @Test
    fun controlLoginFailureStaysEditableAndSuccessImmediatelyShowsLocalChoices() = runDesktopComposeUiTest(width = 320, height = 640) {
        val manager = DesktopRenderAccountResetManager("committed-auth-required").apply { nextAuthenticationIssue = AccountDataResetIssue.AccountMismatch }
        render("committed-auth-required", manager = manager)
        onNodeWithText("Review next steps").performClick()
        onNodeWithText("Start with an empty workspace").assertDoesNotExist()
        onNodeWithText("Sign in for account recovery").performClick()
        onNode(hasSetTextAction() and hasText("Email")).performTextReplacement("wrong@example.test")
        onNode(hasSetTextAction() and hasText("Account password")).performScrollTo().performTextInput("password")
        onNode(hasText("Sign in for account recovery") and androidx.compose.ui.test.hasClickAction()).performClick()
        onNodeWithText("This email belongs to a different account. Enter the email for the account being reset.").assertExists()
        capture("desktop-control-login-account-mismatch")
        onNode(hasSetTextAction() and hasText("Email")).performScrollTo().performTextReplacement("owner@example.test")
        manager.nextAuthenticationIssue = null
        onNode(hasSetTextAction() and hasText("Account password")).performScrollTo().performTextInput("password")
        onNode(hasText("Sign in for account recovery") and androidx.compose.ui.test.hasClickAction()).performClick()
        onNodeWithText("Start with an empty workspace").performScrollTo().assertIsEnabled()
        onNodeWithText("Sign in for account recovery").assertDoesNotExist()
        capture("desktop-authenticated-local-choices")
        assertEquals("owner@example.test", manager.lastAuthenticationEmail)
        assertEquals(2, manager.authenticateCalls)
        onNodeWithText("Close").performClick()
        onNodeWithText("Review next steps").performScrollTo().performClick()
        onNodeWithText("Sign in for account recovery").assertIsEnabled()
        onNodeWithText("Start with an empty workspace").assertDoesNotExist()
    }

    @Test
    fun invalidRecoveryCodeKeepsTheFormAndRequiresNewConsentForEveryAttempt() = runDesktopComposeUiTest(width = 320, height = 640) {
        val manager = DesktopRenderAccountResetManager("committed").apply { nextReplacementIssue = AccountDataResetIssue.InvalidSecret }
        render("committed", manager = manager)
        onNodeWithText("Review next steps").performClick()
        onNodeWithText("Use a recovery code").performScrollTo().performClick()
        onNode(hasSetTextAction() and hasText("Recovery code created after the reset")).performScrollTo().performTextInput("invalid-code")
        onNodeWithText("I agree to discard this device’s current workspace without merging.").performScrollTo().performClick()
        onNodeWithText("Confirm local replacement").performClick()
        onNodeWithText("The pairing token or recovery code is invalid. Check it and try again.").assertExists()
        onNode(hasSetTextAction() and hasText("Recovery code created after the reset")).assertExists()
        onNodeWithText("Confirm local replacement").assertIsNotEnabled()
        capture("desktop-invalid-recovery-retry")
        assertEquals(1, manager.replacementCalls)
        onNodeWithText("Back").performClick()
        onNodeWithText("Start with an empty workspace").performScrollTo().assertIsEnabled()
    }

    @Test
    fun largeChineseLocalChoicesAndIndependentConsentRemainReachable() = runDesktopComposeUiTest(width = 320, height = 640) {
        render("committed", fontScale = 1.5f, language = AppLanguage.Chinese)
        onNodeWithText("查看处理方式").performScrollTo().performClick()
        onNodeWithText("从空工作区开始").performScrollTo().assertIsEnabled()
        capture("desktop-large-zh-local-choices")
        onNodeWithText("从空工作区开始").performClick()
        onNodeWithText("确认替换本机工作区").assertIsNotEnabled()
        onNodeWithText("我同意丢弃此设备的当前工作区，且不合并数据。").performScrollTo().performClick()
        onNodeWithText("确认替换本机工作区").assertIsEnabled()
        capture("desktop-large-zh-local-consent")
    }

    @Test
    fun repeatedExportClickCannotReopenTheDialogWhileTheSavePickerIsPending() = runDesktopComposeUiTest(width = 320, height = 640) {
        val completion = CompletableDeferred<LocalExportResult>()
        var exportCalls = 0
        render("committed", localExportRunner = LocalExportRunner {
            exportCalls++
            completion.await()
        })
        onNodeWithText("Review next steps").performScrollTo().performClick()
        val exportClick = onNodeWithText("Export local data").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Invoke the same rendered callback twice before Compose removes the dialog.
        runOnIdle { exportClick(); exportClick() }
        waitForIdle()
        assertEquals(1, exportCalls)
        onNodeWithText("Close").assertDoesNotExist()
        onNodeWithText("Export local data").assertDoesNotExist()
        onNodeWithText("Review local copy").assertIsNotEnabled()
        onNodeWithText("Review next steps").assertIsNotEnabled().performClick()
        onNodeWithText("Close").assertDoesNotExist()
        runOnIdle { completion.complete(LocalExportResult.Cancelled) }
        onNodeWithText("Close").assertExists()
        onNodeWithText("Start with an empty workspace").performScrollTo().assertIsEnabled()
        assertEquals(1, exportCalls)
    }

    private fun ComposeUiTest.render(
        scenario: String,
        fontScale: Float = 1f,
        language: AppLanguage = AppLanguage.English,
        manager: DesktopRenderAccountResetManager = DesktopRenderAccountResetManager(scenario),
        localExportRunner: LocalExportRunner? = null,
    ) {
        val settings = ClientSettings(appLanguage = language)
        val controller = SettingsUiController(initialSettings = settings, loadSettings = { settings },
            accountDataResetManager = manager, localExportRunner = localExportRunner, backgroundDispatcher = Dispatchers.Unconfined)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppLocaleEnvironment(language) {
                    SomedayTheme {
                        Column(Modifier.width(320.dp).height(640.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            AccountDataResetContent(controller.state, controller, rememberCoroutineScope())
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(320f, onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.width)
    }

    private fun ComposeUiTest.capture(name: String) {
        val directory = System.getenv("SOMEDAY_REVIEW_ARTIFACTS")?.let(::File) ?: return
        directory.mkdirs()
        val roots = onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asSkiaBitmap()
            Image.makeFromBitmap(bitmap).use { image -> image.encodeToData()!!.use { data ->
                File(directory, "$name-$index.png").writeBytes(data.bytes)
            } }
        }
    }
}
