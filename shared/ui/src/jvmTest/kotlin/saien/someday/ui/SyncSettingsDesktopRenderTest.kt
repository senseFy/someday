@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Image
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.ui.i18n.AppLocaleEnvironment
import saien.someday.ui.i18n.applyAppLanguageTag
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.SyncIssueReason
import saien.someday.ui.settings.SyncIssueUi
import saien.someday.ui.settings.UnavailableWorkspacePairingScanner

class SyncSettingsDesktopRenderTest {
    @AfterTest
    fun restoreSystemLanguage() = applyAppLanguageTag(null)

    @Test
    fun narrowChinesePageKeepsHealthyAccountAndResetCompactAndAccountActionsInMenu() {
        for (fontScale in listOf(1f, 1.5f)) {
            runDesktopComposeUiTest(width = 320, height = 640) {
                render(language = AppLanguage.Chinese, fontScale = fontScale)
                onNodeWithText("已登录").assertIsDisplayed()
                onAllNodes(hasText("owner@example.test")).assertCountEquals(1)
                onAllNodes(hasText("https://sync.example.test")).assertCountEquals(1)
                onAllNodes(hasText("立即同步")).assertCountEquals(1)
                onNodeWithText("立即同步").assertIsEnabled()
                onNodeWithText("重置账号数据").assertExists()
                onNodeWithText("重新登录").assertDoesNotExist()
                onNodeWithText("切换服务器或账户").assertDoesNotExist()
                onNodeWithText("确认切换").assertDoesNotExist()
                onNodeWithText("导出本机数据").assertDoesNotExist()
                capture("sync-page-healthy-zh-$fontScale")

                onNodeWithContentDescription("更多").performClick()
                onNodeWithText("重新登录").assertIsDisplayed()
                onNodeWithText("切换服务器或账户").assertIsDisplayed()
                onNodeWithText("重新登录").performClick()
                onAllNodes(hasSetTextAction()).assertCountEquals(1)
                onNodeWithText("取消").performScrollTo().performClick()
                onNodeWithText("立即同步").assertIsEnabled()
                onAllNodes(hasSetTextAction()).assertCountEquals(0)
            }
        }
    }

    @Test
    fun reauthenticationIsExplicitAndDoesNotClaimTheAccountIsSignedIn() = runDesktopComposeUiTest(width = 320, height = 640) {
        render(language = AppLanguage.English, issue = SyncIssueUi(SyncIssueReason.SignInRequired))
        onNodeWithText("Sign-in required").assertIsDisplayed()
        onNodeWithText("Signed in").assertDoesNotExist()
        onNodeWithText("Sync now").assertDoesNotExist()
        onNodeWithText("Sign in again").assertIsDisplayed().assertIsEnabled()
        capture("sync-page-reauthentication-en")

        onNodeWithText("Sign in again").performClick()
        onAllNodes(hasSetTextAction()).assertCountEquals(2)
        onNodeWithText("Sign in again").performScrollTo().assertIsEnabled()
        onNodeWithText("Cancel").performScrollTo().performClick()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onNodeWithText("Sign-in required").assertExists()
        onNodeWithText("Sign in again").assertIsEnabled()
    }

    @Test
    fun pendingResetWithMissingCredentialsKeepsOrdinaryLoginHiddenAndControlLoginReachable() {
        for (scenario in listOf("committed", "unknown", "reset-required")) {
            runDesktopComposeUiTest(width = 320, height = 640) {
                render(language = AppLanguage.Chinese, issue = SyncIssueUi(SyncIssueReason.SetupFailed),
                    resetScenario = "$scenario-auth-required", signedIn = false)
                onNodeWithText("本机副本待处理").assertIsDisplayed()
                onNodeWithText("处理本机副本").assertExists()
                onNodeWithText("重置账号数据").assertDoesNotExist()
                onNodeWithText("登录未完成。请检查账号信息和网络连接后重试。").assertDoesNotExist()
                onNodeWithText("重新登录").assertDoesNotExist()
                onNodeWithText("立即同步").assertDoesNotExist()
                onAllNodes(hasSetTextAction()).assertCountEquals(0)
                onNodeWithText("查看处理方式").performScrollTo().performClick()
                onNodeWithText("登录以继续处理账号").performScrollTo().performClick()
                onAllNodes(hasSetTextAction()).assertCountEquals(2)
                onNodeWithText("账号密码").assertExists()
                onNodeWithText("确认替换本机工作区").assertDoesNotExist()
            }
        }
    }

    @Test
    fun missingAccountHintOffersEmailAndPasswordOnlyInControlLogin() = runDesktopComposeUiTest(width = 320, height = 640) {
        val manager = DesktopRenderAccountResetManager("committed-missing-email")
        render(language = AppLanguage.English, signedIn = false, missingAccountHint = true,
            issue = SyncIssueUi(SyncIssueReason.AccountResetRequired), manager = manager)
        onNodeWithText("http://127.0.0.1:18080").assertIsDisplayed()
        onNodeWithText("Sign in again").assertDoesNotExist()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onNodeWithText("Review next steps").performScrollTo().performClick()
        onNodeWithText("Sign in for account recovery").performScrollTo().performClick()
        onAllNodes(hasSetTextAction()).assertCountEquals(2)
        val confirm = onNode(hasText("Sign in for account recovery") and hasClickAction())
        confirm.assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Account password")).performScrollTo().performTextInput("password-secret")
        confirm.assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Email")).performScrollTo().performTextInput("  owner@example.test  ")
        confirm.assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, manager.authenticateCalls)
        assertEquals("owner@example.test", manager.lastAuthenticationEmail)
        assertEquals("", manager.lastAuthenticationReview?.accountEmail)
        assertEquals(0, manager.submitCalls)
    }

    private fun ComposeUiTest.render(
        language: AppLanguage,
        fontScale: Float = 1f,
        issue: SyncIssueUi? = null,
        resetScenario: String = "ready",
        signedIn: Boolean = true,
        missingAccountHint: Boolean = false,
        manager: DesktopRenderAccountResetManager = DesktopRenderAccountResetManager(resetScenario),
    ) {
        applyAppLanguageTag(language.languageTag)
        val settings = ClientSettings(
            appLanguage = language,
            activeDeviceId = "00000000-0000-4000-8000-000000000001",
            syncConfiguration = SyncConfiguration(
                mode = if (signedIn) SyncMode.SelfHosted else SyncMode.Off,
                selfHostedEndpoint = if (missingAccountHint) null else "https://sync.example.test",
                selfHostedSession = SelfHostedSessionSummary(
                    loggedIn = signedIn,
                    userEmail = if (missingAccountHint) null else "owner@example.test",
                    deviceId = "00000000-0000-4000-8000-000000000001",
                    deviceName = "iOS 设备",
                    devicePlatform = "ios",
                ),
            ),
        )
        val controller = SettingsUiController(
            initialSettings = settings,
            loadSettings = { settings },
            accountDataResetManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppLocaleEnvironment(language) {
                    SomedayTheme(theme = ClientTheme.Light) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            val state = controller.state
                            SyncSettingsContent(
                                state = state.copy(sync = state.sync.copy(issue = issue)),
                                controller = controller,
                                workspacePairingScanner = UnavailableWorkspacePairingScanner,
                                actionScope = rememberCoroutineScope(),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val directory = System.getenv("SOMEDAY_REVIEW_ARTIFACTS")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = onNode(isRoot()).captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            image.encodeToData()!!.use { data ->
                File(directory, "$name.png").writeBytes(data.bytes)
            }
        }
    }
}
