package saien.someday.app.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopShellSmokeTest {
    @Test
    fun desktopShellRoutesIntoSharedUiStartup() {
        val log = DesktopShellEntrypoint.startupLog()

        println(log)
        assertTrue(log.contains("platform=desktop"))
        assertTrue(log.contains("shared-ui=shared:ui"))
        assertTrue(log.contains("startup=SomedayApp"))
        assertTrue(log.contains("material=Material3"))
        assertTrue(log.contains("tabs=Notes|Memories|Settings"))
        assertTrue(log.contains("markdown-source=plain-text"))
        assertTrue(log.contains("preview=toggle"))
        assertTrue(log.contains("toolbar=heading|bold|italic|list|quote|code-block|link"))
        assertTrue(log.contains("attachments=absent"))
        assertTrue(log.contains("memories=calendar-counts|month-navigation|selected-day|prior-year"))
        assertTrue(log.contains("location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk"))
        assertTrue(log.contains("platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence"))
        assertTrue(log.contains("search=local-title-body-active-only"))
        assertTrue(log.contains("settings-sections=sync-mode-account|webdav-config|self-hosted-device-management"))
        assertTrue(log.contains("workspace-pairing=one-use-invitation|qr-or-token|redacted-logs"))
        assertTrue(log.contains("export=notes-notebooks|excludes-raw-keys-tokens-passwords-recovery-material"))
    }
}
