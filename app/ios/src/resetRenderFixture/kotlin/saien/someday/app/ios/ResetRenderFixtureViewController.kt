package saien.someday.app.ios

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import platform.UIKit.UIViewController
import saien.someday.ui.AccountResetRenderFixture

/** Included only by the explicit render-fixture build property. */
fun ResetRenderFixtureViewController(scenario: String, fontScale: Float, language: String): UIViewController =
    ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
        AccountResetRenderFixture(scenario = scenario, fontScale = fontScale, languageTag = language)
    }
