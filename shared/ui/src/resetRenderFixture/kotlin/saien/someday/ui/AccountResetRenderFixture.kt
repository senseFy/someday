package saien.someday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.ui.i18n.AppLocaleEnvironment
import saien.someday.ui.i18n.rememberSettingsUiStrings
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.OnThisDayNotificationStrings

/** Opt-in render source set only. No filesystem, credentials, network, or product mutations. */
@Composable
fun AccountResetRenderFixture(scenario: String = "unknown", fontScale: Float = 1f, languageTag: String = "en") {
    val language = when (languageTag) { "zh" -> AppLanguage.Chinese; "ja" -> AppLanguage.Japanese; "ko" -> AppLanguage.Korean; else -> AppLanguage.English }
    val settings = remember(language) { ClientSettings(appLanguage = language) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        AppLocaleEnvironment(language) {
            SomedayTheme {
                val strings = rememberSettingsUiStrings()
                val controller = remember(scenario, language) {
                    SettingsUiController(loadSettings = { settings }, initialSettings = settings,
                        accountDataResetManager = RenderOnlyAccountResetManager(scenario), uiStrings = strings)
                }
                // Match the production shell: iOS resources may settle after the
                // first composition applies the app's language preference.
                SideEffect { controller.updateLocalizedStrings(strings, OnThisDayNotificationStrings()) }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Isolated reset render fixture")
                    AccountDataResetContent(controller.state, controller, scope)
                }
            }
        }
    }
}
