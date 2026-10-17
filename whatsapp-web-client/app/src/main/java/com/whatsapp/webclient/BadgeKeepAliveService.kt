package com.whatsapp.webclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service yang menjaga WebView (off-screen) tetap hidup sehingga
 * badge_hook.js dan listener folder_unread WhatsApp Web tetap berjalan walau
 * Activity di-background. Unread count dari bridge diteruskan ke
 * UnreadBadgeManager untuk badge launcher + notifikasi native.
 *
 * Trade-off yang melekat pada pendekatan ini:
 *  - Ada notifikasi service persisten (syarat OS).
 *  - Baterai lebih boros: WebView + polling MTProto nyala terus.
 *  - OEM agresif bisa mematikan proses background; tidak ada jaminan.
 *  - Tidak bisa menampilkan notif sama sekali bila proses sudah mati total.
 */
class BadgeKeepAliveService : Service() {

    companion object {
        private const val TAG = "BadgeHook"
        private const val CHANNEL_ID = "badge_keep_alive"
        private const val NOTIFICATION_ID = 9002

        fun start(context: Context) {
            val intent = Intent(context, BadgeKeepAliveService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "gagal start service: $e")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BadgeKeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        Log.d(TAG, "BadgeKeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Jika Activity sudah mati (app di-close dari recent), tidak ada WebView
        // yang bisa di-poll. Stop diri sendiri agar tidak jadi zombie.
        if (!MainActivity.isAlive) {
            Log.d(TAG, "Activity mati, service stop diri")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BadgeKeepAliveService destroyed")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.keep_alive_channel_desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startInForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.keep_alive_channel_name))
            .setContentText(getString(R.string.keep_alive_channel_desc))
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
