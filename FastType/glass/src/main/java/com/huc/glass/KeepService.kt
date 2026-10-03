package com.huc.glass

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

/**
 * Keeps the home screen's process alive.
 *
 * ColorOS reclaims a third-party launcher's memory aggressively, and every time it
 * does, coming home means a cold start. A foreground service raises the process's
 * importance enough that it is no longer the first thing closed. The notification is
 * the price the platform charges for that, so it is set to the quietest channel there
 * is, and the whole thing is optional.
 */
class KeepService : Service() {

    companion object {
        private const val CHANNEL = "keep"
        private const val ID = 7

        fun apply(ctx: Context) {
            val i = Intent(ctx, KeepService::class.java)
            try {
                if (GStore.keepAlive) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                    else ctx.startService(i)
                } else {
                    ctx.stopService(i)
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    val ch = NotificationChannel(
                        CHANNEL, getString(R.string.keep_title),
                        NotificationManager.IMPORTANCE_MIN
                    )
                    ch.setShowBadge(false)
                    ch.enableLights(false)
                    ch.enableVibration(false)
                    nm.createNotificationChannel(ch)
                }
            }
            val n = build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun build(): Notification {
        val open = Intent(this, SettingsActivity::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, open, flags)

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setSmallIcon(R.drawable.ic_keep)
        b.setContentTitle(getString(R.string.app_name))
        b.setContentText(getString(R.string.keep_title))
        b.setContentIntent(pi)
        b.setOngoing(true)
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_SECRET)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GStore.keepAlive) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
}
