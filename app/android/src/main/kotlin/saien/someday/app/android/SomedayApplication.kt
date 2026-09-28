package saien.someday.app.android

import android.app.Application

class SomedayApplication : Application() {
    // Foreground bootstrap and background receivers access this off the main thread.
    // Publish only after device identity, workspace keys, and the initial DAG are ready;
    // all subsequent access shares the same workspace-lifecycle coordinator.
    val clientRepositories: AndroidClientRepositories by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createAndroidClientRepositories(this)
    }
}
