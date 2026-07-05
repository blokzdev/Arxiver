package dev.blokz.arxiver.feature.organize

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
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
class OrganizeViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var vm: OrganizeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        repo = LibraryRepository(db.libraryDao(), db.inboxDao())
        vm = OrganizeViewModel(repo)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun addPaper(id: String) {
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
    fun `collection membership is tri-state across the selection and add fills the gaps`() =
        runTest {
            addPaper("p1")
            addPaper("p2")
            val c = repo.createCollection("Robotics")
            repo.addToCollection(c, "p1") // only one of the two

            vm.start(listOf("p1", "p2"))
            assertEquals(MembershipState.SOME, vm.uiState.first { it.paperCount == 2 }.collections.single().state)

            vm.addCollection(c)
            assertEquals(
                MembershipState.ALL,
                vm.uiState.first {
                    it.collections.single().state == MembershipState.ALL
                }.collections.single().state,
            )

            vm.removeCollection(c)
            assertEquals(
                MembershipState.NONE,
                vm.uiState.first {
                    it.collections.single().state == MembershipState.NONE
                }.collections.single().state,
            )
        }

    @Test
    fun `adding an already-member paper is idempotent (add-only)`() =
        runTest {
            addPaper("p1")
            val c = repo.createCollection("Reading")
            repo.addToCollection(c, "p1")

            vm.start(listOf("p1"))
            vm.uiState.first { it.collections.singleOrNull()?.state == MembershipState.ALL }

            vm.addCollection(c) // no-op, must not duplicate
            assertEquals(1, repo.observeCollectionPapers(c).first().size)
        }

    @Test
    fun `creating a collection adds every selected paper`() =
        runTest {
            addPaper("p1")
            addPaper("p2")

            vm.start(listOf("p1", "p2"))
            vm.uiState.first { it.paperCount == 2 }

            vm.createCollectionWithSelection("New")
            val created = repo.observeCollections().first { it.isNotEmpty() }.single()
            assertEquals(
                setOf("p1", "p2"),
                repo.observeCollectionPapers(created.id).first { it.size == 2 }.map { it.paper.id.value }.toSet(),
            )
        }

    @Test
    fun `tag add applies to all and toggling an ALL tag removes it from all`() =
        runTest {
            addPaper("p1")
            addPaper("p2")

            vm.start(listOf("p1", "p2"))
            vm.uiState.first { it.paperCount == 2 }

            vm.addTag("ml")
            val tag = vm.uiState.first { it.tags.any { t -> t.state == MembershipState.ALL } }.tags.single()
            assertEquals("ml", tag.name)

            vm.addExistingTag(tag) // ALL → remove from all
            assertEquals(
                MembershipState.NONE,
                vm.uiState.first {
                    it.tags.all {
                            t ->
                        t.state == MembershipState.NONE
                    }
                }.tags.single().state,
            )
        }
}
