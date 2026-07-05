package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PaperDaoTest {
    private lateinit var db: ArxiverDatabase

    private val paper =
        Paper(
            ref = ArxivRef(ArxivId("2403.01234")),
            latestVersion = 2,
            title = "Attention Is Not All You Need After All",
            abstract = "We revisit the foundations of sequence modeling.",
            publishedAt = Instant.parse("2024-03-02T10:00:00Z"),
            updatedAt = Instant.parse("2024-03-15T10:00:00Z"),
            primaryCategory = "cs.LG",
            categories = listOf("cs.LG", "stat.ML"),
            authors = listOf("A. Researcher", "B. Scholar"),
            fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert and read back round-trips domain model`() =
        runTest {
            db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)

            val loaded = assertNotNull(db.paperDao().paperWithRelations("2403.01234")).toDomain()

            assertEquals(paper.id, loaded.id)
            assertEquals(paper.title, loaded.title)
            assertEquals(paper.authors, loaded.authors)
            assertEquals(setOf("cs.LG", "stat.ML"), loaded.categories.toSet())
            assertEquals("cs.LG", loaded.primaryCategory)
        }

    @Test
    fun `papersByCategory returns category papers newest-first, capped`() =
        runTest {
            val dao = db.paperDao()
            val older =
                paper.copy(ref = ArxivRef(ArxivId("2401.00001")), publishedAt = Instant.parse("2024-01-01T00:00:00Z"))
            val newer =
                paper.copy(ref = ArxivRef(ArxivId("2402.00002")), publishedAt = Instant.parse("2024-02-01T00:00:00Z"))
            val other =
                paper.copy(
                    ref = ArxivRef(ArxivId("2403.00003")),
                    categories = listOf("hep-th"),
                    primaryCategory = "hep-th",
                )
            listOf(older, newer, other).forEach {
                dao.upsertPaperWithRelations(it.toEntity(), it.authors, it.categories)
            }

            val csLg = dao.papersByCategory("cs.LG", limit = 10).map { it.id }
            assertEquals(listOf("2402.00002", "2401.00001"), csLg)
            assertEquals(1, dao.papersByCategory("cs.LG", limit = 1).size)
            assertEquals(listOf("2403.00003"), dao.papersByCategory("hep-th", limit = 10).map { it.id })
        }

    @Test
    fun `re-upsert replaces relations without duplicating authors`() =
        runTest {
            val dao = db.paperDao()
            dao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)

            val revised = paper.copy(authors = listOf("B. Scholar", "C. Newcomer"))
            dao.upsertPaperWithRelations(revised.toEntity(), revised.authors, revised.categories)

            val loaded = assertNotNull(dao.paperWithRelations("2403.01234"))
            assertEquals(listOf("B. Scholar", "C. Newcomer"), loaded.authors)
            assertEquals(1, dao.count())
        }

    @Test
    fun `shared author across papers is stored once`() =
        runTest {
            val dao = db.paperDao()
            dao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
            val second = paper.copy(ref = ArxivRef(ArxivId("2404.05678")), title = "Another One")
            dao.upsertPaperWithRelations(second.toEntity(), second.authors, second.categories)

            // Same name resolves to the same author id in both papers.
            assertEquals(dao.authorNamesFor("2403.01234"), dao.authorNamesFor("2404.05678"))
        }

    @Test
    fun `missing paper returns null`() =
        runTest {
            assertNull(db.paperDao().paperWithRelations("9999.99999"))
        }

    @Test
    fun `taxonomy seeder is idempotent`() =
        runTest {
            val seeder = TaxonomySeeder(db.categoryDao())
            seeder.seed()
            val first = db.categoryDao().count()
            seeder.seed()
            assertEquals(first, db.categoryDao().count())
            assertEquals("Machine Learning", db.categoryDao().byCode("cs.LG")?.name)
        }
}
