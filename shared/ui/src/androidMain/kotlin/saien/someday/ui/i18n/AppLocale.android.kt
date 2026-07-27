package saien.someday.ui.i18n

import java.util.Locale

private val systemDefaultLocale: Locale = Locale.getDefault()

/**
 * Android: update the JVM default locale used by compose multiplatform resources.
 *
 * Activity-level configuration is also updated by the host Activity when it
 * observes the stored language preference (see [saien.someday.app.android]).
 */
actual fun applyAppLanguageTag(languageTag: String?) {
    // Capture process-start default before the first override.
    val baseline = systemDefaultLocale
    if (languageTag == null) {
        Locale.setDefault(baseline)
    } else {
        Locale.setDefault(Locale.forLanguageTag(languageTag))
    }
}
