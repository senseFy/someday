package saien.someday.app.android

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInstrumentedSmokeTest {
    @Test
    fun sharedUiStartupContractIsAvailableOnDevice() {
        val log = AndroidShellEntrypoint.startupLog()

        Log.i("SomedaySmoke", log)
        assertTrue(log.contains("platform=android"))
        assertTrue(log.contains("shared-ui=shared:ui"))
        assertTrue(log.contains("tabs=Notes|Memories|Settings"))
        assertTrue(log.contains("platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence"))
    }

    @Test
    fun mainActivityLaunchesSharedComposeShell() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                Log.i("SomedaySmoke", AndroidShellEntrypoint.startupLog())
                assertTrue(!activity.isFinishing)
            }
        } finally {
            scenario.close()
        }
    }
}
