package dev.blokz.arxiver.core.search.eval

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Zero-egress by construction (P5.1): the eval package must never touch a network primitive — `:core:search`
 * can reach okhttp transitively, so the red line is pinned textually, mirroring the `data/tool` idiom. A
 * ranking eval that phones home is a telemetry violation, not a bug.
 */
class EvalNoOkHttpStructuralTest {
    @Test
    fun `the eval sources contain no network primitive`() {
        val dir = File("src/main/kotlin/dev/blokz/arxiver/core/search/eval")
        val sources = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "eval sources must exist at ${dir.absolutePath}")
        sources.forEach { file ->
            val text = file.readText()
            listOf("import okhttp3", ".newCall(", "HttpURLConnection", "java.net.Socket").forEach { needle ->
                assertTrue(needle !in text, "${file.name} must not contain '$needle'")
            }
        }
    }
}
