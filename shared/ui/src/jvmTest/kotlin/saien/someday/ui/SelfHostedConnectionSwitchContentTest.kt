@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.getString
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedConnectionSwitchResult
import saien.someday.domain.settings.SelfHostedConnectionSwitcher
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.resetBoundWorkspaceForConnectionSwitch
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.common_cancel
import saien.someday.ui.resources.connection_switch_action
import saien.someday.ui.resources.connection_switch_dialog_confirm
import saien.someday.ui.resources.connection_switch_dialog_title
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.UnavailableWorkspacePairingScanner
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfHostedConnectionSwitchContentTest {
    @Test
    fun switchRequiresConfirmationAndReopensTheEditableConnectionForm() = runComposeUiTest {
        val actionLabel = getString(Res.string.connection_switch_action)
        val dialogTitle = getString(Res.string.connection_switch_dialog_title)
        val confirmLabel = getString(Res.string.connection_switch_dialog_confirm)
        val cancelLabel = getString(Res.string.common_cancel)
        var stored = connectedSettings()
        var switchCalls = 0
        val controller = SettingsUiController(
            loadSettings = { stored },
            initialSettings = stored,
            selfHostedConnectionSwitcher = SelfHostedConnectionSwitcher {
                switchCalls += 1
                stored = stored.resetBoundWorkspaceForConnectionSwitch()
                SelfHostedConnectionSwitchResult.switched(workspaceReplaced = true)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        setContent {
            MaterialTheme {
                SyncSettingsContent(
                    state = controller.state,
                    controller = controller,
                    workspacePairingScanner = UnavailableWorkspacePairingScanner,
                    actionScope = rememberCoroutineScope(),
                )
            }
        }

        onNodeWithText(actionLabel).assertExists()
        onNodeWithText(confirmLabel).performClick()
        onNodeWithText(dialogTitle).assertExists()
        assertEquals(0, switchCalls)
        onNodeWithText(cancelLabel).performClick()
        onNodeWithText(dialogTitle).assertDoesNotExist()
        assertEquals(0, switchCalls)

        onNodeWithText(confirmLabel).performClick()
        onAllNodes(hasText(confirmLabel))[1].performClick()
        waitUntil { switchCalls == 1 }

        onNodeWithText(dialogTitle).assertDoesNotExist()
        onAllNodes(hasSetTextAction()).assertCountEquals(3)
        assertEquals(1, switchCalls)
    }

    private fun connectedSettings(): ClientSettings =
        ClientSettings(
            activeDeviceId = "00000000-0000-4000-8000-000000000001",
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "https://sync.example.test",
                selfHostedSession = SelfHostedSessionSummary(
                    loggedIn = true,
                    userEmail = "owner@example.test",
                    deviceId = "00000000-0000-4000-8000-000000000001",
                    deviceName = "Desktop device",
                    devicePlatform = "desktop",
                ),
            ),
        )
}
