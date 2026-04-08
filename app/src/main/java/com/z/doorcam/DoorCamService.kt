package com.z.doorcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Minimal foreground service whose only job is to elevate this app's process
 * to PROCESS_STATE_FOREGROUND_SERVICE so ViewerActivity can open the camera
 * even when the physical display is blanked via scrcpy. Without this, Android
 * 10's camera-background restriction ("cannot open camera from background
 * (calling UID ... proc state 21)") rejects Camera2.openCamera() whenever the
 * activity is not "visible" in the window manager sense.
 *
 * The service owns no state. The camera pipeline lives in ViewerActivity;
 * the service just holds a sticky foreground notification.
 */
class DoorCamService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val notif = buildNotification(this)
        startForeground(NOTIF_ID, notif)
        Log.i(TAG, "foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoorCam.Svc"
        const val CHANNEL_ID = "doorcam_fg"
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, DoorCamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DoorCamService::class.java))
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "DoorCam", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "DoorCam foreground state"
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        private fun buildNotification(ctx: Context): Notification {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            // IMPORTANCE_LOW channel is already silent — no need for setSilent().
            return builder
                .setContentTitle("DoorCam")
                .setContentText("watching the door")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }
}
