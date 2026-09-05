package com.switchboard.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.view.Surface
import com.switchboard.app.net.CameraCodec
import com.switchboard.app.net.CameraFacing
import com.switchboard.app.net.CameraFrame
import com.switchboard.app.net.CameraQuality
import com.switchboard.app.net.CameraSettings
import com.switchboard.app.net.CameraState
import com.switchboard.app.net.CameraWhiteBalance
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import android.util.Size

/**
 * Streams this device's camera to the paired desktop over the existing
 * encrypted session, on two tracks at once.
 *
 * **H.264, from [VideoEncoder], is the picture the desktop shows.** The camera
 * writes into the hardware encoder's input surface, so a frame never touches
 * the CPU or the Java heap: that is what makes 60 fps reachable, and it costs
 * a few kilobytes a frame where a JPEG cost a few hundred.
 *
 * **JPEG, from the analyser below, is the compatibility track.** A virtual
 * camera and an MJPEG client for OBS or VLC can only take whole pictures, and
 * neither can apply a display transform — so this track alone still pays for
 * software compression and for baking rotation and mirroring into the pixels.
 * It is capped well below the video track, because nothing reading it wants
 * more, and a phone that has no hardware encoder falls back to it entirely.
 *
 * Capture is bound to whatever lifecycle [start] is given. [CameraController]
 * owns one for exactly the duration of a stream, so leaving the app does not
 * end it; [CameraService] keeps the process alive and says so in the shade.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraStreamer(private val context: Context) {

    /** Sends one frame: metadata plus the encoded bytes beside it. */
    fun interface FrameSink {
        fun send(meta: CameraFrame, payload: ByteArray)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val encoderExecutor = Executors.newSingleThreadExecutor()
    private val sequence = AtomicLong(0)
    private val videoSequence = AtomicLong(0)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null

    private val encoder = VideoEncoder(::onEncodedUnit)

    /**
     * The encoded buffer's size and how far it is from upright, as CameraX
     * reports them for the surface it handed the encoder. Written and read on
     * the encoder executor, so every access unit can carry them to the far end
     * to apply as a display transform.
     */
    @Volatile
    private var videoRotation = 0

    @Volatile
    private var videoSize = Size(0, 0)

    private var sink: FrameSink? = null
    private var onState: ((CameraState) -> Unit)? = null

    @Volatile
    private var settings = CameraSettings()

    @Volatile
    private var streaming = false

    /**
     * [System.nanoTime] the next frame is allowed at. The analyser is handed
     * frames at the sensor's rate; anything above the requested rate is closed
     * without encoding, which is where most of the CPU saving of a low fps
     * setting actually comes from.
     *
     * Nanoseconds, and advanced from the previous deadline rather than from
     * "now": millisecond intervals truncate (16 ms caps 60 fps at 62.5, but
     * 33 ms caps 30 at 30.3) and re-basing on arrival time adds the encode
     * latency to every interval, which is how a 60 fps request quietly
     * becomes 45.
     */
    @Volatile
    private var nextFrameAt = 0L

    // ---- Auto framing ----

    /** The sensor's full readout rectangle: the space face rectangles live in. */
    private var activeArray: Rect? = null

    /** The face detection mode this lens supports, or OFF if it has none. */
    private var faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF

    /** White balance presets this lens will honour, in this protocol's names. */
    private var availableWhiteBalance: List<String> = listOf(CameraWhiteBalance.AUTO)

    /**
     * Where auto framing currently has the crop, or null when it is not driving
     * one. Read on the capture thread and written from it; the apply path only
     * reads it, so a stale read costs one frame of lag and nothing else.
     */
    @Volatile
    private var framingCrop: Box? = null

    private var lastFramingAt = 0L

    val isStreaming: Boolean get() = streaming

    @SuppressLint("MissingPermission")
    fun start(
        owner: LifecycleOwner,
        settings: CameraSettings,
        sink: FrameSink,
        onState: (CameraState) -> Unit
    ) {
        this.settings = settings
        this.sink = sink
        this.onState = onState

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                this.provider = provider
                bind(owner, provider)
                streaming = true
                report()
            }.onFailure {
                Log.w(TAG, "camera start failed", it)
                streaming = false
                onState(CameraState(streaming = false, error = it.message ?: "Camera unavailable"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        streaming = false
        provider?.unbindAll()
        encoder.stop()
        camera = null
        analysis = null
        preview = null
        sequence.set(0)
        videoSequence.set(0)
        nextFrameAt = 0L
        onState?.invoke(CameraState(streaming = false, settings = settings))
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
        encoderExecutor.shutdown()
    }

    /**
     * Applies a settings change.
     *
     * Zoom, torch, focus and exposure are pushed straight into the running
     * session; facing, resolution and frame rate cannot be, because they are
     * fixed when the use case is bound. Those rebind, which is why the two
     * paths are distinguished rather than rebinding on every slider tick — a
     * rebind blanks the picture for a moment.
     */
    fun apply(owner: LifecycleOwner, updated: CameraSettings) {
        val previous = settings
        settings = updated

        val needsRebind = previous.facing != updated.facing ||
            previous.quality != updated.quality ||
            previous.fps != updated.fps

        if (needsRebind) {
            provider?.let { runCatching { bind(owner, it) } }
        } else {
            applyLive(updated)
        }
        report()
    }

    // ---- Binding ----

    private fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider) {
        provider.unbindAll()
        encoder.stop()
        framingCrop = null

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (settings.facing == CameraFacing.FRONT) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()

        // Preview is the video track: its surface is the encoder's input, so
        // the camera feeds the hardware encoder directly. Preview alongside
        // analysis is a combination every device is required to support.
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector())
            // Rotation is always reported relative to a target, so pinning the
            // target makes upright mean the phone's natural orientation rather
            // than whatever its own display is doing. A phone propped up as a
            // webcam should not flip the desktop's picture because the user
            // turned it in their hand.
            .setTargetRotation(Surface.ROTATION_0)

        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(Surface.ROTATION_0)
            // The analyser must never queue: a frame that arrives while the
            // last one is still encoding is stale by the time it would be
            // sent, and holding it only adds latency to everything after it.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Frame rate is a Camera2 concern; CameraX has no first-class control
        // for it on ImageAnalysis.
        //
        // It has to be one of the ranges the sensor advertises. Asking for a
        // (60,60) a device does not list gets the whole request ignored, and
        // auto-exposure then settles on whatever suits the light — indoors
        // that is (15,30), which is the real reason "60 fps" delivered 15.
        val characteristics = runCatching {
            Camera2CameraInfo.from(provider.getCameraInfo(selector))
        }.getOrNull()

        readCapabilities(characteristics)

        val advertised = runCatching {
            characteristics
                ?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper }
        }.getOrNull().orEmpty()

        pickFpsRange(advertised, settings.fps.coerceIn(5, 60))?.let { (lower, upper) ->
            val range = android.util.Range(lower, upper)
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }

        // Face rectangles only arrive on capture results, so auto framing needs
        // a session callback; there is no CameraX API that surfaces them.
        Camera2Interop.Extender(builder).setSessionCaptureCallback(faceWatcher)

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(encoderExecutor, ::provideEncoderSurface)
        this.preview = preview

        val analysis = builder.build()
        analysis.setAnalyzer(analysisExecutor, ::onFrame)
        this.analysis = analysis

        camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        applyLive(settings)
    }

    private fun resolutionSelector() = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                targetSize(settings.quality),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            )
        )
        .build()

    /**
     * Hands CameraX the encoder's input surface.
     *
     * The size is not ours to choose: CameraX picks it from the strategy above
     * and states it here, and the encoder must be configured for exactly that
     * before any surface exists. Declining is a legitimate answer, and a
     * device with no hardware AVC encoder takes it: the JPEG track carries on
     * alone and the desktop still gets a picture.
     */
    private fun provideEncoderSurface(request: SurfaceRequest) {
        val size = request.resolution
        videoSize = size
        request.setTransformationInfoListener(encoderExecutor) { info ->
            videoRotation = info.rotationDegrees
        }

        val fps = settings.fps.coerceIn(5, 60)
        val surface = encoder.start(
            size.width,
            size.height,
            fps,
            videoBitrate(size.width, size.height, fps, settings.quality)
        )
        if (surface == null) {
            request.willNotProvideSurface()
            return
        }
        // The codec owns the surface, so it goes first: releasing a surface out
        // from under a running codec is a native crash, not an exception.
        request.provideSurface(surface, encoderExecutor) {
            encoder.stop()
            surface.release()
        }
    }

    private fun targetSize(quality: String): Size = when (quality) {
        CameraQuality.LOW -> Size(640, 480)
        CameraQuality.BALANCED -> Size(1280, 720)
        // "Full" asks for 1080p rather than the raw sensor: a 12MP still is
        // not a video frame, and no link carries 30 of them a second.
        else -> Size(1920, 1080)
    }

    /**
     * Reads what this lens can actually do, once per bind.
     *
     * White balance in particular: a Camera2 AWB mode the hardware does not
     * list is accepted by the request and then silently ignored, so a preset
     * offered without checking is a control that visibly does nothing. What is
     * discovered here is reported in [report] and the desktop offers only that.
     */
    private fun readCapabilities(info: Camera2CameraInfo?) {
        activeArray = runCatching {
            info?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }.getOrNull()

        faceDetectMode = runCatching {
            val modes = info
                ?.getCameraCharacteristic(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                ?.toList()
                .orEmpty()
            // SIMPLE is enough — bounding boxes are all auto framing needs, and
            // FULL additionally computes landmarks nothing here reads.
            when {
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE in modes ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL in modes ->
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                else -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            }
        }.getOrDefault(CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)

        availableWhiteBalance = runCatching {
            info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.toList()
                ?.mapNotNull(::whiteBalanceName)
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: listOf(CameraWhiteBalance.AUTO)
    }

    private val hasFaceDetection: Boolean
        get() = faceDetectMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF && activeArray != null

    /**
     * Follows detected faces and moves the crop to keep them in shot.
     *
     * The whole of auto framing is here: the sensor's own face detector finds
     * the subject and `SCALER_CROP_REGION` reframes on it, so nothing has to
     * decode or scan a frame in software and the picture stays full resolution.
     * Faces are reported in active-array coordinates whatever the current crop
     * is, so there is no feedback loop to unwind.
     */
    private val faceWatcher = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!streaming || !settings.autoFraming || !hasFaceDetection) return

            // A few times a second is plenty: a subject cannot leave the frame
            // between updates, and re-sending capture options per frame would
            // cost more than the reframing is worth.
            val now = System.currentTimeMillis()
            if (now - lastFramingAt < FRAMING_INTERVAL_MS) return
            lastFramingAt = now

            val array = activeArray?.toBox() ?: return
            val faces = runCatching { result.get(TotalCaptureResult.STATISTICS_FACES) }
                .getOrNull()
                ?.mapNotNull { it.bounds?.toBox() }
                .orEmpty()

            // Nothing found: hold the last framing rather than snapping back to
            // full width, which would lurch every time a subject looks away.
            val target = subjectCrop(faces, array) ?: return
            val settled = framingCrop
            val eased = ease(settled ?: array, target)
            if (settled != null && !movedEnough(settled, eased, array)) return

            framingCrop = eased
            runCatching {
                camera?.cameraControl?.let {
                    androidx.camera.camera2.interop.Camera2CameraControl.from(it)
                        .setCaptureRequestOptions(captureOptions(settings))
                }
            }
        }
    }

    /** Settings the running capture session can take without a rebind. */
    private fun applyLive(s: CameraSettings) {
        val camera = camera ?: return
        val control = camera.cameraControl
        val info = camera.cameraInfo

        runCatching { control.setLinearZoom(s.zoom.toFloat().coerceIn(0f, 1f)) }
        if (info.hasFlashUnit()) runCatching { control.enableTorch(s.torch) }

        runCatching {
            val range = info.exposureState.exposureCompensationRange
            if (!s.autoExposure && info.exposureState.isExposureCompensationSupported) {
                control.setExposureCompensationIndex(s.exposure.coerceIn(range.lower, range.upper))
            } else if (s.autoExposure) {
                control.setExposureCompensationIndex(0)
            }
        }

        // Manual focus and white balance have no CameraX API, so they go
        // through Camera2 capture requests on the live session.
        runCatching {
            androidx.camera.camera2.interop.Camera2CameraControl.from(control)
                .setCaptureRequestOptions(captureOptions(s))
        }
    }

    private fun captureOptions(s: CameraSettings) =
        androidx.camera.camera2.interop.CaptureRequestOptions.Builder().apply {
            if (s.autoFocus) {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            } else {
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                // LENS_FOCUS_DISTANCE is dioptres: 0 is infinity and the
                // maximum is the closest the lens can go, so the slider is
                // inverted to read near-to-far like every other focus control.
                val minimumDistance = minimumFocusDistance()
                setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    ((1.0 - s.focusDistance) * minimumDistance).toFloat()
                )
            }
            // Requesting a mode the lens never listed is accepted and then
            // ignored, so an unsupported preset falls back to auto rather than
            // leaving the picture on whatever was set before.
            val requested = s.whiteBalance.takeIf { it in availableWhiteBalance }
                ?: CameraWhiteBalance.AUTO
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                whiteBalanceMode(requested)
            )
            // AWB obeys the mode only while it is unlocked, and a lock can
            // survive from an earlier session.
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)

            if (s.autoFraming && hasFaceDetection) {
                setCaptureRequestOption(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
                // Metering follows the subject too, so a backlit face is
                // exposed for rather than silhouetted.
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
                )
                framingCrop?.let {
                    setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, it.toRect())
                }
            }
            // With auto framing off, SCALER_CROP_REGION is deliberately absent:
            // omitting it hands the crop back to CameraX, whose setLinearZoom
            // owns it, instead of the two fighting over the same request key.
        }.build()

    private fun whiteBalanceMode(name: String): Int = when (name) {
        CameraWhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        CameraWhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        CameraWhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        CameraWhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        CameraWhiteBalance.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    /** The inverse of [whiteBalanceMode]; unknown or vendor modes are dropped. */
    private fun whiteBalanceName(mode: Int): String? = when (mode) {
        CaptureRequest.CONTROL_AWB_MODE_AUTO -> CameraWhiteBalance.AUTO
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> CameraWhiteBalance.INCANDESCENT
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> CameraWhiteBalance.FLUORESCENT
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> CameraWhiteBalance.DAYLIGHT
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> CameraWhiteBalance.CLOUDY
        CaptureRequest.CONTROL_AWB_MODE_SHADE -> CameraWhiteBalance.SHADE
        else -> null
    }

    private fun minimumFocusDistance(): Float = runCatching {
        val info = camera?.cameraInfo ?: return@runCatching 0f
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    }.getOrDefault(0f)

    // ---- Capture ----

    private fun onFrame(image: ImageProxy) {
        try {
            if (!streaming) return

            val now = System.nanoTime()
            // Only as fast as something reading this track actually needs. A
            // virtual camera and OBS take 30, and every frame above that is a
            // software JPEG compression competing for the CPU with the capture
            // pipeline the video track depends on. With no hardware encoder
            // this is the only track there is, so the cap lifts.
            val ceiling = if (encoder.isRunning) JPEG_MAX_FPS else 60
            val interval = 1_000_000_000L / settings.fps.coerceIn(1, ceiling)
            if (now < nextFrameAt) return
            // From the previous deadline, so the rate does not drift by one
            // encode per frame — but clamped forward after a hitch, or the
            // catch-up would arrive as a burst.
            nextFrameAt =
                if (nextFrameAt == 0L || now - nextFrameAt > interval) now + interval
                else nextFrameAt + interval

            // The sensor's own orientation plus whatever the user asked for.
            val rotation = (image.imageInfo.rotationDegrees + settings.rotation).mod(360)
            val swap = rotation == 90 || rotation == 270

            val jpeg = encode(image, rotation, settings.mirror) ?: return
            val meta = CameraFrame(
                seq = sequence.incrementAndGet(),
                width = if (swap) image.height else image.width,
                height = if (swap) image.width else image.height,
                ts = System.currentTimeMillis()
            )
            sink?.send(meta, jpeg)
        } catch (t: Throwable) {
            Log.w(TAG, "dropping frame", t)
        } finally {
            // Always: an unclosed ImageProxy stalls the whole pipeline after
            // a couple of frames, and the stream simply stops.
            image.close()
        }
    }

    /**
     * YUV to JPEG.
     *
     * Rotation and mirroring are applied here rather than on the desktop: the
     * bytes are the same size either way, and doing it at the source means the
     * MJPEG stream is already upright for any consumer — including OBS, which
     * has no idea a phone was involved.
     *
     * Both happen in the NV21 byte domain, *before* compression. Doing them on
     * the JPEG meant decode-to-Bitmap plus a second compress on every frame —
     * and since a phone held in portrait reports a 90° sensor rotation, "every
     * frame" was the normal case, not the exception.
     */
    private fun encode(image: ImageProxy, rotation: Int, mirror: Boolean): ByteArray? {
        var nv21 = toNv21(image) ?: return null
        var width = image.width
        var height = image.height

        if (rotation != 0) {
            nv21 = rotateNv21(nv21, width, height, rotation)
            if (rotation == 90 || rotation == 270) {
                val swapped = width
                width = height
                height = swapped
            }
        }
        if (mirror) nv21 = mirrorNv21(nv21, width, height)

        val stream = ByteArrayOutputStream(width * height / 4)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), jpegQuality(), stream)) return null
        return stream.toByteArray()
    }

    private fun jpegQuality(): Int = when (settings.quality) {
        CameraQuality.LOW -> 55
        CameraQuality.BALANCED -> 75
        else -> 90
    }

    /**
     * Packs the planar YUV_420_888 the analyser produces into the interleaved
     * NV21 [YuvImage] wants, honouring row and pixel strides — they are not
     * always tight, and assuming they are produces a sheared green picture on
     * exactly the devices that pad.
     */
    private fun toNv21(image: ImageProxy): ByteArray? {
        if (image.planes.size < 3) return null

        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var offset = 0
        if (yPlane.rowStride == width) {
            yBuffer.get(out, 0, width * height)
            offset = width * height
        } else {
            val row = ByteArray(yPlane.rowStride)
            for (y in 0 until height) {
                yBuffer.position(y * yPlane.rowStride)
                yBuffer.get(row, 0, minOf(row.size, yBuffer.remaining()))
                System.arraycopy(row, 0, out, offset, width)
                offset += width
            }
        }

        // NV21 interleaves V then U, which is the opposite order to the planes.
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        if (vPlane.pixelStride == 2) {
            // Semi-planar, which is what nearly every device produces. The V
            // plane's buffer is already V,U,V,U — the U plane is the same
            // allocation one byte along — so a chroma row is one bulk copy
            // instead of 2 × chromaWidth bounds-checked single-byte reads
            // (half a million of them per 1080p frame).
            val rowBytes = chromaWidth * 2
            for (row in 0 until chromaHeight) {
                val start = row * vPlane.rowStride
                if (start >= vBuffer.limit()) break
                vBuffer.position(start)
                val copied = minOf(rowBytes, vBuffer.remaining())
                vBuffer.get(out, offset, copied)
                if (copied < rowBytes) {
                    // The very last U byte sits past the V plane's limit.
                    val last = row * uPlane.rowStride + (chromaWidth - 1) * 2
                    if (last < uBuffer.limit()) out[offset + rowBytes - 1] = uBuffer.get(last)
                }
                offset += rowBytes
            }
        } else {
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val uvIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    if (uvIndex >= vBuffer.limit() || uvIndex >= uBuffer.limit()) continue
                    out[offset++] = vBuffer.get(uvIndex)
                    out[offset++] = uBuffer.get(uvIndex)
                }
            }
        }
        return out
    }

    // ---- Reporting ----

    /** Tells the desktop what this lens can actually do, so it hides the rest. */
    private fun report() {
        val info = camera?.cameraInfo
        val exposure = info?.exposureState
        val provider = provider

        val state = CameraState(
            streaming = streaming,
            settings = settings,
            maxZoomRatio = (info?.zoomState?.value?.maxZoomRatio ?: 1f).toDouble(),
            minExposure = exposure?.exposureCompensationRange?.lower ?: 0,
            maxExposure = exposure?.exposureCompensationRange?.upper ?: 0,
            hasTorch = info?.hasFlashUnit() ?: false,
            hasManualFocus = minimumFocusDistance() > 0f,
            hasManualExposure = exposure?.isExposureCompensationSupported ?: false,
            // More than one mode, or the control is decoration: every device
            // lists auto, and a lens that lists only auto cannot be adjusted.
            hasWhiteBalance = availableWhiteBalance.size > 1,
            whiteBalanceModes = availableWhiteBalance,
            hasAutoFraming = hasFaceDetection,
            hasFrontCamera = runCatching {
                provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
            }.getOrDefault(false)
        )
        onState?.invoke(state)
    }

    /**
     * One H.264 access unit, delivered on the encoder's own thread.
     *
     * The presentation time rides in the frame's timestamp rather than a wall
     * clock: it is the encoder's monotonic capture time, which is what a
     * decoder on the far end wants, and nothing on the video path reads the
     * timestamp for anything else.
     */
    private fun onEncodedUnit(unit: ByteArray, key: Boolean, presentationTimeUs: Long) {
        if (!streaming) return
        val size = videoSize
        sink?.send(
            CameraFrame(
                seq = videoSequence.incrementAndGet(),
                width = size.width,
                height = size.height,
                ts = presentationTimeUs,
                codec = CameraCodec.H264,
                key = key,
                rotation = (videoRotation + settings.rotation).mod(360),
                mirror = settings.mirror
            ),
            unit
        )
    }

    private companion object {
        const val TAG = "CameraStreamer"

        /** How often auto framing may move the crop. */
        const val FRAMING_INTERVAL_MS = 250L

        /** The compatibility track's ceiling while the video track is up. */
        const val JPEG_MAX_FPS = 30
    }
}

