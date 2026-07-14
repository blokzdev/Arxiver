package dev.blokz.arxiver.core.pdf

/**
 * `:core:pdf` — the isolation module carrying the `pdfbox-android` dependency (Phase P-Reader2, Track A).
 *
 * It is deliberately kept OFF the pure `:core:ai` HTML/LLM path and has **no network dependency**
 * (`:core:common` + coroutines only), so [dev.blokz.arxiver.core.pdf] is a small, meaningful walk-root for
 * `PdfboxNoNetworkStructuralTest`. Everything this module ever does is extract text from an **already-
 * downloaded local PDF file** — zero egress. The `pdfbox-android` dependency + the `PdfBodyTextExtractor`
 * seam land in the next subphase (PFT.5.2); this file is the module's initial marker so the structural
 * guard has something to guard.
 */
internal object CorePdf
