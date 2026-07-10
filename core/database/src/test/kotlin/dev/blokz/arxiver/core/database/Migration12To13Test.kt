package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_12_13
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v12 → v13 (P-Explorer PE.1b): `papers.landing_url`. Additive, nullable, no backfill — the value is only
 * obtainable from a fresh OpenAlex response, so existing rows stay NULL and keep their exact prior behavior.
 * `runMigrationsAndValidate` gates the identity hash against the committed `13.json`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate12To13_addsLandingUrl_nullable_andPreservesExistingRows() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL(
                "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, " +
                    "primary_category, authors_line, pdf_url, source, fetched_at, doi, doi_norm) " +
                    "VALUES ('chemrxiv:10.26434/x', 1, 'T', 'A', 0, 0, 'Chemistry', '', 'p.pdf', 'arxiv', 0, " +
                    "'10.26434/X', '10.26434/x')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)

        db.query("SELECT doi, doi_norm, landing_url FROM papers WHERE id = 'chemrxiv:10.26434/x'").use { c ->
            assertEquals(1, c.count, "the pre-existing row survives")
            c.moveToFirst()
            assertEquals("10.26434/X", c.getString(0), "the verbatim DOI is untouched")
            assertEquals("10.26434/x", c.getString(1), "the PE.2 de-dup key is untouched")
            assertTrue(c.isNull(2), "landing_url back-fills NULL — prior link behavior is unchanged")
        }

        // The new column is writable: a DOI-less OSF paper's only link lives here.
        db.execSQL(
            "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, primary_category, " +
                "authors_line, pdf_url, source, fetched_at, landing_url) " +
                "VALUES ('psyarxiv:W7112150394', 1, 'T', 'A', 0, 0, 'Psychology', '', '', 'follow', 0, " +
                "'https://osf.io/szf8y')",
        )
        db.query("SELECT landing_url, doi FROM papers WHERE id = 'psyarxiv:W7112150394'").use { c ->
            c.moveToFirst()
            assertEquals("https://osf.io/szf8y", c.getString(0))
            assertTrue(c.isNull(1), "a DOI-less source stores no DOI, never a synthesized one")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-12-13-test"
    }
}
