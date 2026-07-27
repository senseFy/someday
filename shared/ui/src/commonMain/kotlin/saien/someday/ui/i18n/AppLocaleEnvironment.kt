package saien.someday.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import saien.someday.domain.settings.AppLanguage

/**
 * Applies [language] for compose multiplatform string resources.
 *
 * Compose resources resolve strings from the platform locale. This wrapper:
 * 1. Installs the preferred language via [applyAppLanguageTag] (platform actual).
 * 2. Forces a recomposition with [key] so `stringResource` reloads for the new locale.
 *
 * [AppLanguage.System] clears the override and follows the OS language again.
 */
@Composable
fun AppLocaleEnvironment(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    SideEffect {
        applyAppLanguageTag(language.languageTag)
    }
    key(language) {
        content()
    }
}

/**
 * Installs or clears an app-level language override used by compose resources.
 *
 * @param languageTag BCP-47 language subtag (`en`, `zh`, `ko`, `ja`), or null for system default.
 */
expect fun applyAppLanguageTag(languageTag: String?)
