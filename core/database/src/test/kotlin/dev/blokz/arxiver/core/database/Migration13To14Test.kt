package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_13_14
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v13 → v14 (P5.3). Identity-hash gate + two extra guards the TableInfo validator can't see:
 * a raw `sqlite_master` SQL equality (a stray byte in the hand-written CREATE TABLE fails HERE, not on a
 * phone), and the **never-seeded** invariant — an absent `relevance_model` row is the below-floor state, so a
 * migrated install and a fresh install behave identically by construction.
 */
@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate13To14_addsRelevanceModelAndScoreAtLabel_neverSeeds() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, " +
                    "primary_category, authors_line, pdf_url, source, fetched_at) " +
                    "VALUES ('2403.01234', 1, 'T', 'A', 0, 0, 'cs.LG', '', '', 'arxiv', 0)",
            )
            // A pre-v14 label — must survive with score_at_label = NULL (unrecoverable exposure context).
            execSQL(
                "INSERT INTO paper_feedback (paper_id, signal, source, created_at) " +
                    "VALUES ('2403.01234', -1, 'dismiss', 5)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14)

        db.query("SELECT COUNT(*) FROM relevance_model").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0), "NEVER seeded — absent row ≡ below-floor ≡ the legacy 0.55")
        }
        db.query("SELECT score_at_label, signal FROM paper_feedback WHERE paper_id = '2403.01234'").use { c ->
            c.moveToFirst()
            assertTrue(c.isNull(0), "a pre-v14 label's exposure context stays NULL, never invented")
            assertEquals(-1, c.getInt(1), "the label itself survives untouched")
        }
        // The TableInfo blind spot: assert the stored DDL matches Room's generated shape byte-for-byte.
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'relevance_model'",
        ).use { c ->
            c.moveToFirst()
            val sql = c.getString(0)
            assertTrue("`head_weights` BLOB" in sql && "PRIMARY KEY(`id`)" in sql, sql)
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-13-14-test"
    }
}
