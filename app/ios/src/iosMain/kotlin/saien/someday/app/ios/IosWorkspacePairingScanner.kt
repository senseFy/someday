@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package saien.someday.app.ios

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.preferredLanguages
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import saien.someday.ui.settings.WorkspacePairingScanner

internal class IosWorkspacePairingScanner(
    private val rootControllerProvider: () -> UIViewController?,
) : WorkspacePairingScanner {
    private var activeController: WorkspacePairingScannerViewController? = null

    override val available: Boolean
        get() {
            val camera = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
            return camera != null &&
                status != AVAuthorizationStatusDenied &&
                status != AVAuthorizationStatusRestricted
        }

    override fun scan(
        onResult: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        if (activeController != null) {
            onCancelled()
            return
        }
        runOnMain {
            when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
                AVAuthorizationStatusAuthorized -> presentScanner(onResult, onCancelled)
                AVAuthorizationStatusNotDetermined ->
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                        runOnMain {
                            if (granted) presentScanner(onResult, onCancelled) else onCancelled()
                        }
                    }
                else -> onCancelled()
            }
        }
    }

    private fun presentScanner(
        onResult: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val presenter = rootControllerProvider()?.topPresentedController()
        if (presenter == null || activeController != null) {
            onCancelled()
            return
        }
        val scanner = WorkspacePairingScannerViewController(
            onResult = { rawValue ->
                activeController = null
                onResult(rawValue)
            },
            onCancelled = {
                activeController = null
                onCancelled()
            },
        )
        activeController = scanner
        scanner.modalPresentationStyle = UIModalPresentationFullScreen
        presenter.presentViewController(scanner, animated = true, completion = null)
    }
}

private class WorkspacePairingScannerViewController(
    private val onResult: (String) -> Unit,
    private val onCancelled: () -> Unit,
) : UIViewController(nibName = null, bundle = null),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val session = AVCaptureSession()
    private val captureQueue = platform.darwin.dispatch_queue_create(
        "app.someday.workspace-pairing-camera",
        null,
    )
    private val cancelButton = UIButton.buttonWithType(UIButtonTypeSystem)
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var finished = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        if (!configureCaptureSession()) {
            runOnMain(::finishCancelled)
            return
        }

        val preview = AVCaptureVideoPreviewLayer(session = session).also {
            it.videoGravity = AVLayerVideoGravityResizeAspectFill
            it.frame = view.bounds
        }
        previewLayer = preview
        view.layer.addSublayer(preview)

        cancelButton.setTitle(localizedCancelTitle(), forState = UIControlStateNormal)
        cancelButton.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        cancelButton.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.58)
        cancelButton.layer.cornerRadius = 12.0
        cancelButton.addTarget(
            target = this,
            action = NSSelectorFromString("cancelPairingScan"),
            forControlEvents = UIControlEventTouchUpInside,
        )
        view.addSubview(cancelButton)

        dispatch_async(captureQueue) {
            session.startRunning()
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        cancelButton.setFrame(CGRectMake(
            x = 16.0,
            y = view.safeAreaInsets.useContents { top } + 8.0,
            width = 104.0,
            height = 44.0,
        ))
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        stopCapture()
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val rawValue = didOutputMetadataObjects
            .asSequence()
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
            ?: return
        finish(rawValue)
    }

    @ObjCAction
    fun cancelPairingScan() {
        finishCancelled()
    }

    private fun configureCaptureSession(): Boolean {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null) ?: return false
        if (!session.canAddInput(input)) return false
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) return false
        session.addOutput(output)
        output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
        if (!output.availableMetadataObjectTypes.contains(AVMetadataObjectTypeQRCode)) return false
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        return true
    }

    private fun finish(rawValue: String) {
        if (finished) return
        finished = true
        stopCapture()
        dismissViewControllerAnimated(true) {
            onResult(rawValue)
        }
    }

    private fun finishCancelled() {
        if (finished) return
        finished = true
        stopCapture()
        dismissViewControllerAnimated(true) {
            onCancelled()
        }
    }

    private fun stopCapture() {
        dispatch_async(captureQueue) {
            if (session.running) session.stopRunning()
        }
    }
}

private fun UIViewController.topPresentedController(): UIViewController =
    presentedViewController?.topPresentedController() ?: this

private fun runOnMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), block)
}

private fun localizedCancelTitle(): String {
    val language = (NSLocale.preferredLanguages.firstOrNull() as? String).orEmpty().lowercase()
    return when {
        language.startsWith("zh") -> "取消"
        language.startsWith("ja") -> "キャンセル"
        language.startsWith("ko") -> "취소"
        else -> "Cancel"
    }
}
