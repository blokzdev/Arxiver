package dev.blokz.arxiver.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the **local-only** ambient digest (P-Ambient PA.1b) — "N new likely-relevant papers" — and reports
 * whether the app may post at all. An interface so the fire-logic ([DigestRunner]) is unit-testable with a fake.
 * Posts nothing over the network (same posture as the crash reporter / download notifications; no-telemetry).
 */
interface DigestNotifier {
    /** True when a notification can actually be shown (POST_NOTIFICATIONS granted, or pre-13). Gate BEFORE marking. */
    fun canPost(): Boolean

    /** Show the digest: [count] newly-likely-relevant papers, with up to a few [titles] for the expanded body. */
    fun notifyDigest(
        count: Int,
        titles: List<String>,
    )
}

@Singleton
class AndroidDigestNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DigestNotifier {
        override fun canPost(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun ensureChannel() {
            // A SEPARATE channel from the IMPORTANCE_LOW download channel so the user can mute the digest
            // independently and it isn't silenced/buried. minSdk 26 — the channel always exists.
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.digest_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.digest_channel_desc) }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        override fun notifyDigest(
            count: Int,
            titles: List<String>,
        ) {
            // Inline guard so lint sees the permission check (POST_NOTIFICATIONS is a 13+ runtime grant); the
            // runner also gates on canPost() upstream so it never marks rows it can't actually notify about.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannel()
            val title = context.resources.getQuantityString(R.plurals.digest_title, count, count)
            // Expanded body: a few titles, then "…and N more" when the count exceeds what we listed.
            val listed = titles.take(count)
            val extra = count - listed.size
            val body =
                buildString {
                    listed.forEach { append("• ").append(it).append('\n') }
                    if (extra > 0) append(context.resources.getQuantityString(R.plurals.digest_more, extra, extra))
                }.trim()

            // Tap → open the app to its start destination (Today). The package launch intent avoids naming
            // MainActivity here; the paper-specific deep-link rides PA.2 (which reuses this notifier's contract).
            val launch =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pending =
                launch?.let {
                    PendingIntent.getActivity(
                        context,
                        0,
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    // Titles must not render to onlookers on the lockscreen.
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            NotificationManagerCompat.from(context).notify(DIGEST_NOTIFICATION_ID, notification)
        }

        companion object {
            const val CHANNEL_ID = "arxiver_digest"

            // Distinct from the download-notification id block (4201-4204).
            const val DIGEST_NOTIFICATION_ID = 4301
        }
    }
