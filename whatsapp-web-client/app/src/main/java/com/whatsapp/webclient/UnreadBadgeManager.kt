package com.whatsapp.webclient

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.leolin.shortcutbadger.ShortcutBadger

/**
 * Mengelola badge launcher (ala WhatsApp native) yang menampilkan jumlah
 * chat belum terbaca di ikon aplikasi.
 *
 * Strategi ganda untuk kompatibilitas lintas launcher:
 *  - ShortcutBadger: menulis badge langsung untuk launcher yang mendukung
 *    (Samsung, MIUI, OPPO, Vivo, dst) tanpa butuh notifikasi terlihat.
 *  - Notifikasi dengan setNumber(): mekanisme native Android 8+
 *    untuk launcher yang membaca badge dari notifikasi (Pixel/stock, One UI).
 */
object UnreadBadgeManager {

    private const val TAG = "BadgeHook"
    private const val CHANNEL_ID = "unread_badge"
    private const val NOTIFICATION_ID = 9001

    /** Nilai terakhir yang diproses (guard anti-redundan). */
    private var lastCount = -1

    /** Nilai terakhir yang dilaporkan halaman web, untuk re-apply setelah izin/launch. */
    private var knownCount = -1

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DEFAULT (bukan LOW): sebagian OEM menyembunyikan notifikasi senyap/LOW,
            // padahal notifikasi ini dipakai sebagai sumber badge launcher.
            // Suara/getar dimatikan di level channel.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.badge_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.badge_channel_desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /** Dipanggil dari JS bridge setiap kali jumlah chat unread berubah. */
    fun update(context: Context, count: Int) {
        knownCount = count
        if (count == lastCount) return
        lastCount = count
        Log.d(TAG, "update count=$count")
        if (count > 0) {
            applyBadge(context, count)
            showNotification(context, count)
        } else {
            clearBadge(context)
        }
    }

    /**
     * Izin POST_NOTIFICATIONS baru saja di-grant. Update sebelum grant tidak
     * memunculkan notifikasi (guard juga belum diset untuk count itu), jadi
     * proses ulang angka terakhir agar notifikasi tampil.
     */
    fun onPermissionGranted(context: Context) {
        Log.d(TAG, "izin notifikasi granted, re-apply count=$knownCount")
        reapply(context)
    }

    /** Dipanggil dari MainActivity.onResume: repost jika notifikasi sempat di-dismiss. */
    fun refresh(context: Context) {
        reapply(context)
    }

    private fun reapply(context: Context) {
        val count = knownCount
        if (count <= 0) return
        lastCount = -1
        update(context, count)
    }

    private fun clearBadge(context: Context) {
        try {
            ShortcutBadger.removeCount(context.applicationContext)
        } catch (_: Exception) {
            // launcher tidak mendukung ShortcutBadger; abaikan
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun applyBadge(context: Context, count: Int) {
        try {
            ShortcutBadger.applyCount(context.applicationContext, count)
        } catch (_: Exception) {
            // fallback ke notifikasi setNumber di bawah
        }
    }

    private fun showNotification(context: Context, count: Int) {
        // Android 13+: tanpa izin, notifikasi tidak akan muncul.
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.resources.getQuantityString(R.plurals.unread_messages, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.badge_open_hint))
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Minta izin notifikasi (API 33+) sekali di awal, penting untuk badge. */
    fun requestNotificationPermissionIfNeeded(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<String>
    ) {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(activity)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
