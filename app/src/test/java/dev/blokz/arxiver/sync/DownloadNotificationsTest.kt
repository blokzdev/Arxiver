package dev.blokz.arxiver.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = DownloadNotifications(context)

    @Test
    fun `each worker's ForegroundInfo carries its own id and registers the local channel`() {
        val gemma =
            notifications.foregroundInfo(
                R.string.bg_task_gemma_download,
                progressPercent = 42,
                notificationId = DownloadNotifications.GEMMA_NOTIFICATION_ID,
            )
        val light =
            notifications.foregroundInfo(
                R.string.bg_task_light_download,
                progressPercent = 7,
                notificationId = DownloadNotifications.LIGHT_NOTIFICATION_ID,
            )

        assertEquals(DownloadNotifications.GEMMA_NOTIFICATION_ID, gemma.notificationId)
        assertEquals(DownloadNotifications.LIGHT_NOTIFICATION_ID, light.notificationId)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.notificationChannels.any { it.id == DownloadNotifications.CHANNEL_ID })
    }

    @Test
    fun `all four notification ids are pairwise distinct — concurrent downloads never clobber`() {
        val ids =
            listOf(
                DownloadNotifications.GEMMA_NOTIFICATION_ID,
                DownloadNotifications.LIGHT_NOTIFICATION_ID,
                DownloadNotifications.GEMMA_DONE_NOTIFICATION_ID,
                DownloadNotifications.LIGHT_DONE_NOTIFICATION_ID,
            )
        assertEquals(ids.size, ids.toSet().size, "notification ids must be pairwise distinct: $ids")
        // The two PROGRESS ids especially: a shared id made Gemma + Qwen progress clobber each other.
        assertNotEquals(
            DownloadNotifications.GEMMA_NOTIFICATION_ID,
            DownloadNotifications.LIGHT_NOTIFICATION_ID,
        )
    }

    @Test
    fun `completion notification posts as non-ongoing on its own id`() {
        // POST_NOTIFICATIONS is a 13+ runtime grant; the guard silently no-ops without it.
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifications.notifyCompleted(
            modelNameRes = R.string.ai_engine_light,
            notificationId = DownloadNotifications.LIGHT_DONE_NOTIFICATION_ID,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.single { it.id == DownloadNotifications.LIGHT_DONE_NOTIFICATION_ID }
        assertTrue(posted.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
        assertTrue(posted.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }
}
