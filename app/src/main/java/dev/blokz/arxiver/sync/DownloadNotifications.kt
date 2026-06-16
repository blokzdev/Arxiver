package dev.blokz.arxiver.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the **local-only** progress notification that promotes the long on-device model download to a
 * foreground service (UX2.8) so it survives backgrounding/Doze. Posts nothing over the network — same
 * posture as the local crash reporter; honors the no-telemetry red line. `POST_NOTIFICATIONS` is a
 * runtime grant on Android 13+; a denial degrades gracefully (the service still runs, the notification
 * is simply suppressed).
 */
@Singleton
class DownloadNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private fun ensureChannel() {
            // minSdk 26 — the channel always exists.
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.bg_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.bg_notification_channel_desc) }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        fun foregroundInfo(
            @StringRes titleRes: Int,
            progressPercent: Int,
        ): ForegroundInfo {
            ensureChannel()
            val notification = build(titleRes, progressPercent)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        /** Push a progress update to the ongoing notification; no-op if the user denied notifications. */
        fun updateProgress(
            @StringRes titleRes: Int,
            progressPercent: Int,
        ) {
            // Inline guard so lint sees the permission check (POST_NOTIFICATIONS is a 13+ runtime grant).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(titleRes, progressPercent))
        }

        private fun build(
            @StringRes titleRes: Int,
            progressPercent: Int,
        ): Notification {
            val clamped = progressPercent.coerceIn(0, 100)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(titleRes))
                .setContentText("$clamped%")
                .setOngoing(true)
                .setProgress(100, clamped, clamped <= 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        companion object {
            const val CHANNEL_ID = "arxiver_background"
            const val NOTIFICATION_ID = 4201
        }
    }
