package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PA.5c collection-graph feeder. Verified against a real in-memory Room DB (the established repo-test
 * pattern) — the pure scene/layout/clustering it delegates to is already golden-tested in :core:search,
 * so this covers the DAO orchestration + the typed empty causes + the centrality cap.
 */
@RunWith(RobolectricTestRunner::class)
class CollectionGraphRepositoryTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: CollectionGraphRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        repo =
            CollectionGraphRepository(
                db.libraryDao(),
                db.paperDao(),
                db.embeddingDao(),
                db.citationDao(),
                DefaultDispatcherProvider(),
            )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPaper(id: String) {
        val paper =
            Paper(
                id = ArxivId(id),
                latestVersion = 1,
                title = "Paper $id",
                abstract = "abstract",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    private suspend fun collectionOf(vararg ids: String): Long {
        val cid = db.libraryDao().createCollection(CollectionEntity(name = "C", createdAt = 0))
        ids.forEachIndexed { i, id ->
            seedPaper(id)
            db.libraryDao().addToCollection(CollectionPaperCrossRef(cid, id, addedAt = i.toLong()))
        }
        return cid
    }

    @Test
    fun `an unknown or empty collection yields NoPapers`() =
        runTest {
            assertIs<CollectionGraphResult.NoPapers>(repo.sceneForCollection(404L))
            assertIs<CollectionGraphResult.NoPapers>(repo.sceneForCollection(collectionOf()))
        }

    @Test
    fun `members with no edges among them yields NoRelations`() =
        runTest {
            val cid = collectionOf("2403.00001", "2403.00002", "2403.00003")
            assertIs<CollectionGraphResult.NoRelations>(repo.sceneForCollection(cid))
        }

    @Test
    fun `citation edges among members produce a Ready scene`() =
        runTest {
            val cid = collectionOf("2403.00001", "2403.00002", "2403.00003")
            db.citationDao().insertEdges(
                listOf(
                    CitationEdgeEntity(citingId = "2403.00001", citedId = "2403.00002", fetchedAt = 0),
                    CitationEdgeEntity(citingId = "2403.00002", citedId = "2403.00003", fetchedAt = 0),
                ),
            )
            val result = repo.sceneForCollection(cid)
            assertIs<CollectionGraphResult.Ready>(result)
            assertEquals(3, result.scene.nodes.size)
            assertEquals(2, result.scene.edges.size)
            assertTrue(result.scene.nodes.all { it.inLibrary }, "collection members are library papers")
        }

    @Test
    fun `an edge to a non-member is ignored (edgesAmong scopes to the collection)`() =
        runTest {
            val cid = collectionOf("2403.00001", "2403.00002")
            seedPaper("2403.09999") // exists but not in the collection
            db.citationDao().insertEdges(
                listOf(
                    CitationEdgeEntity(citingId = "2403.00001", citedId = "2403.00002", fetchedAt = 0),
                    CitationEdgeEntity(citingId = "2403.00001", citedId = "2403.09999", fetchedAt = 0),
                ),
            )
            val result = repo.sceneForCollection(cid)
            assertIs<CollectionGraphResult.Ready>(result)
            assertEquals(2, result.scene.nodes.size, "only the two members are nodes")
            assertEquals(1, result.scene.edges.size, "only the in-collection edge survives")
        }

    @Test
    fun `capByCentrality keeps the most-connected papers, ties broken by id`() {
        val ids = listOf("a", "b", "c", "d")
        val edges =
            listOf(
                RelationEdge("a", "b", RelationEdgeKind.CITES),
                RelationEdge("a", "c", RelationEdgeKind.CITES),
                RelationEdge("a", "d", RelationEdgeKind.CITES),
                RelationEdge("b", "c", RelationEdgeKind.CITES),
            )
        // degrees: a=3, b=2, c=2, d=1 → top 2 = a, then b (b<c on the degree-2 tie).
        assertEquals(listOf("a", "b"), CollectionGraphRepository.capByCentrality(ids, edges, 2))
        assertEquals(ids, CollectionGraphRepository.capByCentrality(ids, edges, 10), "no cap when under the limit")
    }
}
