package dev.blokz.arxiver.core.pdf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Egress-gate bypass guard for Phase P-Reader2 (sibling of `:core:ai`'s `JsoupNoNetworkStructuralTest`).
 *
 * `pdfbox-android` runs on `java.net`, **not** the app's gated `@ArxivClient` OkHttp stack — so any network
 * call it (or this module) made would **bypass `AllowedHostsInterceptor` and the arXiv rate limiter entirely**.
 * The whole point of `:core:pdf` is to extract text from an **already-downloaded local `File`** and nothing
 * else. This structural test fails the build if any `:core:pdf` source introduces a network token, so a future
 * edit can't quietly open an ungoverned egress path (e.g. `PDDocument.load(URL)` / `url.openStream()`).
 *
 * It is a **source tripwire, not a runtime proof** — the full "zero sockets" guarantee also rests on the FULL
 * local-asset pdfbox bundle (no font egress), `PDDocument.load(File)` only, and the device packet-inspection
 * row in `VERIFICATION.md`. All three are stated honestly in the P-Reader2 plan.
 */
class PdfboxNoNetworkStructuralTest {
    @Test
    fun `no core-pdf code uses a network API`() {
        val root = File("src/main/kotlin/dev/blokz/arxiver/core/pdf")
        assertTrue(root.isDirectory, "must run from the :core:pdf module dir; cwd=${File("").absolutePath}")

        val forbidden =
            listOf("java.net", "URLConnection", "Socket(", ".openStream(", ".openConnection(", "okhttp", "URL(")

        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { f -> forbidden.any { token -> f.readText().contains(token) } }
                .map { it.name }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "pdfbox / :core:pdf must never open a socket — it only ever reads an already-downloaded local " +
                "File. A network token would bypass the AllowedHosts egress gate + the arXiv rate limiter. " +
                "Offenders: $offenders",
        )
    }
}
