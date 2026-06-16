package dev.blokz.arxiver.sync

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.ml.ModelState
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundTaskMonitorTest {
    @Test
    fun `downloading maps to running with fractional progress`() {
        val task = modelDownloadTask(ModelState.Downloading(40), TaskKind.GEMMA_DOWNLOAD)!!
        assertEquals(TaskKind.GEMMA_DOWNLOAD, task.kind)
        assertEquals(TaskState.Running(0.4f), task.state)
        assertTrue(task.cancellable)
    }

    @Test
    fun `failed maps to a non-cancellable failed task`() {
        val task = modelDownloadTask(ModelState.Failed(AppError.Offline), TaskKind.EMBEDDING_MODEL_DOWNLOAD)!!
        assertEquals(TaskState.Failed, task.state)
        assertTrue(!task.cancellable)
    }

    @Test
    fun `ready and not-downloaded surface nothing`() {
        assertNull(modelDownloadTask(ModelState.Ready(File("m")), TaskKind.GEMMA_DOWNLOAD))
        assertNull(modelDownloadTask(ModelState.NotDownloaded, TaskKind.GEMMA_DOWNLOAD))
    }

    @Test
    fun `a running worker surfaces an indeterminate task, idle surfaces nothing`() {
        val running = runningWorkTask(isRunning = true, TaskKind.FOLLOW_SYNC)!!
        assertEquals(TaskState.Running(null), running.state)
        assertNull(runningWorkTask(isRunning = false, TaskKind.FOLLOW_SYNC))
    }

    @Test
    fun `cancel routing maps each kind to its unique work, bge rides the embedding worker`() {
        assertEquals(FollowSyncWorker.UNIQUE_ONESHOT, uniqueWorkNameFor(TaskKind.FOLLOW_SYNC))
        assertEquals(EmbeddingWorker.UNIQUE_ONESHOT, uniqueWorkNameFor(TaskKind.EMBEDDING))
        assertEquals(EmbeddingWorker.UNIQUE_ONESHOT, uniqueWorkNameFor(TaskKind.EMBEDDING_MODEL_DOWNLOAD))
        assertEquals(OnDeviceModelWorker.UNIQUE_ONESHOT, uniqueWorkNameFor(TaskKind.GEMMA_DOWNLOAD))
    }
}
