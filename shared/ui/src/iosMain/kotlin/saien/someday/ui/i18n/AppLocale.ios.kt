package saien.someday.ui.i18n

import platform.Foundation.NSUserDefaults

/**
 * iOS: prefer [AppleLanguages] so compose multiplatform resources pick the override.
 * Clearing the key restores the system preferred languages list.
 */
actual fun applyAppLanguageTag(languageTag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (languageTag == null) {
        defaults.removeObjectForKey(AppleLanguagesKey)
    } else {
        defaults.setObject(listOf(languageTag), forKey = AppleLanguagesKey)
    }
    defaults.synchronize()
}

private const val AppleLanguagesKey: String = "AppleLanguages"
