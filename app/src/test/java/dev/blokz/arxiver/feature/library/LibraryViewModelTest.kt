package dev.blokz.arxiver.feature.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.LibraryExporter
import dev.blokz.arxiver.data.LibraryRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var vm: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        repo = LibraryRepository(db.libraryDao(), db.inboxDao())
        vm = LibraryViewModel(repo, LibraryExporter(db.libraryDao(), db.paperDao()))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun savePaper(
        id: String,
        status: String,
    ) {
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
        db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = id, addedAt = 0, status = status))
    }

    @Test
    fun `status filter narrows the library list`() =
        runTest {
            savePaper("2401.00001", LibraryEntryEntity.STATUS_READING)
            savePaper("2401.00002", LibraryEntryEntity.STATUS_TO_READ)

            vm.uiState.test {
                // Drain to the fully-populated emission.
                awaitItem()
                val populated = awaitItemMatching { it.papers.size == 2 }
                assertEquals(2, populated.papers.size)

                vm.setStatusFilter(LibraryEntryEntity.STATUS_READING)
                val filtered = awaitItemMatching { it.statusFilter == LibraryEntryEntity.STATUS_READING }
                assertEquals(listOf("2401.00001"), filtered.papers.map { it.paper.id.value })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `createCollection ignores blank names`() =
        runTest {
            vm.createCollection("   ")
            assertTrue(repo.observeCollections().first().isEmpty())

            // createCollection launches on viewModelScope; await the insert's emission
            // rather than reading immediately (the Room write completes asynchronously).
            vm.createCollection("Reading")
            assertEquals(
                listOf("Reading"),
                repo.observeCollections().first { it.isNotEmpty() }.map { it.name },
            )
        }

    @Test
    fun `deleting a collection captures its membership for undo and restoring recreates it`() =
        runTest {
            savePaper("2401.00001", LibraryEntryEntity.STATUS_TO_READ)
            val id = repo.createCollection("Temp")
            repo.addToCollection(id, "2401.00001")

            vm.deleteCollection(id, "Temp")

            val event = assertNotNull(vm.collectionDeleted.first { it != null })
            assertEquals("Temp", event.name)
            assertEquals(listOf("2401.00001"), event.memberIds)
            assertTrue(repo.observeCollections().first().isEmpty())

            // undo recreates the collection asynchronously; await the re-creation and
            // its restored membership rather than reading immediately.
            vm.undoDeleteCollection(event)
            val restored = repo.observeCollections().first { it.isNotEmpty() }
            assertEquals(listOf("Temp"), restored.map { it.name })
            assertEquals(
                listOf("2401.00001"),
                repo.observeCollectionPapers(restored.single().id).first { it.isNotEmpty() }
                    .map { it.paper.id.value },
            )
        }
}

/** Awaits successive emissions until [predicate] holds, returning that item. */
private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
