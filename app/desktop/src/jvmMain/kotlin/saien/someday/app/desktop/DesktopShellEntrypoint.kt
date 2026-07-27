package saien.someday.app.desktop

import saien.someday.ui.sharedUiStartupLog

object DesktopShellEntrypoint {
    const val platformName: String = "desktop"

    fun startupLog(): String = sharedUiStartupLog(platformName)
}
