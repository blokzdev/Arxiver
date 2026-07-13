package dev.blokz.arxiver.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reading positions are personal on-device behavioural data (P-Read): exactly what you read + how far. They
 * must never egress. OS backup is already walled off (`data_extraction_rules.xml` excludes root); this guards
 * the CODE sinks — the backup DTO (BackupManager), the library export + BibTeX (LibraryExporter), and the
 * Claude dispatch payload (DispatchRepository). Every `:app` data-layer file EXCEPT the reading store itself
 * must not reference the reading-position table, so a future edit that wires it into an exporter fails the
 * build. Sibling of JsoupNoNetworkStructuralTest.
 */
class ReadingPositionExportExclusionTest {
    @Test
    fun `no data-layer export or dispatch sink references reading positions`() {
        val root = File("src/main/java/dev/blokz/arxiver/data")
        assertTrue(root.isDirectory, "must run from the :app module dir; cwd=${File("").absolutePath}")

        // The reading store itself legitimately references the table; nothing else in the data layer may.
        val storeFile = "ReadingProgressRepository.kt"
        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != storeFile }
                .filter { f ->
                    val t = f.readText()
                    "reading_positions" in t ||
                        "ReadingPosition" in t ||
                        "ReadingProgressRepository" in t ||
                        "readingPositionDao" in t
                }
                .map { it.name }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "reading positions are on-device-only and must stay out of every export/backup/dispatch sink: $offenders",
        )
    }
}
