package dev.blokz.arxiver.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadNotificationsTest {
    @Test
    fun `foregroundInfo carries the fixed id and registers the local channel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = DownloadNotifications(context)

        val info = notifications.foregroundInfo(R.string.bg_task_gemma_download, progressPercent = 42)

        assertEquals(DownloadNotifications.NOTIFICATION_ID, info.notificationId)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.notificationChannels.any { it.id == DownloadNotifications.CHANNEL_ID })
    }
}
