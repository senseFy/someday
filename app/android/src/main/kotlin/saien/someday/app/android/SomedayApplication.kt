package saien.someday.app.android

import android.app.Application

class SomedayApplication : Application() {
    val clientRepositories: AndroidClientRepositories by lazy {
        createAndroidClientRepositories(this)
    }
}
