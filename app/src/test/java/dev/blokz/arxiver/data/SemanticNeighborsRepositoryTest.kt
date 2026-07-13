package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PD.2 live-cosine fallback that makes "more like this" work for any embedded paper, not just library papers. */
@RunWith(RobolectricTestRunner::class)
class SemanticNeighborsRepositoryTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: SemanticNeighborsRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repo = SemanticNeighborsRepository(db.paperDao(), db.embeddingDao(), dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /** Insert a paper (title = id); with [vector] it's L2-normalized-by-caller and embedded, else it has no embedding. */
    private suspend fun paper(
        id: String,
        vector: FloatArray?,
    ) {
        db.paperDao().upsertPaper(paperEntity(id))
        if (vector != null) {
            db.embeddingDao().upsert(
                PaperEmbeddingEntity(
                    paperId = id,
                    vector = PaperEmbeddingEntity.floatsToBlob(vector),
                    model = "test",
                    dim = vector.size,
                ),
            )
        }
    }

    @Test
    fun `live scan returns neighbors above the floor and excludes the far one`() =
        runBlocking {
            paper("2401.00001", floatArrayOf(1f, 0f, 0f, 0f))
            paper("2401.00002", floatArrayOf(0.8f, 0.6f, 0f, 0f)) // cosine 0.8 >= 0.3
            paper("2401.00003", floatArrayOf(0f, 0f, 1f, 0f)) // cosine 0.0 < 0.3

            val result = repo.liveNeighborsFor("2401.00001")

            assertTrue(result is NeighborsResult.Ready, "expected Ready, was $result")
            val neighbors = (result as NeighborsResult.Ready).neighbors
            assertEquals(
                listOf("2401.00002"),
                neighbors.map { it.paper.title },
                "only the above-floor paper, self excluded",
            )
            assertTrue(neighbors.single().similarity in 0.79..0.81, "cosine ≈ 0.8")
        }

    @Test
    fun `a paper with no embedding is NotEmbedded`() =
        runBlocking {
            paper("2401.00009", vector = null)
            assertEquals(NeighborsResult.NotEmbedded, repo.liveNeighborsFor("2401.00009"))
        }

    @Test
    fun `an embedded paper with nothing above the floor is NoRelations`() =
        runBlocking {
            paper("2401.00001", floatArrayOf(1f, 0f, 0f, 0f))
            paper("2401.00003", floatArrayOf(0f, 0f, 1f, 0f)) // orthogonal, cosine 0 < 0.3
            assertEquals(NeighborsResult.NoRelations, repo.liveNeighborsFor("2401.00001"))
        }

    private fun paperEntity(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
            primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = null,
            pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
            embeddedAt = 0, citationsSyncedAt = null,
        )
}
