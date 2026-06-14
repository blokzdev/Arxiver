package dev.blokz.arxiver.feature.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FilteredPapersViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        repo = LibraryRepository(db.libraryDao(), db.inboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertPaper(id: String) {
        val p =
            Paper(
                id = ArxivId(id),
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
    fun `collection mode streams the collection's papers`() =
        runTest {
            insertPaper("2401.00001")
            val cid = repo.createCollection("C")
            repo.addToCollection(cid, "2401.00001")

            val vm =
                FilteredPapersViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf("mode" to "collection", "id" to cid.toString(), "title" to "C"),
                        ),
                    libraryRepository = repo,
                )

            assertEquals("C", vm.title)
            val rows = vm.papers.first { it != null }!!
            assertEquals(listOf("2401.00001"), rows.map { it.paper.id.value })
        }

    @Test
    fun `tag mode streams the tag's papers`() =
        runTest {
            insertPaper("2401.00002")
            repo.addTag("2401.00002", "ml")
            val tagId = db.libraryDao().tagIdByName("ml")!!

            val vm =
                FilteredPapersViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf("mode" to "tag", "id" to tagId.toString(), "title" to "#ml"),
                        ),
                    libraryRepository = repo,
                )

            val rows = vm.papers.first { it != null }!!
            assertEquals(listOf("2401.00002"), rows.map { it.paper.id.value })
        }
}
