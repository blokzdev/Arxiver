package dev.blokz.arxiver.core.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Egress-gate bypass guard (P-FullText checkpoint). jsoup is a `:core:ai`-only dependency; the full-text
 * body extractor ([HtmlBodyTextExtractor]) and the sanitizer must only ever **parse already-downloaded,
 * in-memory strings** (`Jsoup.parse(...)`). jsoup's OWN network API — `Jsoup.connect(...)` /
 * `Jsoup.newSession(...)` — uses jsoup's HTTP stack, which would **bypass the app's `AllowedHosts` egress
 * gate and the arXiv rate limiter entirely**. This structural test fails the build if any `:core:ai` code
 * introduces it, so a future edit can't quietly open an ungoverned egress path. (Sibling of
 * [HtmlFetcherNoBypassStructuralTest]; all real network in the app goes through the gated OkHttp clients.)
 */
class JsoupNoNetworkStructuralTest {
    @Test
    fun `no core-ai code uses jsoup's network API`() {
        val root = File("src/main/kotlin/dev/blokz/arxiver/core/ai")
        assertTrue(root.isDirectory, "must run from the :core:ai module dir; cwd=${File("").absolutePath}")

        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { f ->
                    val text = f.readText()
                    text.contains("Jsoup.connect") || text.contains(".newSession(")
                }
                .map { it.name }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "jsoup's own HTTP stack bypasses the AllowedHosts egress gate — parse in-memory strings only. " +
                "Offenders: $offenders",
        )
    }
}
