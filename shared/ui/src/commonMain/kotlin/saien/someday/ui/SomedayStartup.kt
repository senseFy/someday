package saien.someday.ui

import saien.someday.domain.client.ClientShellSemantics
import saien.someday.domain.client.clientShellSemanticsFor
import saien.someday.domain.location.CapturedLocation
import saien.someday.domain.location.LocationCaptureResult
import saien.someday.domain.location.StaticLocationCaptureAdapter
import saien.someday.ui.notes.InMemoryNotesRepository
import saien.someday.ui.notes.NotesUiController
import saien.someday.ui.notes.markdownEditorCapabilityLog
import kotlinx.coroutines.runBlocking

fun sharedUiStartupSemantics(platform: String): ClientShellSemantics = clientShellSemanticsFor(platform)

fun sharedUiStartupLog(platform: String): String =
    "${sharedUiStartupSemantics(platform).smokeLog()} ${markdownEditorCapabilityLog()} " +
        "memories=calendar-counts|month-navigation|selected-day|prior-year " +
        "location=system-coordinates|manual-place|permission-denied-usable|no-map-sdk " +
        "platform-smoke=workspace-setup|unlock|create-note|markdown-preview|denied-location|restart-persistence " +
        "search=local-title-body-active-only"

fun grantedLocationSmokeLog(
    platform: String,
    capturedLocation: CapturedLocation,
): String = runBlocking {
    val repository = InMemoryNotesRepository()
    val controller = NotesUiController(
        repository = repository,
        locationCaptureAdapter = StaticLocationCaptureAdapter(
            LocationCaptureResult.Captured(capturedLocation),
        ),
    )
    val notebook = controller.createNotebook("Location smoke")

    controller.openNewNote(notebook.id)
    controller.updateDraft(
        title = "Granted location",
        markdownBody = "Saved through the shared editor without any map SDK.",
        createdDateText = "2026-05-22",
    )
    val captured = controller.captureCurrentLocation()
    val saved = controller.saveEditor()
    val savedNote = repository.listNotes(notebook.id).singleOrNull()
    val savedLocation = savedNote?.let { repository.getNoteDetails(it.id)?.location }

    "platform=$platform location=${if (captured) "granted" else "not-granted"} " +
        "lat=${savedLocation?.latitude} lon=${savedLocation?.longitude} saved=$saved no-map-sdk"
}
