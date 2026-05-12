# `takePicture` Function — Preservation Rule & Recovery Guide

## ⚠️ Rule: Do NOT Remove `takePicture` During Package Upgrades

The `takePicture` feature is a **custom addition** that lives on top of the upstream `mobile_scanner` package.
It is **not** present in the upstream package and **will be wiped out** every time the upstream source files are
overwritten (e.g. `pub upgrade`, vendoring a new version, running any script that copies upstream files into
this repo).

**Before completing any `mobile_scanner` upgrade, verify that every file listed in the checklist below
still contains its `takePicture` additions. If any are missing, follow the recovery steps in this document.**

---

## Affected Files Checklist

| # | File | What must be present |
|---|------|----------------------|
| 1 | `android/…/MobileScannerExceptions.kt` | 4 exception classes |
| 2 | `android/…/MobileScannerCallbacks.kt` | 3 type aliases |
| 3 | `android/…/objects/MobileScannerErrorCodes.kt` | 8 constants (4 pairs) |
| 4 | `android/…/MobileScanner.kt` | imports, `imageCapture` field, setup in `start()`, `takePicture()` method |
| 5 | `android/…/MobileScannerHandler.kt` | `"takePicture"` route + private method |
| 6 | `darwin/…/MobileScannerErrorCodes.swift` | 7 static constants |
| 7 | `darwin/…/MobileScannerPlugin.swift` | properties, `start()` setup, switch case, 2 private methods, `releaseCamera()` cleanup, delegate extension |
| 8 | `lib/src/mobile_scanner_platform_interface.dart` | abstract `takePicture()` method |
| 9 | `lib/src/method_channel/mobile_scanner_method_channel.dart` | `@override takePicture()` implementation |
| 10 | `lib/src/mobile_scanner_controller.dart` | public `takePicture()` method |

---

## Recovery Steps (Bottom-Up Order)

### Step 1 — `android/src/main/kotlin/dev/steenbakker/mobile_scanner/MobileScannerExceptions.kt`

Append these four classes at the **end** of the file (after `ZoomNotInRange`):

```kotlin
class ImageCaptureNotAvailable : Exception()
class ImageCaptureReadError : Exception()
class ImageCaptureProcessError : Exception()
class ImageCaptureFailed : Exception()
```

---

### Step 2 — `android/src/main/kotlin/dev/steenbakker/mobile_scanner/MobileScannerCallbacks.kt`

Append these three type aliases at the **end** of the file (after `MobileScannerStartedCallback`):

```kotlin
typealias MobileScannerExceptionCallback = (exception: Exception) -> Unit
typealias TakePictureSuccessCallback = (image: ByteArray) -> Unit
typealias TakePictureErrorCallback = (exception: Exception) -> Unit
```

---

### Step 3 — `android/src/main/kotlin/dev/steenbakker/mobile_scanner/objects/MobileScannerErrorCodes.kt`

Add these eight constants **inside the `companion object`**, after `INVALID_FOCUS_POINT_MESSAGE`:

```kotlin
const val IMAGE_CAPTURE_NOT_AVAILABLE_ERROR = "MOBILE_SCANNER_IMAGE_CAPTURE_NOT_AVAILABLE_ERROR"
const val IMAGE_CAPTURE_NOT_AVAILABLE_ERROR_MESSAGE = "Camera not started or image capture not available."
const val IMAGE_CAPTURE_READ_ERROR = "MOBILE_SCANNER_IMAGE_CAPTURE_READ_ERROR"
const val IMAGE_CAPTURE_READ_ERROR_MESSAGE = "Failed to read captured image."
const val IMAGE_CAPTURE_PROCESS_ERROR = "MOBILE_SCANNER_IMAGE_CAPTURE_PROCESS_ERROR"
const val IMAGE_CAPTURE_PROCESS_ERROR_MESSAGE = "Failed to process captured image."
const val IMAGE_CAPTURE_FAILED_ERROR = "MOBILE_SCANNER_IMAGE_CAPTURE_FAILED_ERROR"
const val IMAGE_CAPTURE_FAILED_ERROR_MESSAGE = "Image capture failed."
```

---

### Step 4 — `android/src/main/kotlin/dev/steenbakker/mobile_scanner/MobileScanner.kt`

#### 4a. Add imports (with the other `androidx.camera.core` imports)

