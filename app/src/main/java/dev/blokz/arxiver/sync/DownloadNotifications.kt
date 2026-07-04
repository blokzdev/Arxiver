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
            notificationId: Int,
        ): ForegroundInfo {
            ensureChannel()
            val notification = build(titleRes, progressPercent)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(notificationId, notification)
            }
        }

        /** Push a progress update to the ongoing notification; no-op if the user denied notifications. */
        fun updateProgress(
            @StringRes titleRes: Int,
            progressPercent: Int,
            notificationId: Int,
        ) {
            // Inline guard so lint sees the permission check (POST_NOTIFICATIONS is a 13+ runtime grant).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            NotificationManagerCompat.from(context).notify(notificationId, build(titleRes, progressPercent))
        }

        /**
         * Terminal "model ready" notification (PA.6 follow-up): the reporting user had ZERO completion
         * signal after a 614 MB download. Non-ongoing + auto-cancel, its own id (distinct from the
         * progress ids so a concurrent sibling download's progress isn't clobbered), same local-only
         * channel. Posted by the worker after a verified download, before Result.success(), so it
         * survives the foreground teardown.
         */
        fun notifyCompleted(
            @StringRes modelNameRes: Int,
            notificationId: Int,
        ) {
            // Inline guard so lint sees the permission check (POST_NOTIFICATIONS is a 13+ runtime grant).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannel()
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(
                        context.getString(R.string.ai_ondevice_ready_notification, context.getString(modelNameRes)),
                    )
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
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

            // Per-model ids — concurrent Gemma + Qwen downloads are a real path (separate unique
            // works; both buttons on one settings visit). A single shared id made the two progress
            // notifications clobber each other. Explicit constants, NOT derived from the title —
            // deriving would recreate the hand-enumerated-seam bug class this cluster fixes.
            const val GEMMA_NOTIFICATION_ID = 4201
            const val LIGHT_NOTIFICATION_ID = 4202
            const val GEMMA_DONE_NOTIFICATION_ID = 4203
            const val LIGHT_DONE_NOTIFICATION_ID = 4204
        }
    }
