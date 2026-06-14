package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema-discipline guard. Room migrations are a red line (CLAUDE.md: destructive
 * migration forbidden; every version needs a committed schema JSON). This asserts
 * the export is current and that the on-disk DB reopens without data loss. Full
 * per-version migrate-and-validate (MigrationTestHelper) belongs in androidTest and
 * lands with the first real migration.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationHarnessTest {
    private val declaredVersion: Int = ArxiverDatabase.VERSION

    @Test
    fun `schema json is exported for the current version`() {
        val schema = File("schemas/${ArxiverDatabase::class.java.canonicalName}/$declaredVersion.json")
        assertTrue(schema.exists(), "missing committed schema for v$declaredVersion at ${schema.path}")
        assertTrue(
            schema.readText().contains("\"version\": $declaredVersion"),
            "schema JSON version does not match the @Database version",
        )
    }

    @Test
    fun `on-disk database reopens with data intact (no destructive fallback)`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val paper =
                Paper(
                    id = ArxivId("2403.09999"),
                    latestVersion = 1,
                    title = "Persisted",
                    abstract = "a",
                    publishedAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG",
                    categories = listOf("cs.LG"),
                    authors = listOf("A"),
                    fetchedAt = Instant.EPOCH,
                )

            val first = ArxiverDatabase.build(context)
            first.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
            first.close()

            // Reopen the same on-disk database; Room validates the identity hash and
            // build() has no fallbackToDestructiveMigration, so the row must survive.
            val second = ArxiverDatabase.build(context)
            val loaded = assertNotNull(second.paperDao().paperById("2403.09999"))
            assertEquals("Persisted", loaded.title)
            second.close()
        }
}