// ---- Pure helpers (no Android framework: unit-testable on the JVM) ----

/**
 * How much room to leave around the faces. A crop hugging the bounding boxes
 * is a portrait of a face, not a shot of a person, and every small head
 * movement then pushes the subject against an edge.
 */
private const val FRAMING_MARGIN = 2.4f

/** The tightest auto framing will crop, as a fraction of the sensor width. */
private const val FRAMING_MIN_WIDTH = 0.34f

/** Fraction of the way to the target each update moves. */
private const val FRAMING_EASE = 0.22f

/** Movement below this fraction of the sensor width is not worth a request. */
private const val FRAMING_DEADBAND = 0.012f

// ---- Pure helpers (no Android framework: unit-testable on the JVM) ----

/**
 * Chooses the auto-exposure target frame rate range to request, out of the
 * ones this sensor advertises as `(lower, upper)` pairs.
 *
 * The device only honours a range it listed. Among ranges that top out at the
 * rate asked for, the one with the *highest* lower bound wins: a (15,60) range
 * lets auto-exposure legally drop to 15 in dim light, which is precisely the
 * behaviour being fixed. Failing an exact match, take the fastest range that
 * does not exceed the request, and failing that the closest one going.
 */
/**
 * Bits per second to ask the hardware encoder for.
 *
 * Scaled by pixels and by rate, since both drive how much motion there is to
 * describe, and by the quality preset, which is the only thing the user said
 * about the trade. Clamped at both ends: below the floor a 480p stream turns
 * to blocks, and above the ceiling a burst of motion can outrun the Wi-Fi link
 * this session already shares with file transfers.
 */
