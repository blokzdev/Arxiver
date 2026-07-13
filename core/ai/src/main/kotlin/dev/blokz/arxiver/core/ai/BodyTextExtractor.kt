package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Extracts clean, FTS/embedding-ready plain text from a paper body (P-FullText PFT.2). A **layered seam**:
 * the HTML implementation ([HtmlBodyTextExtractor]) reads the already-persisted, sanitized reader body; a
 * future PDF implementation (PFT.5) slots in behind the same interface. Pure, no Android.
 */
fun interface BodyTextExtractor {
    /** @return clean plain text (whitespace-collapsed), or "" when there is no usable body text. */
    fun extract(bodyHtml: String): String
}

/**
 * HTML → plain text via jsoup (already a `:core:ai` dependency; no new dep). **Strips `<math>` subtrees
 * before `.text()`**: the reader body keeps MathML verbatim ([ReaderDocument] — "`<math>` kept verbatim"),
 * so a naive `.text()` would splice presentation-MathML token text (`<mi>x</mi><mo>=</mo>…`) into the
 * surrounding prose as glyph-soup on exactly arXiv's math-heavy core — poisoning both BM25 tokenization and
 * bge embeddings. Dropping the whole `<math>` subtree removes the equation cleanly and leaves the prose
 * intact. `.text()` collapses whitespace and ignores `<img>`, so the inlined `data:` figure bytes (PH.5)
 * never enter the text. Structured `.ltx_*`-aware section chunking is a deferred quality refinement (backlog).
 */
object HtmlBodyTextExtractor : BodyTextExtractor {
    override fun extract(bodyHtml: String): String {
        if (bodyHtml.isBlank()) return ""
        val doc =
            try {
                Jsoup.parse(bodyHtml, "", Parser.htmlParser())
            } catch (_: Exception) {
                return ""
            }
        doc.select("math").remove()
        return doc.body().text()
    }
}
