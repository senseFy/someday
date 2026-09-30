package saien.someday.app.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.data.account.AccountResetIntent
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.workspace.WorkspaceProductSnapshot
import saien.someday.sync.selfhosted.SELF_HOSTED_ACCOUNT_INCARNATION_HEADER
import saien.someday.sync.selfhosted.SELF_HOSTED_ERROR_CODE_HEADER
import saien.someday.ui.settings.SettingsUiController

class DesktopResetAccountHintRecoveryTest {
    @Test
    fun productionGraphAndControllerRecoverMissingAccountHintWithoutRegisteringOrDiscarding() = runBlocking {
        val taskDirectory = Files.createTempDirectory("someday-reset-missing-hint-")
        val previousUserHome = System.getProperty("user.home")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val failDiscoveryOnce = AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val discoveryUnavailable = path == "/account/data-state" && failDiscoveryOnce.getAndSet(false)
            val response = when (path) {
                "/auth/login" -> """{"accessToken":"control-access","refreshToken":"control-refresh","expiresInSeconds":900,"user":{"id":"$USER","email":"$EMAIL"}}"""
                "/account/data-state" -> if (discoveryUnavailable) """{"error":"account_busy"}"""
                    else """{"protocolVersion":1,"accountIncarnation":"$NEXT","resetAvailable":false,"resetUnavailableReason":"retired_media_pending"}"""
                else -> null
            }
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add(SELF_HOSTED_ACCOUNT_INCARNATION_HEADER, NEXT)
            if (discoveryUnavailable) exchange.responseHeaders.add(SELF_HOSTED_ERROR_CODE_HEADER, "account_busy")
            val bytes = (response ?: "{}").toByteArray()
            exchange.sendResponseHeaders(if (response == null) 500 else if (discoveryUnavailable) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            System.setProperty("user.home", taskDirectory.toString())
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val store = DesktopSelfHostedSessionCredentialStore(taskDirectory.resolve("credentials"), keychain = null)
            val original: WorkspaceProductSnapshot
            createDesktopClientRepositories(store).use { repositories ->
                original = repositories.workspaceProductAccess.capture()
                repositories.notesRepository.createNotebook("Kept local copy")
            }
            val databasePath = taskDirectory.resolve(".someday/someday.db")
            createSomedayJdbcDriver("jdbc:sqlite:$databasePath").use { driver ->
                val database = SomedayDatabase(driver)
                val states = SqlDelightAccountStateRepository(database)
                states.persistIntent(AccountResetIntent(OPERATION, endpoint, USER, INITIAL_ACCOUNT_INCARNATION,
                    original.workspaceId, original.writerDeviceId))
                states.recordCommittedReceipt(OPERATION, NEXT, 1_790_689_934_336)
                val settings = SqlDelightClientSettingsRepository(SqlDelightLocalDataRepository(database, original.writerDeviceId))
                val current = settings.load()
                settings.saveLocalSnapshot(current.copy(syncConfiguration = current.syncConfiguration.copy(lastError = "setup:AccountResetRequired")))
            }
            createDesktopClientRepositories(store).use { repositories ->
                val controller = SettingsUiController(
                    initialSettings = repositories.settingsRepository.load(),
                    loadSettings = repositories.settingsRepository::load,
                    persistSettings = repositories.settingsRepository::save,
                    selfHostedSessionCredentialStore = repositories.selfHostedSessionCredentialStore,
                    accountDataResetManager = repositories.accountDataResetManager,
                    workspaceProductAccess = repositories.workspaceProductAccess,
                    backgroundDispatcher = Dispatchers.Unconfined,
                )
                controller.refresh()
                val pending = assertNotNull(controller.state.sync.accountReset.snapshot)
                assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, pending.phase)
                assertTrue(pending.productReadOnly)
                assertEquals(OPERATION, pending.operationId)
                val review = assertNotNull(pending.review)
                assertTrue(review.accountEmail.isBlank())
                assertFalse(controller.refreshAccountReset())
                assertEquals(AccountDataResetIssue.SignInRequired, controller.state.sync.accountReset.snapshot?.issue)
                assertNotNull(controller.state.sync.accountReset.snapshot?.review)
                assertTrue(requests.isEmpty(), "Missing credentials must keep a usable login capture without HTTP.")

                // Login succeeds before status discovery fails. Its verified display hint
                // must survive even though the overall action did not finish successfully.
                assertFalse(controller.reauthenticateAccountReset(review, "test-password", EMAIL))

                assertEquals(AccountDataResetIssue.Busy, controller.state.sync.accountReset.snapshot?.issue)
                assertEquals(EMAIL, controller.state.settings.syncConfiguration.selfHostedSession.userEmail)
                assertEquals(endpoint, controller.state.settings.syncConfiguration.selfHostedEndpoint)
                assertFalse(controller.state.settings.syncConfiguration.selfHostedSession.loggedIn)
                assertEquals(SyncMode.Off, controller.state.settings.syncConfiguration.mode)
                assertNull(store.load(), "Account-control credentials are never saved as a content session.")
                assertTrue(controller.selectLanguage(AppLanguage.English))
                assertEquals(EMAIL, repositories.settingsRepository.load().syncConfiguration.selfHostedSession.userEmail)
                assertTrue(controller.refreshAccountReset())
                assertTrue(controller.state.sync.accountReset.snapshot?.canReplaceLocal == true)
                val verified = assertNotNull(controller.state.sync.accountReset.snapshot?.review)
                assertFalse(controller.replaceAccountWorkspace(verified, AccountDataReplacementMode.Fresh, false))
                assertEquals(original, repositories.workspaceProductAccess.capture())
                assertEquals("Kept local copy", repositories.notesRepository.listNotebooks().single().title)
                assertEquals(listOf("/auth/login", "/account/data-state", "/account/data-state"), requests.toList())
                controller.cancelAccountResetReview()
            }
            createDesktopClientRepositories(store).use { reopened ->
                val pending = reopened.accountDataResetManager.load()
                assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, pending.phase)
                assertEquals(EMAIL, assertNotNull(pending.review).accountEmail)
                assertTrue(pending.requiresAuthentication)
                assertFalse(pending.canReplaceLocal)
                assertNull(pending.issue)
                assertTrue(pending.productReadOnly)
                assertEquals(original, reopened.workspaceProductAccess.capture())
            }
        } finally {
            server.stop(0)
            System.setProperty("user.home", previousUserHome)
            taskDirectory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val USER = "10000000-0000-4000-8000-000000000001"
        const val OPERATION = "20000000-0000-4000-8000-000000000002"
        const val NEXT = "30000000-0000-4000-8000-000000000003"
        const val EMAIL = "owner@example.test"
    }
}
