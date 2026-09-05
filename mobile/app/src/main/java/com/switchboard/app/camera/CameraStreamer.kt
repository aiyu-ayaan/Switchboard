package com.switchboard.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
 * Streams this device's camera to the paired desktop as a sequence of JPEG
 * frames over the existing encrypted session.
 *
 * **Frames are encoded here and sent whole.** There is no inter-frame codec:
 * every frame stands alone, so a dropped one costs exactly itself rather than
 * corrupting everything until the next keyframe. On a LAN that is the right
 * trade — bandwidth is cheap and a stream that degrades gracefully under loss
 * is worth more than one that is smaller but brittle. It is also what lets the
 * desktop serve the stream as MJPEG to OBS or VLC without transcoding.
 *
 * Capture runs only while the app is in the foreground on the camera screen.
 * Streaming from the background would need a `camera` foreground service and a
 * persistent notification for a picture nobody is looking at.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraStreamer(private val context: Context) {

    /** Sends one frame: metadata plus the encoded bytes beside it. */
    fun interface FrameSink {
        fun send(meta: CameraFrame, jpeg: ByteArray)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val sequence = AtomicLong(0)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null

    private var sink: FrameSink? = null
    private var onState: ((CameraState) -> Unit)? = null

    @Volatile
    private var settings = CameraSettings()

    @Volatile
    private var streaming = false

    /**
     * Wall-clock time the next frame is allowed at. The analyser is handed
     * frames at the sensor's rate; anything above the requested rate is closed
     * without encoding, which is where most of the CPU saving of a low fps
     * setting actually comes from.
     */
    @Volatile
    private var nextFrameAt = 0L

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
        camera = null
        analysis = null
        sequence.set(0)
        onState?.invoke(CameraState(streaming = false, settings = settings))
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
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

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (settings.facing == CameraFacing.FRONT) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()

        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize(settings.quality),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            // The analyser must never queue: a frame that arrives while the
            // last one is still encoding is stale by the time it would be
            // sent, and holding it only adds latency to everything after it.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Frame rate is a Camera2 concern; CameraX has no first-class control
        // for it on ImageAnalysis.
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(settings.fps.coerceIn(5, 60), settings.fps.coerceIn(5, 60))
        )

        val analysis = builder.build()
        analysis.setAnalyzer(analysisExecutor, ::onFrame)
        this.analysis = analysis

        camera = provider.bindToLifecycle(owner, selector, analysis)
        applyLive(settings)
    }

    private fun targetSize(quality: String): Size = when (quality) {
        CameraQuality.LOW -> Size(640, 480)
        CameraQuality.BALANCED -> Size(1280, 720)
        // "Full" asks for 1080p rather than the raw sensor: a 12MP still is
        // not a video frame, and no link carries 30 of them a second.
        else -> Size(1920, 1080)
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
            val awb = whiteBalanceMode(s.whiteBalance)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                awb
            )
        }.build()

    private fun whiteBalanceMode(name: String): Int = when (name) {
        CameraWhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        CameraWhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        CameraWhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        CameraWhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        CameraWhiteBalance.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
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

            val now = System.currentTimeMillis()
            val interval = 1000L / settings.fps.coerceIn(1, 60)
            if (now < nextFrameAt) return
            nextFrameAt = now + interval

            val jpeg = encode(image) ?: return
            val meta = CameraFrame(
                seq = sequence.incrementAndGet(),
                width = image.width,
                height = image.height,
                ts = now
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
     */
    private fun encode(image: ImageProxy): ByteArray? {
        val nv21 = toNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        val stream = ByteArrayOutputStream(image.width * image.height / 4)
        if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality(), stream)) {
            return null
        }

        // The sensor's own orientation plus whatever the user asked for.
        val rotation = (image.imageInfo.rotationDegrees + settings.rotation) % 360
        if (rotation == 0 && !settings.mirror) return stream.toByteArray()

        val bytes = stream.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return bytes
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (settings.mirror) postScale(-1f, 1f)
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream(bytes.size)
        rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality(), out)
        bitmap.recycle()
        if (rotated !== bitmap) rotated.recycle()
        return out.toByteArray()
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
                yBuffer.get(row, 0, minOf(row.size, yBuffer.remaining() + y * yPlane.rowStride))
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

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uvIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                if (uvIndex >= vBuffer.limit() || uvIndex >= uBuffer.limit()) continue
                out[offset++] = vBuffer.get(uvIndex)
                out[offset++] = uBuffer.get(uvIndex)
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
            hasWhiteBalance = true,
            hasFrontCamera = runCatching {
                provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
            }.getOrDefault(false)
        )
        onState?.invoke(state)
    }

    private companion object {
        const val TAG = "CameraStreamer"
    }
}
