package com.z.doorcam

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.Gravity
import android.widget.FrameLayout
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.os.SystemClock
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.io.File

/**
 * DoorCam M2 — motion-triggered recording with pre-roll ring buffer.
 *
 * Single Camera2 session with three output surfaces, running continuously:
 *   1) TextureView                    → live preview (scrcpy mirrors this)
 *   2) ImageReader(YUV)               → MotionDetector
 *   3) VideoRingBuffer encoder Surface → rolling H264 ring buffer (last N s)
 *
 * When motion is detected, RecordingController opens a MediaMuxer, flushes
 * the preroll from the ring (starting at the most recent keyframe), then keeps
 * appending live encoded frames until the motion has been still for
 * HOLD_AFTER_MOTION_MS, at which point the clip is closed and handed to
 * MediaScanner so it shows up in Huawei Gallery instantly.
 *
 * REC button = manual trigger into the same pipeline (preroll + live + hold).
 */
class ViewerActivity : Activity(), MotionDetector.Listener, RecordingController.Listener {

    companion object {
        private const val TAG = "DoorCam"
        private const val REQ_CAM = 42

        // 4:3 aspect matches the sensor's native aspect ratio on this phone
        // (and most phone cameras) — requesting 16:9 was making the ISP stretch
        // the image horizontally, turning the fish-eye circle into an ellipse.
        // 1440x1080 is a standard Camera2 4:3 size within PREVIEW/RECORD caps.
        private val CAPTURE_SIZE = Size(1440, 1080)

        private const val VIDEO_BITRATE = 4_000_000
        private const val VIDEO_FPS = 20
        private const val I_FRAME_INTERVAL_S = 1
        private const val PREROLL_SECONDS = 5
        private const val HOLD_AFTER_MOTION_MS = 8_000L

        // Intent extras / SharedPreferences keys. Extras override prefs for the
        // current launch AND are persisted for subsequent launches.
        //
        //   adb shell am start -n com.z.doorcam/.ViewerActivity \
        //        --es camera_id 2 --ei rot 90 --ef zoom 2.0 --ef cx 0.5 --ef cy 0.5
        //
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_ROT       = "rot"    // int 0/90/180/270 — extra rotation on top of sensor↔display
        const val EXTRA_ZOOM      = "zoom"   // float ≥ 1.0 — digital zoom via SCALER_CROP_REGION
        const val EXTRA_CX        = "cx"     // float 0..1 — crop center X as fraction of active array width
        const val EXTRA_CY        = "cy"     // float 0..1 — crop center Y as fraction of active array height
        const val EXTRA_FLIP_V    = "flipv"  // boolean — mirror the preview vertically (top↔bottom)
        const val EXTRA_FLIP_H    = "fliph"  // boolean — mirror the preview horizontally (left↔right)
        const val EXTRA_THRESH    = "thresh" // int 1..100 — motion detection threshold

        private const val PREFS = "doorcam_prefs"
    }

    private lateinit var textureView: TextureView
    private lateinit var status: TextView
    private lateinit var recBtn: TextView
    private lateinit var rotBtn: TextView
    private lateinit var flipBtn: TextView

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var sensorOrientation: Int = 90
    private var hardwareLevel: Int = -1
    private var minFocusDistance: Float = 0f  // 0 = fixed-focus, >0 = nearest reachable diopters
    private var activeArraySize: Rect? = null
    private var maxDigitalZoom: Float = 1f

    // Persistent user config (from Intent extras and/or SharedPreferences)
    private var cfgRotation: Int = 0     // 0/90/180/270
    private var cfgZoom: Float = 1f      // 1.0 = no zoom
    private var cfgCropCx: Float = 0.5f  // crop center fraction 0..1
    private var cfgCropCy: Float = 0.5f
    private var cfgFlipV: Boolean = false
    private var cfgFlipH: Boolean = false
    private var cfgThreshold: Int = 18
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var ringBuffer: VideoRingBuffer? = null
    private var recController: RecordingController? = null
    private lateinit var motionDetector: MotionDetector

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    @Volatile private var frameCount = 0L
    @Volatile private var lastMotionScore = 0
    @Volatile private var cameraOpening = false

