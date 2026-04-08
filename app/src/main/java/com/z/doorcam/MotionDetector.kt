package com.z.doorcam

import android.media.Image
import android.util.Log

/**
 * Dead-simple motion detector: downsample the Y plane of an Image to a fixed
 * small grid, compute sum-of-absolute-differences vs the previous grid,
 * normalize to 0–255, threshold with N-frame confirmation, and emit
 * onStart/onActive/onEnd callbacks.
 *
 * Thread model: feed(Image) is called on the ImageReader's background handler.
 * Callbacks are invoked on that same thread — listener must post to its own
 * thread if it needs UI or camera ops.
 */
class MotionDetector(
    private val gridW: Int = 80,
    private val gridH: Int = 60,
    initialThreshold: Int = 18,            // 0–255 average abs diff per cell
    private val confirmFrames: Int = 2,    // frames above threshold before firing start
    private val listener: Listener
) {
    @Volatile var threshold: Int = initialThreshold
    interface Listener {
        /** First confirmed motion frame after an idle period. */
        fun onMotionStart(score: Int)
        /** Called on every confirmed-motion frame (including the start frame). */
        fun onMotionActive(score: Int)
        /** First frame below threshold after motion had been active. */
        fun onMotionEnd()
    }

    private val prev = IntArray(gridW * gridH)
    private var havePrev = false
    private var aboveCount = 0
    private var belowCount = 0
    private var active = false

    /** Last computed diff score — read-only, for status overlays and diagnostics. */
    @Volatile var lastScore: Int = 0
        private set

    fun feed(img: Image) {
        val score = computeDiffScore(img)
        lastScore = score
        if (score >= threshold) {
            belowCount = 0
            aboveCount++
            if (!active && aboveCount >= confirmFrames) {
                active = true
                Log.i(TAG, "motion START score=$score")
                listener.onMotionStart(score)
                listener.onMotionActive(score)
            } else if (active) {
                listener.onMotionActive(score)
            }
        } else {
            aboveCount = 0
            if (active) {
                belowCount++
                // Require a single quiet frame to emit end — the hold timer in
                // RecordingController handles any additional grace period.
                if (belowCount >= 1) {
                    active = false
                    Log.i(TAG, "motion END  score=$score")
                    listener.onMotionEnd()
                }
            }
        }
    }

    /**
     * Downsample the image's Y plane into [gridW]×[gridH] averages, compute
     * sum of absolute differences with the previous grid, and return it
     * normalised to a 0–255 "average diff per cell" number.
     */
    private fun computeDiffScore(img: Image): Int {
        val yPlane = img.planes[0]
        val yBuf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val srcW = img.width
        val srcH = img.height

        // Sample one pixel per cell at the cell center — cheap and good enough
        // for motion detection on a static camera. (True box average is also
        // an option but costs N× the loads for marginal SNR gain here.)
        val cellW = srcW / gridW
        val cellH = srcH / gridH
        val cur = IntArray(gridW * gridH)
        var idx = 0
        for (gy in 0 until gridH) {
            val sy = gy * cellH + cellH / 2
            val rowBase = sy * rowStride
            for (gx in 0 until gridW) {
                val sx = gx * cellW + cellW / 2
                // Y is unsigned 0–255, ByteBuffer.get() returns signed byte
                cur[idx++] = yBuf.get(rowBase + sx).toInt() and 0xFF
            }
        }

        if (!havePrev) {
            System.arraycopy(cur, 0, prev, 0, cur.size)
            havePrev = true
            return 0
        }

        var sumDiff = 0L
        for (i in cur.indices) {
            val d = cur[i] - prev[i]
            sumDiff += if (d < 0) -d else d
        }
        System.arraycopy(cur, 0, prev, 0, cur.size)
        // Normalize: average absolute diff per cell, 0–255
        return (sumDiff / cur.size).toInt()
    }

    companion object { private const val TAG = "DoorCam.Motion" }
}