internal fun videoBitrate(width: Int, height: Int, fps: Int, quality: String): Int {
    val bitsPerPixel = when (quality) {
        CameraQuality.LOW -> 0.06
        CameraQuality.BALANCED -> 0.09
        else -> 0.12
    }
    val bits = width.toDouble() * height * fps.coerceIn(1, 60) * bitsPerPixel
    return bits.toInt().coerceIn(1_000_000, 24_000_000)
}

internal fun pickFpsRange(available: List<Pair<Int, Int>>, target: Int): Pair<Int, Int>? {
    if (available.isEmpty()) return null
    available.filter { it.second == target }.maxByOrNull { it.first }?.let { return it }
    available.filter { it.second <= target }
        .maxByOrNull { it.second.toLong() * 1000 + it.first }?.let { return it }
    return available.minByOrNull { kotlin.math.abs(it.second - target) }
}

/**
 * Rotates an NV21 buffer by 90, 180 or 270 degrees clockwise.
 *
 * Chroma is half resolution and interleaved as V,U pairs, so a pair moves as a
 * unit — splitting one swaps the colours of two different pixels. Any other
 * angle is returned untouched; nothing upstream can produce one.
 */
internal fun rotateNv21(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
    if (degrees % 90 != 0 || degrees % 360 == 0) return src
    val ySize = width * height
    val cw = width / 2
    val ch = height / 2
    val out = ByteArray(src.size)
    // Destination row length, in samples, for the luma and chroma planes.
    val dstW = if (degrees == 180) width else height
    val dstCw = if (degrees == 180) cw else ch

    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx: Int
            val dy: Int
            when (degrees % 360) {
                90 -> { dx = height - 1 - y; dy = x }
                180 -> { dx = width - 1 - x; dy = height - 1 - y }
                else -> { dx = y; dy = width - 1 - x }
            }
            out[dy * dstW + dx] = src[y * width + x]
        }
    }
    for (y in 0 until ch) {
        for (x in 0 until cw) {
            val dx: Int
            val dy: Int
            when (degrees % 360) {
                90 -> { dx = ch - 1 - y; dy = x }
                180 -> { dx = cw - 1 - x; dy = ch - 1 - y }
                else -> { dx = y; dy = cw - 1 - x }
            }
            val s = ySize + (y * cw + x) * 2
            val d = ySize + (dy * dstCw + dx) * 2
            out[d] = src[s]
            out[d + 1] = src[s + 1]
        }
    }
    return out
}

