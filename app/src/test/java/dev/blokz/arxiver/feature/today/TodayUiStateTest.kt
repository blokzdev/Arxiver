package dev.blokz.arxiver.feature.today

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.InboxPaper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the first-load-vs-inbox-zero fix: skeletons during the first sync, not "you're all caught up". */
class TodayUiStateTest {
    private fun item(): InboxPaper =
        InboxPaper(
            paper =
                Paper(
                    ref = ArxivRef(ArxivId("2401.00001")),
                    latestVersion = 1,
                    title = "T",
                    abstract = "a",
                    publishedAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG",
                    categories = listOf("cs.LG"),
                    authors = listOf("A"),
                    fetchedAt = Instant.EPOCH,
                ),
            arrivedAt = Instant.EPOCH,
            state = "new",
            score = null,
        )

    @Test
    fun `first load (syncing, empty, has follows) is loading`() {
        assertTrue(TodayUiState(items = emptyList(), syncing = true, hasFollows = true).loading)
    }

    @Test
    fun `genuine inbox-zero (synced, empty) is not loading`() {
        assertFalse(TodayUiState(items = emptyList(), syncing = false, hasFollows = true).loading)
    }

    @Test
    fun `syncing with items present is not loading`() {
        assertFalse(TodayUiState(items = listOf(item()), syncing = true, hasFollows = true).loading)
    }

    @Test
    fun `no follows is not loading (shows the follow prompt)`() {
        assertFalse(TodayUiState(items = emptyList(), syncing = true, hasFollows = false).loading)
    }
}
