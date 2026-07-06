package dev.blokz.arxiver.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R6 red-line guard (SPEC-P-SOURCES §7): the `pdfDownloader` DI provider MUST pass the INJECTED
 * `ArxivRateLimiter` singleton (the one Atom/HTML also receive) into `PdfHostPolicy.arxivLimiter` — never a
 * fresh `ArxivRateLimiter(...)` — else arXiv PDFs stop FIFO-serializing with Atom/HTML and the global ≥3s
 * spacing silently breaks while every behavioral test stays green.
 *
 * This is a **source-structural** check (mirrors `NoDirectNewCallStructuralTest`) rather than a Hilt/DI
 * test: the repo's only `@HiltAndroidTest` is `@Ignore`d as order-flaky, and neither `PdfDownloader` nor
 * `PdfHostPolicy` exposes its limiter for a behavioral assertion — so we pin the wiring at the source.
 */
class PdfLimiterWiringTest {
    @Test
    fun `pdfDownloader wires the injected arxiv limiter singleton, not a fresh instance`() {
        val src = File("src/main/java/dev/blokz/arxiver/di/AppModule.kt")
        assertTrue(src.isFile, "must run from the :app module dir; cwd=${File("").absolutePath}")
        val text = src.readText()
        assertTrue(text.contains("fun pdfDownloader("), "pdfDownloader provider not found in AppModule.kt")

        // `PdfHostPolicy.arxivLimiter = rateLimiter` (the injected singleton) is the ONLY place this exact
        // named assignment appears; the polite slot is `politeLimiter = ArxivRateLimiter(...)`. So a whole-
        // file check is both sufficient and robust: the injected wiring must be present, and the arXiv slot
        // must never be a fresh `ArxivRateLimiter(...)`.
        assertTrue(
            text.contains("arxivLimiter = rateLimiter"),
            "R6: pdfDownloader must pass the injected `rateLimiter` singleton as PdfHostPolicy.arxivLimiter",
        )
        assertTrue(
            !Regex("""arxivLimiter\s*=\s*ArxivRateLimiter\(""").containsMatchIn(text),
            "R6: the arXiv slot must NOT be a fresh ArxivRateLimiter(...) — that breaks global ≥3s spacing",
        )
    }
}
