@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package saien.someday.app.ios

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.darwin.NSObject
import saien.someday.ui.settings.LocalExportResult
import saien.someday.ui.settings.LocalExportRunner
import saien.someday.ui.settings.SettingsExportSummary

internal class IosLocalExportRunner(
    private val rootControllerProvider: () -> UIViewController?,
    private val prepareExport: () -> IosPreparedLocalExport,
) : LocalExportRunner {
    // UIKit delegates are weak; keep both objects alive through the suspended action.
    private var activeDelegate: ExportDocumentPickerDelegate? = null
    private var activePicker: UIDocumentPickerViewController? = null
    private var running = false

    override suspend fun export(): LocalExportResult = withContext(Dispatchers.Main) {
        if (running) return@withContext LocalExportResult.Failed
        running = true
        var prepared: IosPreparedLocalExport? = null
        try {
            withContext(Dispatchers.Default) {
                // Assign before crossing dispatchers so cancellation cannot orphan the file.
                prepared = prepareExport()
            }
            val document = checkNotNull(prepared)
            val presenter = awaitPresentationHost() ?: return@withContext LocalExportResult.Failed
            presentDocumentPicker(presenter, document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalExportResult.Failed
        } finally {
            var dismissed = true
            withContext(NonCancellable) {
                try {
                    activePicker?.let { picker ->
                        picker.setDelegate(null)
                        picker.presentationController?.delegate = null
                        dismissed = awaitPickerDismissal(picker)
                    }
                } finally {
                    activePicker = null
                    activeDelegate = null
                    withContext(Dispatchers.Default) { runCatching { prepared?.discard() } }
                    running = false
                }
            }
            if (!dismissed) {
                // Do not let the caller reopen a Compose dialog over a stuck native
                // transition. Cleanup and mutex release still complete on cancellation.
                throw CancellationException("The export window has not finished closing.")
            }
        }
    }

    private suspend fun awaitPickerDismissal(picker: UIDocumentPickerViewController): Boolean =
        withTimeoutOrNull(2_000) {
            while (picker.isBeingPresented()) delay(16)
            if (picker.presentingViewController != null && !picker.isBeingDismissed()) {
                val completion = CompletableDeferred<Unit>()
                picker.dismissViewControllerAnimated(false) { completion.complete(Unit) }
                completion.await()
            }
            // didPick / cancel can arrive while UIKit's own dismissal is still
            // animating. That path has no completion callback owned by this runner.
            while (picker.presentingViewController != null || picker.isBeingDismissed() || picker.view.window != null) {
                delay(16)
            }
            true
        } ?: false

    private suspend fun awaitPresentationHost(): UIViewController? {
        // The shared reset UI closes its Compose dialog before starting export. Its
        // UIKit dismissal may finish on a later frame; never present over another modal.
        repeat(60) {
            val root = rootControllerProvider() ?: return null
            if (root.view.window?.isKeyWindow() == true && root.presentedViewController == null &&
                !root.isBeingPresented() && !root.isBeingDismissed()
            ) {
                return root
            }
            delay(16)
        }
        return null
    }

    private suspend fun presentDocumentPicker(
        presenter: UIViewController,
        document: IosPreparedLocalExport,
    ): LocalExportResult = suspendCancellableCoroutine { continuation ->
        val delegate = ExportDocumentPickerDelegate(document.summary) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(NSURL.fileURLWithPath(document.filePath)),
            asCopy = true,
        )
        // A form sheet works on iPhone and iPad without an unanchored popover.
        picker.modalPresentationStyle = UIModalPresentationFormSheet
        picker.setDelegate(delegate)
        picker.presentationController?.delegate = delegate
        activeDelegate = delegate
        activePicker = picker
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}

private class ExportDocumentPickerDelegate(
    private val summary: SettingsExportSummary,
    private val onComplete: (LocalExportResult) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    private var completed = false

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val destination = (didPickDocumentsAtURLs.singleOrNull() as? NSURL)?.lastPathComponent
            ?.takeIf { it.isNotBlank() }
        // In export-as-copy mode Apple returns the newly copied destination, not
        // the app's temporary source. Only this callback establishes saved status.
        complete(
            if (destination == null) LocalExportResult.Failed else LocalExportResult.Saved(
                summary.copy(destinationLabel = destination),
            ),
        )
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        complete(LocalExportResult.Cancelled)
    }

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        complete(LocalExportResult.Cancelled)
    }

    private fun complete(result: LocalExportResult) {
        if (completed) return
        completed = true
        onComplete(result)
    }
}

internal class IosPreparedLocalExport(
    val filePath: String,
    val summary: SettingsExportSummary,
    private val directory: String,
) {
    fun discard() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}

/** Called on the export IO dispatcher. No persistent Documents files are exposed. */
internal fun prepareIosLocalExport(fileName: String, json: String, summary: SettingsExportSummary): IosPreparedLocalExport {
    require(fileName.isNotBlank() && '/' !in fileName && fileName !in setOf(".", ".."))
    val directory = "${NSTemporaryDirectory().trimEnd('/')}/someday-export-${NSUUID().UUIDString}"
    val files = NSFileManager.defaultManager
    try {
        check(files.createDirectoryAtPath(directory, withIntermediateDirectories = false, attributes = null, error = null))
        val filePath = "$directory/$fileName"
        check(NSString.create(string = json).writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null))
        return IosPreparedLocalExport(filePath, summary.copy(destinationLabel = null), directory)
    } catch (failure: Exception) {
        files.removeItemAtPath(directory, error = null)
        throw failure
    }
}
