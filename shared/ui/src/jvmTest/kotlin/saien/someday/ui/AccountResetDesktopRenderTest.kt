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
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Image
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.ui.i18n.AppLocaleEnvironment
import saien.someday.ui.settings.SettingsUiController

class AccountResetDesktopRenderTest {
    @Test
    fun narrowResetFormRequiresBothPasswordAndExactPhraseAndCanFocusEachField() = runDesktopComposeUiTest(width = 320, height = 640) {
        render("ready")
        onNodeWithText("Review reset").performScrollTo().performClick()
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
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Review reset").assertExists()
    }

    @Test
    fun persistentOutcomesAndLocalFailureRenderWithoutPermittingAnAutomaticReset() {
        for (scenario in listOf("unknown", "offline", "committed", "reset-required", "local-failure")) {
            runDesktopComposeUiTest(width = 320, height = 640) {
                render(scenario)
                onNodeWithText("Reset account data").assertExists()
                onNodeWithText("Review reset").assertDoesNotExist()
                capture("desktop-$scenario")
            }
        }
    }

    @Test
    fun largeChineseTextKeepsTheDestructiveReviewReachable() = runDesktopComposeUiTest(width = 320, height = 640) {
        render("ready", fontScale = 1.5f, language = AppLanguage.Chinese)
        // The exact localized button is sourced from the production composable.
        onNodeWithText("查看重置说明").performScrollTo().performClick()
        capture("desktop-large-zh-warning")
    }

    private fun ComposeUiTest.render(scenario: String, fontScale: Float = 1f, language: AppLanguage = AppLanguage.English) {
        val settings = ClientSettings(appLanguage = language)
        val controller = SettingsUiController(initialSettings = settings, loadSettings = { settings },
            accountDataResetManager = DesktopRenderAccountResetManager(scenario), backgroundDispatcher = Dispatchers.Unconfined)
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
