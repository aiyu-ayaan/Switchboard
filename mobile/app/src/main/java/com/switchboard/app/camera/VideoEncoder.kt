package com.switchboard.app.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 encoder fed straight from a camera surface.
 *
 * This exists because a JPEG per frame cannot reach the rates this app offers.
 * Compressing a 1080p picture in software costs tens of milliseconds on the
 * CPU, and the pipeline that fed it — pack to NV21, rotate, mirror — cost as
 * much again in per-pixel Kotlin loops. A "60 fps" request delivered about a
 * quarter of that, and every frame it did deliver was a few hundred kilobytes.
 *
 * Here the camera writes into [MediaCodec]'s own input surface, so no frame
 * ever passes through the CPU or the Java heap: the pixels go from the capture
 * pipeline to the encoder block and come back as a few kilobytes of H.264.
 * Rotation and mirroring are left to the far end, which does them for free in a
 * display transform.
 *
 * Every access unit handed to [onUnit] is Annex-B. Keyframes carry their own
 * SPS/PPS — [MediaCodec] emits those once, before the first frame, and a viewer
 * that opens the stream ten minutes later would otherwise have nothing to
 * configure a decoder with.
 */
class VideoEncoder(
    /** Called on the encoder's own thread with one Annex-B access unit. */
    private val onUnit: (unit: ByteArray, key: Boolean, presentationTimeUs: Long) -> Unit
) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    /** SPS/PPS, prepended to every keyframe so each one decodes standalone. */
    private var parameterSets: ByteArray? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Configures an encoder for [width] x [height] and returns the surface the
     * camera should draw into, or null when no hardware encoder would take
     * those parameters — in which case the caller keeps to the JPEG track.
     */
    @Synchronized
    fun start(width: Int, height: Int, fps: Int, bitrate: Int): Surface? {
        stop()

        return runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // One second between keyframes. A viewer joining mid-stream is
                // blank until the first one arrives, and every keyframe costs
                // several times a delta frame, so this is the whole trade.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                // Live capture, not a file: tell the encoder not to buffer
                // frames looking for a better rate-control decision, and to
                // take priority over background work. Without these some
                // encoders hold several frames back, which is latency the
                // user sees as lag between moving and the picture moving.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val thread = HandlerThread("switchboard-encoder").also { it.start() }
            codec.setCallback(callback, Handler(thread.looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val surface = codec.createInputSurface()
            codec.start()

            this.codec = codec
            this.thread = thread
            isRunning = true
            surface
        }.getOrElse {
            Log.w(TAG, "hardware H.264 unavailable; staying on the JPEG track", it)
            stop()
            null
        }
    }

    /**
     * Synchronized with [start] because they arrive from different threads: a
     * rebind stops the encoder from the main thread while CameraX releases the
     * old surface on the encoder executor, and two overlapping releases of one
     * [MediaCodec] is a native crash rather than an exception.
     */
    @Synchronized
    fun stop() {
        isRunning = false
        parameterSets = null

        codec?.let { runCatching { it.stop() } ; runCatching { it.release() } }
        codec = null
        thread?.let { runCatching { it.quitSafely() } }
        thread = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: the camera fills the buffers, never this side.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) emit(buffer, info)
            } catch (t: Throwable) {
                Log.w(TAG, "dropping encoded unit", t)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Some encoders deliver the parameter sets here instead of as a
            // codec-config buffer, so take them from whichever arrives.
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")
            if (csd0 == null) return
            val sps = ByteArray(csd0.remaining()).also { csd0.duplicate().get(it) }
            val pps = csd1?.let { ByteArray(it.remaining()).also { out -> it.duplicate().get(out) } }
            parameterSets = if (pps == null) sps else sps + pps
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "encoder failed", e)
            isRunning = false
        }
    }

    private fun emit(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val bytes = ByteArray(info.size)
        buffer.get(bytes)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Configuration, not a picture. Held back and re-sent with each
            // keyframe rather than forwarded once: a desktop that opens the
            // stream after this point never saw it.
            parameterSets = bytes
            return
        }

        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val sets = parameterSets
        val unit = if (key && sets != null) sets + bytes else bytes
        onUnit(unit, key, info.presentationTimeUs)
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
