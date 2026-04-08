package com.z.doorcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the MediaMuxer lifecycle. Receives encoded frames from [VideoRingBuffer]
 * and manages the state machine:
 *
 *   IDLE ──onMotionStart()──►  RECORDING  ──onMotionEnd()──►  HOLDOFF
 *     ▲                           │                              │
 *     │                           │ new motion during holdoff    │
 *     │                           ◄──────────────────────────────┘
 *     │                                                          │
 *     └────────────────hold timer expires ───────────────────────┘
 *
 * On RECORDING entry: opens a MediaMuxer, writes the preroll snapshot from
 * the ring (starting at the most recent keyframe), then appends live frames
 * as they arrive from the encoder.
 *
 * On HOLDOFF expiry: closes the muxer, runs MediaScanner so the clip appears
 * in Huawei Gallery immediately.
 *
 * All state mutations happen on a single background Handler to avoid any race
 * with the encoder drain thread (which calls onEncodedFrame) and the UI thread
 * (which calls forceStart/forceStop).
 */
class RecordingController(
    private val context: Context,
    private val ring: VideoRingBuffer,
    private val holdAfterMotionMs: Long = 8_000L,
    private val orientationHintDeg: Int = 90,
) : VideoRingBuffer.Listener {

    enum class State { IDLE, RECORDING, HOLDOFF }

    interface Listener {
        fun onRecordingStarted(file: File, trigger: String)
        fun onRecordingStopped(file: File)
    }
    var listener: Listener? = null

    @Volatile var state: State = State.IDLE
        private set

    private val thread = HandlerThread("RecCtrl").apply { start() }
    private val handler = Handler(thread.looper)

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var currentFile: File? = null
    private var firstPtsUs: Long = -1L
    private var lastWrittenPtsUs: Long = -1L
    private var holdRunnable: Runnable? = null

    init {
        ring.listener = this
    }

    // ------------- public API (thread-safe) -------------

    fun onMotionStart() {
        handler.post { handleMotionStart(trigger = "motion") }
    }
    fun onMotionActive() { /* no-op for now; kept for future analytics */ }
    fun onMotionEnd() {
        handler.post { handleMotionEnd() }
    }

    /** Manual REC button — same pipeline as motion start, different trigger label. */
    fun forceStart() { handler.post { handleMotionStart(trigger = "manual") } }
    /** Manual STOP button — bypass hold timer, close immediately. */
    fun forceStop()  { handler.post { handleForceStop() } }

    fun release() {
        handler.post {
            handleForceStop()
            thread.quitSafely()
        }
    }

    // ------------- state-machine handlers (background thread only) -------------

    private fun handleMotionStart(trigger: String) {
        when (state) {
            State.IDLE -> openMuxerAndFlushPreroll(trigger)
            State.HOLDOFF -> {
                // Cancel pending close; continue same recording session.
                Log.i(TAG, "motion during holdoff → extend recording")
                cancelHold()
                state = State.RECORDING
            }
            State.RECORDING -> { /* already recording, nothing to do */ }
        }
    }

    private fun handleMotionEnd() {
        if (state != State.RECORDING) return
        state = State.HOLDOFF
        scheduleHold()
    }

    private fun handleForceStop() {
        if (state == State.IDLE) return
        cancelHold()
        closeMuxer()
        state = State.IDLE
    }

    // ------------- muxer lifecycle -------------

    private fun openMuxerAndFlushPreroll(trigger: String) {
        val fmt = ring.outputFormat
        if (fmt == null) {
            Log.w(TAG, "cannot start recording: encoder outputFormat not yet available")
            return
        }
        val preroll = ring.snapshotFromLastKeyframeInRange()
        if (preroll.isEmpty()) {
            Log.w(TAG, "cannot start recording: ring has no keyframe yet")
            return
        }

        val dir = File(Environment.getExternalStorageDirectory(), VIDEO_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${trigger}_$stamp.mp4")

        val m = try {
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaMuxer create failed", t); return
        }
        try { m.setOrientationHint(orientationHintDeg) } catch (_: Throwable) {}
        val ti = try {
            m.addTrack(fmt)
        } catch (t: Throwable) {
            Log.e(TAG, "addTrack failed", t)
            try { m.release() } catch (_: Throwable) {}
            return
        }
        try { m.start() } catch (t: Throwable) {
            Log.e(TAG, "muxer start failed", t)
            try { m.release() } catch (_: Throwable) {}
            return
        }
        muxer = m
        trackIndex = ti
        currentFile = file
        firstPtsUs = preroll.first().ptsUs
        lastWrittenPtsUs = -1L
        state = State.RECORDING
        Log.i(TAG, "RECORDING → $file (preroll=${preroll.size} frames, trigger=$trigger)")
        listener?.onRecordingStarted(file, trigger)

        // Write preroll frames (rebase PTS so MediaMuxer is happy)
        for (f in preroll) writeFrameToMuxer(f)
    }

    private fun closeMuxer() {
        val m = muxer ?: return
        val file = currentFile
        try { m.stop() } catch (t: Throwable) { Log.w(TAG, "muxer stop threw", t) }
        try { m.release() } catch (_: Throwable) {}
        muxer = null
        trackIndex = -1
        firstPtsUs = -1L
        lastWrittenPtsUs = -1L
        if (file != null) {
            Log.i(TAG, "CLOSED → $file size=${file.length()}B")
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("video/mp4")
            ) { path, uri -> Log.i(TAG, "Gallery scan $path → $uri") }
            listener?.onRecordingStopped(file)
        }
        currentFile = null
    }

    private fun scheduleHold() {
        cancelHold()
        val r = Runnable {
            Log.i(TAG, "hold expired → close muxer")
            closeMuxer()
            state = State.IDLE
        }
        holdRunnable = r
        handler.postDelayed(r, holdAfterMotionMs)
    }
    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    // ------------- encoder callback (encoder drain thread) -------------

    override fun onEncodedFrame(frame: VideoRingBuffer.EncodedFrame) {
        // Hop to our handler so muxer writes are serialized with state changes.
        handler.post {
            if (state != State.RECORDING && state != State.HOLDOFF) return@post
            writeFrameToMuxer(frame)
        }
    }

    private fun writeFrameToMuxer(frame: VideoRingBuffer.EncodedFrame) {
        val m = muxer ?: return
        // Rebase PTS to start at 0 for this recording (MediaMuxer accepts
        // absolute too, but rebasing keeps clips self-contained and avoids
        // large timestamp values on long-running encoders).
        val rebased = (frame.ptsUs - firstPtsUs).coerceAtLeast(0L)
        // Enforce strict monotonicity (MediaMuxer requires it per track).
        if (rebased <= lastWrittenPtsUs) return
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = frame.data.size
            presentationTimeUs = rebased
            flags = if (frame.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }
        try {
            m.writeSampleData(trackIndex, ByteBuffer.wrap(frame.data), info)
            lastWrittenPtsUs = rebased
        } catch (t: Throwable) {
            Log.e(TAG, "writeSampleData failed", t)
        }
    }

    companion object {
        private const val TAG = "DoorCam.Rec"
        private const val VIDEO_DIR = "Movies/DoorCam"
    }
}
