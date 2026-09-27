@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package saien.someday.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.getString
import org.jetbrains.skia.Image
import saien.someday.domain.settings.ClientSettings
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.*
import saien.someday.ui.settings.DayOneImportRunner
import saien.someday.ui.settings.SettingsImportOutcome
import saien.someday.ui.settings.SettingsImportSummary
import saien.someday.ui.settings.SettingsUiController

class DayOneImportContentTest {
    @Test
    fun importedImagesDoNotShowTheOldNoImageRestorationWarning() = showSummary(
        SettingsImportSummary(SettingsImportOutcome.Completed, notesCreated = 3, photosImported = 3), "complete",
    )

    @Test
    fun missingImagesKeepTheTextImportAndExplainPlaceholders() = showSummary(
        SettingsImportSummary(
            SettingsImportOutcome.Completed, journalsImported = 4, notebooksCreated = 4, notesCreated = 935,
            richTextConverted = 448, photosImported = 1, photosMissing = 128, photosUnresolved = 2,
            photosRejected = 3, otherMedia = 4, unsupportedItems = 233,
        ), "missing",
    )

    @Test
    fun englishPhotoCountsStaySeparatedAtNarrowWidth() = showSummary(
        SettingsImportSummary(
            SettingsImportOutcome.Completed, notesCreated = 4,
            photosImported = 1, photosMissing = 128, photosUnresolved = 2, photosRejected = 3,
        ), "english", Locale.ENGLISH,
    )

    @Test
    fun failuresAndCancellationNeverShowFakeZeroStatisticsOrConversionTotals() {
        for (outcome in listOf(SettingsImportOutcome.Partial, SettingsImportOutcome.Failed, SettingsImportOutcome.Cancelled)) {
            showSummary(
                SettingsImportSummary(outcome, notebooksCreated = 1, notesCreated = 1, photosImported = 20),
                outcome.name.lowercase(),
            )
        }
    }

    private fun showSummary(summary: SettingsImportSummary, name: String, locale: Locale = Locale.SIMPLIFIED_CHINESE) {
        val original = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            renderSummary(summary, name)
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun renderSummary(summary: SettingsImportSummary, name: String) = runComposeUiTest {
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            dayOneImportRunner = DayOneImportRunner { it(summary) },
        )
        controller.startDayOneImport()
        val boundary = getString(Res.string.import_media_boundary)
        setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(360.dp).padding(16.dp)) {
                        ImportSettingsContent(controller.state, controller)
                    }
                }
            }
        }
        val status = getString(when (summary.outcome) {
            SettingsImportOutcome.Completed -> Res.string.import_day_one_completed
            SettingsImportOutcome.Partial -> Res.string.import_day_one_partial
            SettingsImportOutcome.Failed -> Res.string.import_day_one_failed
            SettingsImportOutcome.Cancelled -> Res.string.import_day_one_cancelled
            SettingsImportOutcome.Unavailable -> Res.string.import_day_one_unavailable
        })
        onNodeWithText(status).assertExists()
        val notes = onNodeWithText(getString(Res.string.import_notes_summary, summary.notesImported, summary.notesSkipped))
        if (summary.hasPersistenceResult) notes.assertExists() else notes.assertDoesNotExist()
        onNodeWithText(getString(Res.string.import_notes_summary, 0, 0)).assertDoesNotExist()
        if (summary.outcome == SettingsImportOutcome.Completed) {
            for ((resource, count) in listOf(
                Res.string.import_photos_imported to summary.photosImported,
                Res.string.import_photos_missing to summary.photosMissing,
                Res.string.import_photos_unresolved to summary.photosUnresolved,
                Res.string.import_photos_rejected to summary.photosRejected,
            )) {
                val label = getString(resource)
                val labelBounds = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                val valueBounds = onNode(hasText(count.toString()) and SemanticsMatcher("in the $label row") {
                    it.boundsInRoot.top == labelBounds.top
                }).fetchSemanticsNode().boundsInRoot
                assertTrue(valueBounds.left > labelBounds.right, "$label needs a gap before its count")
            }
            if (summary.photosMissing + summary.photosUnresolved + summary.photosRejected + summary.otherMedia > 0) {
                onNodeWithText(boundary).assertExists()
            } else {
                onNodeWithText(boundary).assertDoesNotExist()
            }
        } else {
            onNodeWithText(getString(Res.string.import_photos_imported)).assertDoesNotExist()
        }
        System.getenv("SOMEDAY_REVIEW_ARTIFACTS")?.let { directory ->
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            Image.makeFromBitmap(bitmap).use { image ->
                image.encodeToData()!!.use { data ->
                    File(directory, "day-one-fixed-$name.png").writeBytes(data.bytes)
                }
            }
        }
    }
}
