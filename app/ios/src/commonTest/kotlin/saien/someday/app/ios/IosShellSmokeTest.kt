package saien.someday.app.ios

import kotlin.test.Test
import kotlin.test.assertTrue

class IosShellSmokeTest {
    @Test
    fun iosShellRoutesIntoSharedUiStartup() {
        val log = IosShellEntrypoint.startupLog()

        println(log)
        assertTrue(log.contains("platform=ios"))
        assertTrue(log.contains("shared-ui=shared:ui"))
        assertTrue(log.contains("startup=SomedayApp"))
        assertTrue(log.contains("material=Material3"))
        assertTrue(log.contains("tabs=Notes|Memories|Settings"))
        assertTrue(log.contains("markdown-source=plain-text"))
        assertTrue(log.contains("preview=toggle"))
        assertTrue(log.contains("toolbar=heading|bold|italic|list|quote|code-block|link|image"))
        assertTrue(log.contains("images=app-owned-assets+local-preview+user-requested-materialization"))
        assertTrue(log.contains("memories=calendar-counts|month-navigation|selected-day|prior-year"))
        assertTrue(log.contains("location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk"))
        assertTrue(log.contains("platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence"))
        assertTrue(log.contains("search=local-title-body-active-only"))
        assertTrue(log.contains("settings-sections=sync-mode-account|self-hosted-device-management|device-pairing"))
        assertTrue(log.contains("workspace-pairing=one-use-invitation|qr-or-token|redacted-logs"))
        assertTrue(log.contains("export=notes-notebooks|dag-only|excludes-media-bytes|asset-references-may-be-unresolved"))
    }
}