```kotlin
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
```

#### 4b. Add instance variable (alongside `imageAnalysis`)

```kotlin
private var imageCapture: ImageCapture? = null
```

#### 4c. In `start()`, after building and assigning `imageAnalysis`, add the `ImageCapture` use case and include it in `bindToLifecycle`

```kotlin
// After: imageAnalysis = analysis

val imageCaptureBuilder = ImageCapture.Builder()
imageCaptureBuilder.setResolutionSelector(selectorBuilder.build())
imageCapture = imageCaptureBuilder.build()

// In bindToLifecycle — add imageCapture as last argument:
camera = cameraProvider?.bindToLifecycle(
    activity as LifecycleOwner,
    cameraPosition,
    preview,
    analysis,
    imageCapture       // ← add this
)
```

#### 4d. Add `takePicture()` method (before `dispose()`)

```kotlin
fun takePicture(
    onSuccess: TakePictureSuccessCallback,
    onError: TakePictureErrorCallback
) {
    if (imageCapture == null) {
        onError(ImageCaptureNotAvailable())
        return
    }

    val tempFile = activity.applicationContext.cacheDir.resolve(
        "mobile_scanner_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
    )
    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

    imageCapture?.takePicture(
        outputFileOptions,
        ContextCompat.getMainExecutor(activity),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val bytes = tempFile.readBytes()
                    onSuccess(bytes)
                } catch (e: Exception) {
                    onError(ImageCaptureProcessError())
                } finally {
                    tempFile.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                tempFile.delete()
                onError(ImageCaptureFailed())
            }
        }
    )
}
```

---

### Step 5 — `android/src/main/kotlin/dev/steenbakker/mobile_scanner/MobileScannerHandler.kt`

#### 5a. In `onMethodCall`, add this case before `else -> result.notImplemented()`

```kotlin
"takePicture" -> takePicture(result)
```

#### 5b. Add private method (at the end of the class, before the closing `}`)

```kotlin
private fun takePicture(result: MethodChannel.Result) {
    mobileScanner?.takePicture(
        onSuccess = {
            Handler(Looper.getMainLooper()).post {
                result.success(it)
            }
        },
        onError = {
            Handler(Looper.getMainLooper()).post {
                when (it) {
                    is ImageCaptureNotAvailable -> result.error(
                        MobileScannerErrorCodes.IMAGE_CAPTURE_NOT_AVAILABLE_ERROR,
                        MobileScannerErrorCodes.IMAGE_CAPTURE_NOT_AVAILABLE_ERROR_MESSAGE, null)
                    is ImageCaptureReadError -> result.error(
                        MobileScannerErrorCodes.IMAGE_CAPTURE_READ_ERROR,
                        MobileScannerErrorCodes.IMAGE_CAPTURE_READ_ERROR_MESSAGE, null)
                    is ImageCaptureProcessError -> result.error(
                        MobileScannerErrorCodes.IMAGE_CAPTURE_PROCESS_ERROR,
                        MobileScannerErrorCodes.IMAGE_CAPTURE_PROCESS_ERROR_MESSAGE, null)
                    is ImageCaptureFailed -> result.error(
                        MobileScannerErrorCodes.IMAGE_CAPTURE_FAILED_ERROR,
                        MobileScannerErrorCodes.IMAGE_CAPTURE_FAILED_ERROR_MESSAGE, null)
                    else -> result.error(
                        MobileScannerErrorCodes.GENERIC_ERROR,
                        MobileScannerErrorCodes.GENERIC_ERROR_MESSAGE, null)
                }
            }
        }
    )
}
```

---

### Step 6 — `darwin/mobile_scanner/Sources/mobile_scanner/MobileScannerErrorCodes.swift`

Add these seven constants **before** `UNSUPPORTED_OPERATION_ERROR`:

```swift
// Photo capture related errors
static let PHOTO_CAPTURE_ERROR = "MOBILE_SCANNER_PHOTO_CAPTURE_ERROR"
static let PHOTO_OUTPUT_NOT_AVAILABLE_ERROR_MESSAGE = "Photo output is not available."
static let PHOTO_CAPTURE_FAILED_ERROR_MESSAGE = "Failed to capture photo."
static let PHOTO_NO_IMAGE_DATA_ERROR_MESSAGE = "No image data received from photo capture."
static let VIDEO_BUFFER_NOT_AVAILABLE_ERROR_MESSAGE = "No video frame available for photo capture."
static let VIDEO_BUFFER_TO_IMAGE_CONVERSION_ERROR_MESSAGE = "Failed to create image from video buffer."
static let IMAGE_ENCODING_ERROR_MESSAGE = "Failed to encode image to JPEG."
```

