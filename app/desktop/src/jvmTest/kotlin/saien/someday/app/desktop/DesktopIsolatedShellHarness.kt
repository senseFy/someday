package saien.someday.app.desktop

import java.nio.file.Files
import java.nio.file.Path

/** Test-only executable: the full production shell with a disposable home and no macOS Keychain. */
object DesktopIsolatedShellHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        check(System.getProperty("someday.isolatedShell") == "true")
        check(home.fileName.toString().startsWith("someday-isolated-shell-"))
        check(Files.isRegularFile(home.resolve(".test-owned-profile")))
        println("Isolated Desktop shell profile: $home")
        val scenario = System.getProperty("someday.isolatedResetScenario")
        launchDesktopApp(accountDataResetManagerOverride = scenario?.let(::SyntheticResetManager)) {
            createDesktopClientRepositories(
                DesktopSelfHostedSessionCredentialStore(directory = home.resolve(".someday/credentials"), keychain = null),
            )
        }
    }
}
