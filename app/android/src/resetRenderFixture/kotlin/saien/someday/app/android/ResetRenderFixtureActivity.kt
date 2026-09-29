package saien.someday.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import saien.someday.ui.AccountResetRenderFixture

/** Included only by the explicit render-fixture build property. */
class ResetRenderFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "ready"
        val fontScale = intent.getStringExtra("fontScale")?.toFloatOrNull() ?: 1f
        val language = intent.getStringExtra("language") ?: "en"
        setContent { AccountResetRenderFixture(scenario = scenario, fontScale = fontScale, languageTag = language) }
    }
}