---

### Step 7 — `darwin/mobile_scanner/Sources/mobile_scanner/MobileScannerPlugin.swift`

#### 7a. Add properties near the top of the class (after `latestBuffer`)

```swift
// Photo output for taking pictures
var photoOutput: AVCapturePhotoOutput?

// Completion handler for photo capture
var photoCompletionHandler: ((Data?, Error?) -> Void)?
```

#### 7b. In `start()`, after `captureSession!.addOutput(videoOutput)` and before setting video orientation

```swift
if #available(macOS 10.15, *) {
    let photoOut = AVCapturePhotoOutput()
    if captureSession!.canAddOutput(photoOut) {
        captureSession!.sessionPreset = .photo
        captureSession!.addOutput(photoOut)
        photoOutput = photoOut
    }
}
```

#### 7c. Add a named constant for JPEG quality near the other static vars

```swift
/// The JPEG compression quality used when encoding a picture taken with `takePicture`.
private static let jpegCompressionQuality: CGFloat = 0.8
```

#### 7d. In `handle(_ call:result:)`, add before `default:`

```swift
case "takePicture":
    takePicture(result)
```

#### 7e. Add two private methods (before `releaseCamera()`)

```swift
private func takePicture(_ result: @escaping FlutterResult) {
    guard self.device != nil else {
        result(FlutterError(
            code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
            message: MobileScannerErrorCodes.PHOTO_OUTPUT_NOT_AVAILABLE_ERROR_MESSAGE,
            details: nil))
        return
    }

    if #available(macOS 10.15, *) {
        guard let photoOutput = self.photoOutput as? AVCapturePhotoOutput else {
            result(FlutterError(
                code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
                message: MobileScannerErrorCodes.PHOTO_OUTPUT_NOT_AVAILABLE_ERROR_MESSAGE,
                details: nil))
            return
        }

        photoCompletionHandler = { [weak self] (data, error) in
            DispatchQueue.main.async {
                if let error = error {
                    result(FlutterError(
                        code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
                        message: "\(MobileScannerErrorCodes.PHOTO_CAPTURE_FAILED_ERROR_MESSAGE): \(error.localizedDescription)",
                        details: nil))
                } else if let data = data {
                    result(FlutterStandardTypedData(bytes: data))
                } else {
                    result(FlutterError(
                        code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
                        message: MobileScannerErrorCodes.PHOTO_NO_IMAGE_DATA_ERROR_MESSAGE,
                        details: nil))
                }
                self?.photoCompletionHandler = nil
            }
        }

        photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
    } else {
        // For older macOS versions, use the current frame from the video stream
        takePictureFromVideoBuffer(result)
    }
}

private func takePictureFromVideoBuffer(_ result: @escaping FlutterResult) {
    guard let latestBuffer = self.latestBuffer else {
        result(FlutterError(
            code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
            message: MobileScannerErrorCodes.VIDEO_BUFFER_NOT_AVAILABLE_ERROR_MESSAGE,
            details: nil))
        return
    }

    var cgImage: CGImage?
    let status = VTCreateCGImageFromCVPixelBuffer(latestBuffer, options: nil, imageOut: &cgImage)

    guard status == kCVReturnSuccess, let image = cgImage else {
        result(FlutterError(
            code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
            message: MobileScannerErrorCodes.VIDEO_BUFFER_TO_IMAGE_CONVERSION_ERROR_MESSAGE,
            details: nil))
        return
    }

    guard let imageData = image.jpegData(compressionQuality: MobileScannerPlugin.jpegCompressionQuality) else {
        result(FlutterError(
            code: MobileScannerErrorCodes.PHOTO_CAPTURE_ERROR,
            message: MobileScannerErrorCodes.IMAGE_ENCODING_ERROR_MESSAGE,
            details: nil))
        return
    }

    result(FlutterStandardTypedData(bytes: imageData))
}
```

