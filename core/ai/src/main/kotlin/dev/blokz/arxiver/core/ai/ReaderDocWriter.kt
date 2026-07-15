package dev.blokz.arxiver.core.ai

/**
 * Wraps a transformed [ReaderDocument] body into the full, self-contained reader HTML document
 * (Phase P-HTML PH.3, SPEC-P-HTML §9/§12). Pure JVM → golden-testable; the only runtime input is the
 * [ReaderTheme] hex (supplied by `:app` at PH.4 from `rememberRichTheme()`).
 *
 * - **CSP `script-src 'none'`** — the security floor against any jsoup↔Chromium parser-differential
 *   mutation-XSS (the sanitizer removes scripts; CSP guarantees none can execute even if one slipped).
 *   The reader needs no page JS (native MathML is declarative; PH.4 anchor-scroll is host-driven).
 * - **reader.css is INLINED** into a single `<style>` (not a `file://` `<link>`): the reader WebView's
 *   base URL is the per-paper origin with `allowFileAccessFromFileURLs = false`, so a cross-origin
 *   `file:///android_asset` stylesheet would silently fail to load.
 * - **Theme via CSS custom properties** — reader.css references only `var(--reader-*)`.
 * - **No self-size script** — the reader is a full-screen, natively-scrolling destination (PH.4), not an
 *   inline answer block, so it doesn't report its height back to Compose.
 */
object ReaderDocWriter {
    private val css: String by lazy {
        ReaderDocWriter::class.java.getResourceAsStream("/reader/reader.css")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("reader.css resource missing from :core:ai classpath")
    }

    fun write(
        doc: ReaderDocument,
        theme: ReaderTheme,
    ): String {
        val themeVars =
            ":root{" +
                "--reader-text:${theme.text};" +
                "--reader-bg:${theme.background};" +
                "--reader-link:${theme.link};" +
                "--reader-muted:${theme.muted};" +
                "--reader-muted-text:${theme.mutedText};" +
                "--reader-code-bg:${theme.codeBackground};" +
                "}"
        return buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset=\"utf-8\">")
            append(
                "<meta http-equiv=\"Content-Security-Policy\" " +
                    "content=\"script-src 'none'; object-src 'none'; base-uri 'none'\">",
            )
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>").append(themeVars).append('\n').append(css).append("</style>")
            append("</head><body>")
            append(doc.bodyHtml)
            append("</body></html>")
        }
    }
}