/** Flips an NV21 buffer left-to-right, V,U pairs moving together. */
internal fun mirrorNv21(src: ByteArray, width: Int, height: Int): ByteArray {
    val ySize = width * height
    val cw = width / 2
    val ch = height / 2
    val out = ByteArray(src.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) out[row + width - 1 - x] = src[row + x]
    }
    for (y in 0 until ch) {
        for (x in 0 until cw) {
            val s = ySize + (y * cw + x) * 2
            val d = ySize + (y * cw + (cw - 1 - x)) * 2
            out[d] = src[s]
            out[d + 1] = src[s + 1]
        }
    }
    return out
}

/**
 * A rectangle in sensor coordinates.
 *
 * Deliberately not [android.graphics.Rect]: that class is a stub in JVM unit
 * tests, and the framing geometry below — aspect ratios, clamping, easing — is
 * the part most worth testing. Conversion happens at the one point that talks
 * to Camera2.
 */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centreX get() = (left + right) / 2f
    val centreY get() = (top + bottom) / 2f
}

internal fun Rect.toBox() = Box(left, top, right, bottom)
internal fun Box.toRect() = Rect(left, top, right, bottom)

/**
 * The crop that frames [faces] within [array], or null when there are none.
 *
 * The result always carries the sensor's own aspect ratio. A crop region of a
 * different shape is not letterboxed by the camera — it is cropped again to fit
 * the output, so an off-ratio rectangle silently reframes somewhere other than
 * where it was asked to.
 */
