package saien.someday.ui.i18n

import java.util.Locale

private val systemDefaultLocale: Locale = Locale.getDefault()

/**
 * Desktop/JVM: compose resources read [Locale.getDefault] for language selection.
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