#### 7f. In `releaseCamera()`, add cleanup before the closing `}`

```swift
latestBuffer = nil
self.photoOutput = nil
self.photoCompletionHandler = nil
```

#### 7g. Add `AVCapturePhotoCaptureDelegate` extension at the end of the file

Place this **after** the existing `#endif` for the `UIDeviceOrientation` extension (i.e., at the very end of the file):

```swift
// MARK: - AVCapturePhotoCaptureDelegate (macOS 10.15+)
@available(macOS 10.15, *)
extension MobileScannerPlugin: AVCapturePhotoCaptureDelegate {
    public func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error = error {
            photoCompletionHandler?(nil, error)
            return
        }

        guard let imageData = photo.fileDataRepresentation() else {
            photoCompletionHandler?(nil, NSError(
                domain: "MobileScannerPlugin",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Failed to get image data"]))
            return
        }

        photoCompletionHandler?(imageData, nil)
    }

    public func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        if let error = error {
            photoCompletionHandler?(nil, error)
        }
    }
}
```

---

### Step 8 — `lib/src/mobile_scanner_platform_interface.dart`

1. Add `import 'dart:typed_data';` at the top of the file.
2. Add the abstract method alongside the other abstract methods (e.g., after `updateScanWindow`):

```dart
/// Take a picture with the active camera and return the image bytes.
Future<Uint8List> takePicture() {
  throw UnimplementedError('takePicture() has not been implemented.');
}
```

---

### Step 9 — `lib/src/method_channel/mobile_scanner_method_channel.dart`

Add the override alongside the other `@override` methods (e.g., after `updateScanWindow`):

```dart
@override
Future<Uint8List> takePicture() async {
  final Uint8List? result = await methodChannel.invokeMethod<Uint8List>(
    'takePicture',
  );

  if (result == null) {
    throw const MobileScannerException(
      errorCode: MobileScannerErrorCode.genericError,
      errorDetails: MobileScannerErrorDetails(
        message: 'Failed to take picture: null result',
      ),
    );
  }

  return result;
}
```

---

### Step 10 — `lib/src/mobile_scanner_controller.dart`

Add the public method (e.g., after `buildCameraView()`):

```dart
/// Take a picture with the active camera and return the image bytes.
///
/// Throws a [MobileScannerException] if the controller is not initialized
/// or the camera is not running.
Future<Uint8List> takePicture() async {
  _throwIfNotInitialized();

  if (!value.isRunning) {
    throw const MobileScannerException(
      errorCode: MobileScannerErrorCode.genericError,
      errorDetails: MobileScannerErrorDetails(
        message: 'Camera is not running. Cannot take picture.',
      ),
    );
  }

  return MobileScannerPlatform.instance.takePicture();
}
```

> **Note:** `Uint8List` is available via `package:flutter/services.dart`, which is already imported in
> this file. No additional import is needed.

---

## Quick Verification

After applying any of the above, confirm these grep patterns find matches:

```bash
# Android exceptions
grep -r "ImageCaptureNotAvailable" mobile_scanner/android/

# Android error codes
grep -r "IMAGE_CAPTURE_NOT_AVAILABLE_ERROR" mobile_scanner/android/

# Android method handler
grep -r "takePicture" mobile_scanner/android/src/main/kotlin/dev/steenbakker/mobile_scanner/MobileScannerHandler.kt

# iOS/macOS error codes
grep -r "PHOTO_CAPTURE_ERROR" mobile_scanner/darwin/

# iOS/macOS plugin method + delegate
grep -r "takePicture\|AVCapturePhotoCaptureDelegate" mobile_scanner/darwin/

# Dart platform interface
grep -r "takePicture" mobile_scanner/lib/src/mobile_scanner_platform_interface.dart

# Dart method channel
grep -r "takePicture" mobile_scanner/lib/src/method_channel/mobile_scanner_method_channel.dart

# Dart controller
grep -r "takePicture" mobile_scanner/lib/src/mobile_scanner_controller.dart
```

All commands should return at least one match. If any return empty, apply the corresponding recovery step above.

---

## History

This feature was originally removed in commit `c7e6a10` (`chore: update mobile scanner to v7.2.0`)
when the upstream package source files were overwritten, silently dropping the custom additions.
It was re-added in the subsequent commit on this branch.
