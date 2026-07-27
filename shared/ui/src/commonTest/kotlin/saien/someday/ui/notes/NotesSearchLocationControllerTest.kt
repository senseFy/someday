@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.notes

import saien.someday.domain.location.CapturedLocation
import saien.someday.domain.location.LocationCaptureResult
import saien.someday.domain.location.StaticLocationCaptureAdapter
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesSearchLocationControllerTest {
    @Test
    fun searchMatchesTitlesAndBodiesAcrossActiveNotesOnly() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val travel = controller.createNotebook("Travel")
        val titleMatch = repository.seedNote(
            notebookId = diary.id,
            title = "Kyoto breakfast",
            markdownBody = "Coffee before a walk",
            createdDate = LocalDate(2026, 5, 22),
        )
        val bodyMatch = repository.seedNote(
            notebookId = travel.id,
            title = "Train ride",
            markdownBody = "Body mentions the hidden bamboo grove",
            createdDate = LocalDate(2026, 5, 23),
        )
        val deleted = repository.seedNote(
            notebookId = diary.id,
            title = "Kyoto deleted",
            markdownBody = "Should be removed from normal search",
            createdDate = LocalDate(2026, 5, 24),
        )
        repository.deleteNote(deleted.id)
        controller.refresh()

        controller.updateSearchQuery("kyoto")
        assertEquals(listOf(titleMatch.id), controller.state.searchResults.map { it.id })

        controller.updateSearchQuery("BAMBOO")
        assertEquals(listOf(bodyMatch.id), controller.state.searchResults.map { it.id })

        controller.updateSearchQuery("deleted")
        assertTrue(controller.state.searchResults.none { it.id == deleted.id })
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("Found 0 notes"))
    }

    @Test
    fun grantedDeniedAndManualLocationFlowsKeepEditorUsableWithoutMapSdk() = runBlocking {
        val repository = InMemoryNotesRepository()
        val deniedController = NotesUiController(
            repository = repository,
            locationCaptureAdapter = StaticLocationCaptureAdapter(
                LocationCaptureResult.Denied("Permission denied by test"),
            ),
        )
        val diary = deniedController.createNotebook("Diary")

        deniedController.openNewNote(diary.id)
        deniedController.updateDraft(
            title = "Manual place survives",
            markdownBody = "No coordinates are needed",
            createdDateText = "2026-05-22",
            placeText = "Kyoto Station",
        )

        assertFalse(deniedController.captureCurrentLocation())
        assertTrue(deniedController.state.feedbackMessage.orEmpty().contains("still type a place"))
        assertTrue(deniedController.saveEditor())

        val manual = repository.listNotes(diary.id).single()
        assertEquals("Kyoto Station", repository.getNoteDetails(manual.id)?.location?.placeText)
        assertEquals(null, repository.getNoteDetails(manual.id)?.location?.latitude)

        val grantedController = NotesUiController(
            repository = repository,
            locationCaptureAdapter = StaticLocationCaptureAdapter(
                LocationCaptureResult.Captured(
                    CapturedLocation(
                        latitude = 35.681236,
                        longitude = 139.767125,
                        accuracyMeters = 8.5,
                        altitudeMeters = 44.0,
                        capturedAt = Instant.parse("2026-05-22T12:20:00Z"),
                        providerLabel = "test-provider",
                    ),
                ),
            ),
        )

        grantedController.selectNotebook(diary.id)
        grantedController.openNewNote(diary.id)
        grantedController.updateDraft(
            title = "Captured place",
            markdownBody = "Coordinates came from a granted adapter",
            createdDateText = "2026-05-23",
        )

        assertTrue(grantedController.captureCurrentLocation())
        assertEquals("35.681236", grantedController.state.editor?.latitudeText)
        assertEquals("139.767125", grantedController.state.editor?.longitudeText)
        assertEquals("8.5", grantedController.state.editor?.accuracyMetersText)
        assertEquals("44.0", grantedController.state.editor?.altitudeMetersText)
        assertEquals("2026-05-22T12:20:00Z", grantedController.state.editor?.capturedAtText)
        assertTrue(grantedController.saveEditor())

        val captured = repository.listNotes(diary.id).first { it.title == "Captured place" }
        val location = repository.getNoteDetails(captured.id)?.location
        assertEquals(35.681236, location?.latitude)
        assertEquals(139.767125, location?.longitude)
        assertEquals(8.5, location?.accuracyMeters)
        assertEquals(44.0, location?.altitudeMeters)
        assertEquals(Instant.parse("2026-05-22T12:20:00Z"), location?.capturedAt)
    }
}
