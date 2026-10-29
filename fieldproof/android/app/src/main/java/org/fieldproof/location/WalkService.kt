package org.fieldproof.location

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.fieldproof.MainActivity
import org.fieldproof.R
import org.fieldproof.geo.WalkPhase
import org.fieldproof.geo.WalkState

/** Keeps GPS recording alive (and the CPU awake) while a boundary is being walked. */
class WalkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val n = build(this, getString(R.string.walk_notification_starting))
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldproof:walk").apply { acquire(3 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "walk"
        private const val NOTIFICATION_ID = 41
        private var lastText = ""

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.walk_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(ctx: Context, text: String): Notification {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_walk)
                .setContentTitle(ctx.getString(R.string.walk_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
        }

        fun update(ctx: Context, s: WalkState) {
            val text = when (s.phase) {
                WalkPhase.PAUSED -> ctx.getString(R.string.walk_notification_paused, s.points.size)
                else -> ctx.getString(R.string.walk_notification_recording, s.points.size, s.walkedM.toInt())
            }
            if (text == lastText) return
            lastText = text
            ensureChannel(ctx)
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(ctx, text)) }
        }
    }
}
