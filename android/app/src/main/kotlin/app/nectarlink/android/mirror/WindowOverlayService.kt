// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.R

/**
 * Keeps the app alive, with a notification you can stop it from, while PC windows are
 * shown over other apps. The overlays themselves belong to [WindowOverlays].
 */
class WindowOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as NectarlinkApplication).core.windowOverlays.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure {
            // Not allowed to start from here: the overlays are still shown while the app runs.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, WindowOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.window_overlay_notification))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "window_overlay"
        private const val NOTIFICATION_ID = 7311
        private const val ACTION_STOP = "app.nectarlink.android.mirror.STOP_WINDOWS"

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_window_overlay), NotificationManager.IMPORTANCE_LOW),
            )
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WindowOverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WindowOverlayService::class.java)) }
        }
    }
}