    // Status overlay state
    @Volatile private var batteryPct = -1
    @Volatile private var batteryCharging = false
    @Volatile private var lastMotionEndRealtimeMs = 0L  // SystemClock.elapsedRealtime() of last motion end
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the foreground service FIRST — this elevates the process
        // to PROCESS_STATE_FOREGROUND_SERVICE so Camera2.openCamera() won't
        // be rejected with "cannot open camera from background" when the
        // physical display is blanked via scrcpy.
        DoorCamService.start(this)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_viewer)
        textureView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        recBtn = findViewById(R.id.recBtn)
        rotBtn = findViewById(R.id.rotBtn)
        flipBtn = findViewById(R.id.flipBtn)
        recBtn.setOnClickListener { onRecBtn() }
        rotBtn.setOnClickListener { onRotBtn() }
        flipBtn.setOnClickListener { onFlipBtn() }

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        loadConfig()
        motionDetector = MotionDetector(initialThreshold = cfgThreshold, listener = this)
        updateAdjustButtonLabels()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "lifecycle: onResume")
        registerBatteryReceiver()
        startBgThread()
        if (textureView.isAvailable) {
            configureTransform(textureView.width, textureView.height)
            tryOpenCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                    textureView.surfaceTextureListener = null  // prevent re-fire
                    configureTransform(w, h); tryOpenCamera()
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                }
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    override fun onPause() {
        Log.i(TAG, "lifecycle: onPause")
        unregisterBatteryReceiver()
        textureView.surfaceTextureListener = null
        cameraOpening = false
        recController?.release(); recController = null
        ringBuffer?.stop(); ringBuffer = null
        closeCamera()
        stopBgThread()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "lifecycle: onConfigurationChanged orientation=${newConfig.orientation}")
        // Activity stays alive thanks to android:configChanges=orientation|...
        // Re-apply the transform for the new view size after layout pass.
        textureView.post { configureTransform(textureView.width, textureView.height) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) tryOpenCamera()
        } else {
            status.text = "no camera permission"
            Log.e(TAG, "camera permission denied")
        }
    }

    // ------------------------------------------------------------------
    // Camera / session plumbing
    // ------------------------------------------------------------------

    /**
     * Resize the TextureView itself to the aspect-correct letterbox rectangle
     * inside its parent FrameLayout. This avoids the TextureView.setTransform
     * matrix entirely — we just make the view smaller and centered, and let
     * TextureView's default uniform stretch fill it. Much more reliable across
     * OEMs (the matrix approach was giving inconsistent results on this Huawei).
     */
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val parentW = (textureView.parent as? FrameLayout)?.width ?: viewW
        val parentH = (textureView.parent as? FrameLayout)?.height ?: viewH
        if (parentW == 0 || parentH == 0) {
            // Parent not yet measured; retry after layout.
            textureView.post { configureTransform(textureView.width, textureView.height) }
            return
        }
        // Buffer aspect is 4:3 landscape (1440x1080). We letterbox it into the
        // parent view. cfgRotation is applied AFTER the letterbox as a matrix
        // rotation inside this same landscape box — for fish-eye content the
        // circle is rotationally symmetric so no visible clipping.
        val bufW = CAPTURE_SIZE.width.toFloat()
        val bufH = CAPTURE_SIZE.height.toFloat()
        val bufAspect = bufW / bufH
        val parentAspect = parentW.toFloat() / parentH

        val targetW: Int
        val targetH: Int
        if (parentAspect > bufAspect) {
            targetH = parentH
            targetW = (parentH * bufAspect).toInt()
        } else {
            targetW = parentW
            targetH = (parentW / bufAspect).toInt()
        }

        val lp = textureView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != targetW || lp.height != targetH || lp.gravity != Gravity.CENTER) {
            lp.width = targetW
            lp.height = targetH
            lp.gravity = Gravity.CENTER
            textureView.layoutParams = lp
            Log.i(TAG, "TextureView resized to ${targetW}x$targetH (parent=${parentW}x$parentH buf=${bufW.toInt()}x${bufH.toInt()})")
        }

        // Build final transform: base rotation from sensor↔display alignment
        // + user-configurable rotation + user-configurable mirror flips.
        // Flips are scale(±1) around view center; the Y flip is the "flip
        // vertically" operation (top↔bottom mirror) and X is left↔right.
        // Order: scale (flip) first, then rotation, so the flip is applied
        // in the sensor's local space before the user rotation.
        val m = Matrix()
        val cx = targetW / 2f
        val cy = targetH / 2f
        val sx = if (cfgFlipH) -1f else 1f
        val sy = if (cfgFlipV) -1f else 1f
        if (sx != 1f || sy != 1f) {
            m.postScale(sx, sy, cx, cy)
        }
        val rotation = windowManager.defaultDisplay.rotation
        val baseDeg = if (rotation == Surface.ROTATION_270) 180f else 0f
        val totalDeg = (baseDeg + cfgRotation) % 360f
        if (totalDeg != 0f) {
            m.postRotate(totalDeg, cx, cy)
        }
        textureView.setTransform(m)
        Log.i(TAG, "view transform: base=$baseDeg user=$cfgRotation total=$totalDeg flipV=$cfgFlipV flipH=$cfgFlipH")
    }

    private fun startBgThread() {
        bgThread = HandlerThread("DoorCamBg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }
    private fun stopBgThread() {
        bgThread?.quitSafely()
        try { bgThread?.join(); bgThread = null; bgHandler = null } catch (_: InterruptedException) {}
    }

    /**
     * Load user config. Priority: Intent extras > SharedPreferences > defaults.
     * If an Intent extra is present, it's also written back to prefs so the
     * setting sticks across subsequent launches.
     */
    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val edit = prefs.edit()

        intent?.let { i ->
            if (i.hasExtra(EXTRA_ROT)) {
                cfgRotation = i.getIntExtra(EXTRA_ROT, 0)
                edit.putInt(EXTRA_ROT, cfgRotation)
            } else cfgRotation = prefs.getInt(EXTRA_ROT, 0)

            if (i.hasExtra(EXTRA_ZOOM)) {
                cfgZoom = i.getFloatExtra(EXTRA_ZOOM, 1f)
                edit.putFloat(EXTRA_ZOOM, cfgZoom)
            } else cfgZoom = prefs.getFloat(EXTRA_ZOOM, 1f)

            if (i.hasExtra(EXTRA_CX)) {
                cfgCropCx = i.getFloatExtra(EXTRA_CX, 0.5f)
                edit.putFloat(EXTRA_CX, cfgCropCx)
            } else cfgCropCx = prefs.getFloat(EXTRA_CX, 0.5f)

            if (i.hasExtra(EXTRA_CY)) {
                cfgCropCy = i.getFloatExtra(EXTRA_CY, 0.5f)
                edit.putFloat(EXTRA_CY, cfgCropCy)
            } else cfgCropCy = prefs.getFloat(EXTRA_CY, 0.5f)

            if (i.hasExtra(EXTRA_FLIP_V)) {
                cfgFlipV = i.getBooleanExtra(EXTRA_FLIP_V, false)
                edit.putBoolean(EXTRA_FLIP_V, cfgFlipV)
            } else cfgFlipV = prefs.getBoolean(EXTRA_FLIP_V, false)

            if (i.hasExtra(EXTRA_FLIP_H)) {
                cfgFlipH = i.getBooleanExtra(EXTRA_FLIP_H, false)
                edit.putBoolean(EXTRA_FLIP_H, cfgFlipH)
            } else cfgFlipH = prefs.getBoolean(EXTRA_FLIP_H, false)

            if (i.hasExtra(EXTRA_THRESH)) {
                cfgThreshold = i.getIntExtra(EXTRA_THRESH, 18)
                edit.putInt(EXTRA_THRESH, cfgThreshold)
            } else cfgThreshold = prefs.getInt(EXTRA_THRESH, 18)

            if (i.hasExtra(EXTRA_CAMERA_ID)) {
                edit.putString(EXTRA_CAMERA_ID, i.getStringExtra(EXTRA_CAMERA_ID))
            }
        }
        // Clamp zoom, snap rotation to nearest quadrant
        if (cfgZoom < 1f) cfgZoom = 1f
        cfgRotation = ((cfgRotation % 360) + 360) % 360
        cfgRotation = listOf(0, 90, 180, 270).minByOrNull { kotlin.math.abs(it - cfgRotation) } ?: 0
        edit.apply()
        Log.i(TAG, "config: rot=$cfgRotation zoom=$cfgZoom crop=($cfgCropCx,$cfgCropCy) flipV=$cfgFlipV flipH=$cfgFlipH")
    }

    /** Re-read persisted camera id override from prefs (or intent extra if still present). */
    private fun chosenCameraIdOverride(): String? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return intent?.getStringExtra(EXTRA_CAMERA_ID) ?: prefs.getString(EXTRA_CAMERA_ID, null)
    }

    private fun pickBackCameraId(): String? {
        val cm = cameraManager ?: return null

        // Enumerate EVERY camera first and log interesting properties so we can
        // identify main vs ultrawide vs depth vs macro without guessing.
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXT"
            }
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString(",") ?: "?"
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val hwLvl = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.joinToString(",") ?: ""
            Log.i(TAG, "camera enum id=$id facing=$facing focal=${focals}mm pixelArr=$pixelArray activeArr=$activeArray hwLvl=$hwLvl caps=$caps")
        }

        // Pick camera: honour override from Intent extra / prefs if present and valid,
        // otherwise first back-facing camera.
        val override = chosenCameraIdOverride()
        val chosenId = if (override != null && cm.cameraIdList.contains(override)) {
            override
        } else {
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } ?: return null

        val ch = cm.getCameraCharacteristics(chosenId)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        activeArraySize = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxDigitalZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        Log.i(TAG, "camera characteristics: activeArray=$activeArraySize maxZoom=$maxDigitalZoom")

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            Log.i(TAG, "chosen camera $chosenId YUV sizes: ${yuvSizes?.take(20)?.joinToString(",") { "${it.width}x${it.height}" }}${if ((yuvSizes?.size ?: 0) > 20) "..." else ""}")
        }
        return chosenId
    }

    private fun tryOpenCamera() {
        if (cameraOpening || cameraDevice != null) {
            Log.w(TAG, "tryOpenCamera ignored: already opening/open")
            return
        }
        cameraOpening = true
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { cameraOpening = false; return }
        val cm = cameraManager ?: run { cameraOpening = false; return }
        val id = pickBackCameraId() ?: run { Log.e(TAG, "no camera id"); cameraOpening = false; return }
        cameraId = id
        Log.i(TAG, "opening camera id=$id sensorOrientation=$sensorOrientation hwLevel=$hardwareLevel minFocusDist=$minFocusDistance")

        // --- Output 1: ImageReader for motion detection ---
        imageReader = ImageReader.newInstance(
            CAPTURE_SIZE.width, CAPTURE_SIZE.height, ImageFormat.YUV_420_888, 2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try { onCameraFrame(img) } finally { img.close() }
            }, bgHandler)
        }

        // --- Output 2: TextureView for preview / scrcpy ---
        val st = textureView.surfaceTexture!!
        st.setDefaultBufferSize(CAPTURE_SIZE.width, CAPTURE_SIZE.height)
        previewSurface = Surface(st)

        // --- Output 3: MediaCodec encoder input Surface for ring buffer ---
        val ring = VideoRingBuffer(
            width = CAPTURE_SIZE.width,
            height = CAPTURE_SIZE.height,
            bitRate = VIDEO_BITRATE,
            frameRate = VIDEO_FPS,
            iFrameIntervalSec = I_FRAME_INTERVAL_S,
            prerollSec = PREROLL_SECONDS,
        )
        ring.start()
        ringBuffer = ring
        // orientationHint is how much the player should rotate the stored
        // frames to be upright. Stored frames are in the sensor's natural
        // orientation (landscape). Base hint = rotation between sensor and
        // current display orientation; add user's cfgRotation so saved clips
        // match what the viewer shows.
        val displayRotDeg = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0   -> 0
            Surface.ROTATION_90  -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val orientationHint = (((sensorOrientation - displayRotDeg) + cfgRotation) + 360) % 360
        Log.i(TAG, "orientationHint=$orientationHint (sensor=$sensorOrientation displayRot=$displayRotDeg cfgRot=$cfgRotation)")
        recController = RecordingController(
            context = applicationContext,
            ring = ring,
            holdAfterMotionMs = HOLD_AFTER_MOTION_MS,
            orientationHintDeg = orientationHint,
        ).also { it.listener = this }

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.i(TAG, "CameraDevice.onOpened")
                    cameraDevice = device
                    cameraOpening = false
                    startSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "CameraDevice.onDisconnected"); device.close(); cameraDevice = null; cameraOpening = false
                }
                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "CameraDevice.onError code=$error"); device.close(); cameraDevice = null; cameraOpening = false
                }
            }, bgHandler)
            Log.i(TAG, "openCamera() returned, awaiting callback")
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed", t)
            cameraOpening = false
        }
    }

    /**
     * Set SCALER_CROP_REGION for hardware digital zoom. cfgZoom=1.0 → no crop.
     * cfgZoom=2.0 → center crop half the sensor in each dimension (4x pixel
     * zoom). Crop center is controlled by (cfgCropCx, cfgCropCy) as fractions
     * of the active array — (0.5, 0.5) is centered.
     */
    private fun applyCropRegion(builder: CaptureRequest.Builder) {
        val active = activeArraySize ?: return
        val zoom = cfgZoom.coerceIn(1f, maxOf(1f, maxDigitalZoom))
        if (zoom <= 1.0001f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, active)
            Log.i(TAG, "crop region: full sensor ($active), zoom=1.0")
            return
        }
        val cropW = (active.width() / zoom).toInt()
        val cropH = (active.height() / zoom).toInt()
        val cx = (active.left + active.width() * cfgCropCx).toInt()
        val cy = (active.top + active.height() * cfgCropCy).toInt()
        val halfW = cropW / 2
        val halfH = cropH / 2
        val clampedCx = cx.coerceIn(active.left + halfW, active.right - halfW)
        val clampedCy = cy.coerceIn(active.top + halfH, active.bottom - halfH)
        val rect = Rect(
            clampedCx - halfW,
            clampedCy - halfH,
            clampedCx + halfW,
            clampedCy + halfH
        )
        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
        Log.i(TAG, "crop region: $rect (zoom=$zoom, center=($cfgCropCx,$cfgCropCy), maxZoom=$maxDigitalZoom)")
    }

    private fun startSession() {
        Log.i(TAG, "startSession() entered")
        val device = cameraDevice ?: run { Log.w(TAG, "startSession: cameraDevice null"); return }
        val preview = previewSurface ?: run { Log.w(TAG, "startSession: previewSurface null"); return }
        val reader = imageReader ?: run { Log.w(TAG, "startSession: imageReader null"); return }
        val encSurface = ringBuffer?.getInputSurface() ?: run { Log.w(TAG, "startSession: encSurface null"); return }

        val surfaces = listOf(preview, reader.surface, encSurface)
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(preview)
            addTarget(reader.surface)
            addTarget(encSurface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            // Force focus to infinity (0 diopters in Camera2). The phone has a
            // fish-eye clip-on lens directly in front of the back camera, and
            // CONTROL_AF_MODE_CONTINUOUS_VIDEO would re-lock on the lens glass
            // itself instead of focusing through it to the door scene. Manual
            // focus to infinity makes everything beyond ~1m sharp.
            if (minFocusDistance > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)  // 0.0 diopters = infinity
                Log.i(TAG, "focus: manual infinity (minDist=$minFocusDistance diopters)")
            } else {
                // Fixed-focus camera (rare on phones); nothing to set.
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                Log.i(TAG, "focus: fixed-focus camera (no LENS_FOCUS_DISTANCE control)")
            }

            // Hardware digital zoom via ISP crop region. Sharper than a software
            // crop because the full sensor readout is downsampled into the
            // requested output size — effectively zoom + recenter without any
            // CPU work on our side. Used here to crop out the fish-eye's dark
            // vignette so the bright circle fills the output buffer.
            applyCropRegion(this)

            // Hint target fps so AE doesn't ramp way above our encoder.
            set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(VIDEO_FPS, VIDEO_FPS)
            )
        }
        Log.i(TAG, "startSession: createCaptureSession with ${surfaces.size} surfaces")

        @Suppress("DEPRECATION")
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    session.setRepeatingRequest(builder.build(), null, bgHandler)
                    runOnUiThread { status.text = "armed ${CAPTURE_SIZE.width}x${CAPTURE_SIZE.height}" }
                    Log.i(TAG, "3-surface session armed")
                } catch (t: Throwable) { Log.e(TAG, "setRepeatingRequest failed", t) }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "session configure failed — likely hw-level stream combo issue")
                runOnUiThread { status.text = "session FAILED" }
            }
        }, bgHandler)
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        previewSurface?.release()
        previewSurface = null
    }

    // ------------------------------------------------------------------
    // Per-frame work (YUV path)
    // ------------------------------------------------------------------

    private fun onCameraFrame(img: Image) {
        val c = ++frameCount
        motionDetector.feed(img)
        // Expose the CURRENT score (not just the last triggered score) so the
        // status bar shows live ambient noise, useful for threshold tuning.
        lastMotionScore = motionDetector.lastScore
        if (c % 20L == 0L) {
            val text = buildStatusText()
            runOnUiThread { status.text = text }
        }
        if (c % 40L == 0L) {
            Log.d(TAG, "motion score=${motionDetector.lastScore} (threshold=${motionDetector.threshold}) frame=$c")
        }
    }

    private fun buildStatusText(): String {
        val rec = recController?.state ?: "-"
        val bat = if (batteryPct >= 0) {
            "${batteryPct}%${if (batteryCharging) "⚡" else ""}"
        } else "?"
        val lastMotion = if (lastMotionEndRealtimeMs == 0L) {
            "last: none"
        } else {
            val ageMs = SystemClock.elapsedRealtime() - lastMotionEndRealtimeMs
            "last: ${formatAge(ageMs)}"
        }
        val motion = "motion=$lastMotionScore"
        val flip = buildString {
            if (cfgFlipV) append("V")
            if (cfgFlipH) append("H")
        }
        val cfg = "rot${cfgRotation} z${"%.1f".format(cfgZoom)}${if (flip.isNotEmpty()) " flip$flip" else ""} t$cfgThreshold cam${cameraId ?: "?"}"
        return "bat $bat  |  $lastMotion  |  $motion  |  $rec  |  $cfg  |  f$frameCount"
    }

    private fun formatAge(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60     -> "${s}s ago"
            s < 3600   -> "${s / 60}m${(s % 60).toString().padStart(2,'0')}s ago"
            s < 86400  -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2,'0')}m ago"
            else       -> "${s / 86400}d ago"
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                batteryCharging = plugged != 0
            }
        }
        batteryReceiver = r
        registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        batteryReceiver = null
    }

    // ------------------------------------------------------------------
    // MotionDetector.Listener
    // ------------------------------------------------------------------

    override fun onMotionStart(score: Int) {
        lastMotionScore = score
        recController?.onMotionStart()
    }
    override fun onMotionActive(score: Int) {
        lastMotionScore = score
    }
    override fun onMotionEnd() {
        lastMotionEndRealtimeMs = SystemClock.elapsedRealtime()
        recController?.onMotionEnd()
    }

    // ------------------------------------------------------------------
    // RecordingController.Listener — only used to update UI
    // ------------------------------------------------------------------

    override fun onRecordingStarted(file: File, trigger: String) {
        runOnUiThread {
            recBtn.text = "■ STOP"
            status.text = "REC ($trigger) ${file.name}"
        }
    }
    override fun onRecordingStopped(file: File) {
        runOnUiThread {
            recBtn.text = "● REC"
            status.text = "saved ${file.name} ${file.length()/1024}KB"
        }
    }

    // ------------------------------------------------------------------
    // Manual REC/STOP button
    // ------------------------------------------------------------------

    private fun onRecBtn() {
        val ctrl = recController ?: return
        when (ctrl.state) {
            RecordingController.State.IDLE -> ctrl.forceStart()
            RecordingController.State.RECORDING,
            RecordingController.State.HOLDOFF -> ctrl.forceStop()
        }
    }

    /** Cycle rotation 0 → 90 → 180 → 270 → 0, persist, reapply transform. */
    private fun onRotBtn() {
        cfgRotation = (cfgRotation + 90) % 360
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(EXTRA_ROT, cfgRotation)
            .apply()
        configureTransform(textureView.width, textureView.height)
        updateAdjustButtonLabels()
        Log.i(TAG, "ROT button → $cfgRotation°")
    }

    /** Toggle vertical flip, persist, reapply transform. */
    private fun onFlipBtn() {
        cfgFlipV = !cfgFlipV
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(EXTRA_FLIP_V, cfgFlipV)
            .apply()
        configureTransform(textureView.width, textureView.height)
        updateAdjustButtonLabels()
        Log.i(TAG, "FLIP button → V=$cfgFlipV")
    }

    private fun updateAdjustButtonLabels() {
        rotBtn.text = "↻ ${cfgRotation}°"
        flipBtn.text = if (cfgFlipV) "↕ FLIP ✓" else "↕ FLIP"
    }
}