internal fun subjectCrop(faces: List<Box>, array: Box): Box? {
    if (faces.isEmpty() || array.width <= 0 || array.height <= 0) return null

    val union = Box(
        left = faces.minOf { it.left },
        top = faces.minOf { it.top },
        right = faces.maxOf { it.right },
        bottom = faces.maxOf { it.bottom }
    )

    val aspect = array.width.toFloat() / array.height

    // Wide enough for everything found, tall enough for the same, then the
    // larger of the two wins so nothing detected is left outside.
    val fromWidth = union.width * FRAMING_MARGIN
    val fromHeight = union.height * FRAMING_MARGIN * aspect
    var width = maxOf(fromWidth, fromHeight, array.width * FRAMING_MIN_WIDTH)
    var height = width / aspect

    // A crop can never exceed the sensor, and shrinking it must keep the ratio
    // or the picture stretches.
    if (width > array.width) {
        width = array.width.toFloat()
        height = width / aspect
    }
    if (height > array.height) {
        height = array.height.toFloat()
        width = height * aspect
    }

    // Slide rather than shrink when the subject is near an edge: a subject at
    // the frame's edge should be off-centre, not zoomed away from.
    val left = (union.centreX - array.left - width / 2).coerceIn(0f, array.width - width)
    val top = (union.centreY - array.top - height / 2).coerceIn(0f, array.height - height)

    return Box(
        array.left + left.toInt(),
        array.top + top.toInt(),
        array.left + (left + width).toInt(),
        array.top + (top + height).toInt()
    )
}

/**
 * Moves part of the way from [from] to [to].
 *
 * Auto framing that snapped to each new detection would jitter with every
 * twitch of the detector; easing turns that into a pan.
 */
internal fun ease(from: Box, to: Box): Box {
    fun step(a: Int, b: Int) = a + ((b - a) * FRAMING_EASE).toInt()
    return Box(
        step(from.left, to.left),
        step(from.top, to.top),
        step(from.right, to.right),
        step(from.bottom, to.bottom)
    )
}

/** Whether a move is large enough to be worth a capture request. */
internal fun movedEnough(from: Box, to: Box, array: Box): Boolean {
    val threshold = array.width * FRAMING_DEADBAND
    return kotlin.math.abs(from.left - to.left) > threshold ||
        kotlin.math.abs(from.top - to.top) > threshold ||
        kotlin.math.abs(from.right - to.right) > threshold ||
        kotlin.math.abs(from.bottom - to.bottom) > threshold
}
