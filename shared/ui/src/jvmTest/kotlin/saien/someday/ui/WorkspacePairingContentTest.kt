@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.getString
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.UnavailableWorkspacePairingScanner
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.common_cancel
import saien.someday.ui.resources.common_join
import saien.someday.ui.resources.pairing_enter_token
import saien.someday.ui.resources.pairing_replace_dialog_confirm
import saien.someday.ui.resources.pairing_replace_dialog_title
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacePairingContentTest {
    @Test
    fun replacementOnlyJoinsOnceAfterConfirmationAndNeverOnCancelOrDismiss() = runComposeUiTest {
        val enterTokenLabel = getString(Res.string.pairing_enter_token)
        val joinLabel = getString(Res.string.common_join)
        val cancelLabel = getString(Res.string.common_cancel)
        val replacementTitle = getString(Res.string.pairing_replace_dialog_title)
        val replacementConfirmLabel = getString(Res.string.pairing_replace_dialog_confirm)
        var joinCalls = 0
        var replacementAuthorized: Boolean? = null
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, replaceExistingWorkspace ->
                joinCalls += 1
                replacementAuthorized = replaceExistingWorkspace
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            manualSyncRunner = {
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        setContent {
            MaterialTheme {
                WorkspacePairingContent(
                    state = controller.state,
                    controller = controller,
                    readinessSubtitle = "Ready",
                    scanner = UnavailableWorkspacePairingScanner,
                    actionScope = rememberCoroutineScope(),
                )
            }
        }

        onNodeWithText(enterTokenLabel).performClick()
        onNode(hasSetTextAction()).performTextInput(PAIRING_TOKEN)

        openReplacementDialog(joinLabel, replacementTitle)
        assertEquals(0, joinCalls)
        onNodeWithText(cancelLabel).performClick()
        onNodeWithText(replacementTitle).assertDoesNotExist()
        assertEquals(0, joinCalls)

        openReplacementDialog(joinLabel, replacementTitle)
        onNode(
            isRoot() and hasAnyDescendant(hasText(replacementTitle)),
            useUnmergedTree = true,
        ).performTouchInput { click(Offset(1f, 1f)) }
        onNodeWithText(replacementTitle).assertDoesNotExist()
        assertEquals(0, joinCalls)

        openReplacementDialog(joinLabel, replacementTitle)
        onNodeWithText(replacementConfirmLabel).performTouchInput { doubleClick() }
        waitUntil { joinCalls == 1 }
        onNodeWithText(replacementTitle).assertDoesNotExist()
        assertEquals(1, joinCalls)
        assertEquals(true, replacementAuthorized)
    }

    private fun ComposeUiTest.openReplacementDialog(
        joinLabel: String,
        replacementTitle: String,
    ) {
        onNodeWithText(joinLabel).performClick()
        onNodeWithText(replacementTitle).assertExists()
    }

    private companion object {
        const val PAIRING_TOKEN = "000G40R 40M30E2 09185GR 38E1WRJ"

        fun connectedSettings() = ClientSettings(
            activeDeviceId = "device-123",
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "https://sync.example.test",
                selfHostedSession = SelfHostedSessionSummary(
                    loggedIn = true,
                    userEmail = "alice@example.test",
                    deviceId = "device-123",
                    deviceName = "Test device",
                    devicePlatform = "desktop",
                ),
            ),
        )
    }
}
