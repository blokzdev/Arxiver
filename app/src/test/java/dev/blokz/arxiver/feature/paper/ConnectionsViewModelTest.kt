package dev.blokz.arxiver.feature.paper

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionsViewModelTest {
    private lateinit var db: ArxiverDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun paper(id: String) {
        val p =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = "T $id",
                abstract = "A",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
            )
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
    }

    @Test
    fun `splits references and citations around the focus paper`() =
        runTest {
            listOf("A", "B", "C").forEach { paper("2401.0000$it") }
            // A cites B; C cites A.
            db.citationDao().insertEdges(
                listOf(
                    CitationEdgeEntity(citingId = "2401.0000A", citedId = "2401.0000B", fetchedAt = 0),
                    CitationEdgeEntity(citingId = "2401.0000C", citedId = "2401.0000A", fetchedAt = 0),
                ),
            )
            db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = "2401.0000B", addedAt = 0))

            val vm =
                ConnectionsViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("id" to "2401.0000A")),
                    citationDao = db.citationDao(),
                )

            val state = vm.uiState.first { it.references.isNotEmpty() && it.citations.isNotEmpty() }
            assertEquals(listOf("2401.0000B"), state.references.map { it.paper.id })
            assertTrue(state.references.single().in_library)
            assertEquals(listOf("2401.0000C"), state.citations.map { it.paper.id })
            assertTrue(!state.citations.single().in_library)
        }
}
