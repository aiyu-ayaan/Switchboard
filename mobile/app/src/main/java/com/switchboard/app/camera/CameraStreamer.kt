package com.switchboard.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
        nextFrameAt = 0L
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
        //
        // It has to be one of the ranges the sensor advertises. Asking for a
        // (60,60) a device does not list gets the whole request ignored, and
        // auto-exposure then settles on whatever suits the light — indoors
        // that is (15,30), which is the real reason "60 fps" delivered 15.
        val advertised = runCatching {
            Camera2CameraInfo.from(provider.getCameraInfo(selector))
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper }
        }.getOrNull().orEmpty()

        pickFpsRange(advertised, settings.fps.coerceIn(5, 60))?.let { (lower, upper) ->
            Camera2Interop.Extender(builder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(lower, upper)
            )
        }

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

            val now = System.nanoTime()
            val interval = 1_000_000_000L / settings.fps.coerceIn(1, 60)
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
