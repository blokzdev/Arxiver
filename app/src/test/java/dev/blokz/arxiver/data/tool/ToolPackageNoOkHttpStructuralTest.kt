package dev.blokz.arxiver.data.tool

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard (SPEC-P-TOOLS red lines): the `data/tool/` executor package must NEVER reach the
 * network directly — every external tool routes through `PaperRepository` → `ArxivApiClient`, which
 * `acquire()`s the shared ≥3s arXiv limiter on the AllowedHosts-gated `@ArxivClient` before any socket.
 * A tool that constructed its own `OkHttpClient`/`Request` or called `.newCall(...)` would bypass BOTH
 * the rate limiter and the egress allowlist — the mirror of the `PdfDownloader` bypass P-HTML fixed.
 * This asserts the registry stays client-free by construction, not just by review.
 */
class ToolPackageNoOkHttpStructuralTest {
    @Test
    fun `no tool source imports okhttp, builds a client, or calls newCall`() {
        val root = File("src/main/java/dev/blokz/arxiver/data/tool")
        assertTrue(root.isDirectory, "must run from the :app module dir; cwd=${File("").absolutePath}")

        // Match real usage, not prose: an `import okhttp3.*` (you cannot use OkHttp without importing it)
        // or a `.newCall(` dispatch. Scanning bare tokens would false-positive on doc comments.
        val forbidden = listOf("import okhttp3", ".newCall(")
        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .mapNotNull { f ->
                    val text = f.readText()
                    val hits = forbidden.filter { text.contains(it) }
                    if (hits.isEmpty()) null else "${f.name}: $hits"
                }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "data/tool/ must stay HTTP-client-free (route external fetches through PaperRepository): $offenders",
        )
    }
}
