package com.z.doorcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Continuous H.264 encoder that feeds a ring buffer of encoded frames.
 *
 * Camera2 writes raw pixels to the encoder's [inputSurface], the encoder emits
 * encoded NAL units on its output side, and a background thread drains them
 * into [ring]. Frames older than [prerollUs] are trimmed.
 *
 * A [Listener] is notified about each new frame so a live-recording muxer can
 * append it in real time. The output [MediaFormat] (with CSD = SPS/PPS blobs)
 * is published via [outputFormat] once the encoder has figured it out.
 */
class VideoRingBuffer(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val iFrameIntervalSec: Int,
    prerollSec: Int,
) {
    data class EncodedFrame(
        val data: ByteArray,
        val ptsUs: Long,
        val isKey: Boolean,
    )

    interface Listener {
        /** Called on the encoder output thread for every encoded (non-CSD) frame. */
        fun onEncodedFrame(frame: EncodedFrame)
    }

    var listener: Listener? = null

    /** Set once encoder emits INFO_OUTPUT_FORMAT_CHANGED. Contains CSD buffers. */
    @Volatile var outputFormat: MediaFormat? = null
        private set

    private val prerollUs: Long = prerollSec.toLong() * 1_000_000L
    private val ring = ArrayDeque<EncodedFrame>()
    private val ringLock = Any()

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: HandlerThread? = null
    @Volatile private var running = false

    fun getInputSurface(): Surface = inputSurface
        ?: throw IllegalStateException("start() first")

    fun start() {
        val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
        }
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        running = true
        drainThread = HandlerThread("EncoderDrain", Process.THREAD_PRIORITY_VIDEO).also {
            it.start()
            it.looper.thread.apply { /* handler not needed; we loop manually below */ }
        }
        // Run the drain loop on a plain thread (not handler-driven) — dequeue
        // blocks with a timeout so it polls cleanly.
        Thread({ drainLoop() }, "EncoderDrain").start()
        Log.i(TAG, "encoder started ${width}x$height @ ${frameRate}fps ${bitRate/1000}kbps iFrame=${iFrameIntervalSec}s")
    }

    fun stop() {
        running = false
        try { encoder?.signalEndOfInputStream() } catch (_: Throwable) {}
        try { encoder?.stop() } catch (_: Throwable) {}
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        drainThread?.quitSafely()
        drainThread = null
        synchronized(ringLock) { ring.clear() }
    }

    /**
     * Snapshot the current ring contents beginning at the **most recent**
     * keyframe that is within preroll range (or the oldest keyframe if none
     * within range). Returned list is independent of the ring — safe to iterate
     * while new frames continue to arrive.
     */
    fun snapshotFromLastKeyframeInRange(): List<EncodedFrame> {
        synchronized(ringLock) {
            if (ring.isEmpty()) return emptyList()
            val newest = ring.last.ptsUs
            val minPts = newest - prerollUs
            // Find last keyframe with pts >= minPts; otherwise the first keyframe in the ring.
            var startIdx = -1
            val it = ring.descendingIterator()
            var i = ring.size - 1
            while (it.hasNext()) {
                val f = it.next()
                if (f.isKey && f.ptsUs >= minPts) {
                    startIdx = i
                    break
                }
                i--
            }
            if (startIdx < 0) {
                // Fallback: oldest keyframe in the ring, regardless of preroll window.
                var j = 0
                for (f in ring) {
                    if (f.isKey) { startIdx = j; break }
                    j++
                }
            }
            if (startIdx < 0) return emptyList()
            val out = ArrayList<EncodedFrame>(ring.size - startIdx)
            var k = 0
            for (f in ring) {
                if (k >= startIdx) out.add(f)
                k++
            }
            return out
        }
    }

    private fun drainLoop() {
        val bufInfo = MediaCodec.BufferInfo()
        val enc = encoder ?: return
        while (running) {
            val idx: Int = try {
                enc.dequeueOutputBuffer(bufInfo, 10_000L)
            } catch (t: Throwable) {
                if (!running) {
                    // Clean shutdown race — stop() released the codec while we were in dequeue.
                    break
                }
                Log.e(TAG, "dequeueOutputBuffer threw", t); break
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = enc.outputFormat
                    Log.i(TAG, "encoder output format = $outputFormat")
                }
                idx >= 0 -> {
                    val buf: ByteBuffer? = try { enc.getOutputBuffer(idx) } catch (_: Throwable) { null }
                    if (buf != null && bufInfo.size > 0 &&
                        (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(bufInfo.offset)
                        buf.limit(bufInfo.offset + bufInfo.size)
                        val data = ByteArray(bufInfo.size)
                        buf.get(data)
                        val frame = EncodedFrame(
                            data = data,
                            ptsUs = bufInfo.presentationTimeUs,
                            isKey = (bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        )
                        synchronized(ringLock) {
                            ring.add(frame)
                            // Trim oldest frames beyond preroll window
                            val newestPts = frame.ptsUs
                            while (ring.size > 1) {
                                val oldest = ring.peekFirst() ?: break
                                if (newestPts - oldest.ptsUs > prerollUs) ring.pollFirst() else break
                            }
                        }
                        // Notify listener outside the lock
                        try { listener?.onEncodedFrame(frame) } catch (t: Throwable) {
                            Log.e(TAG, "listener threw", t)
                        }
                    }
                    try { enc.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
        Log.i(TAG, "drain loop exiting")
    }

    companion object { private const val TAG = "DoorCam.Ring" }
}
