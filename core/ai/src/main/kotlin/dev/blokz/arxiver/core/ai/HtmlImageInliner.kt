package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup

/**
 * Pure PH.5 step (SPEC-P-HTML §8/§9): rewrite each `<img data-img-key=K>` token that
 * [HtmlReaderTransform] emitted into either a self-contained `data:image/<sub>;base64,<…>` URI (when
 * its bytes were fetched — `resolved[K]`) or the figcaption placeholder (on miss / over-cap / failure).
 *
 * The result is fully offline — no host, no fetch, no `file://`. The only `data:` form emitted is the
 * [SanitizerCore] `DATA_IMAGE` raster allowlist (png/jpeg/gif/webp), so a re-sanitize is a no-op. Pure
 * jsoup → golden-testable; the persisted body the WebView loads is this output.
 */
object HtmlImageInliner {
    fun inline(
        bodyHtml: String,
        resolved: Map<String, InlinedImage>,
    ): String {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        doc.outputSettings().prettyPrint(false)
        for (img in doc.body().select("img[data-img-key]")) {
            val hit = resolved[img.attr("data-img-key")]
            if (hit != null) {
                img.removeAttr("data-img-key")
                img.attr("src", "data:image/${hit.mimeSubtype};base64,${hit.base64}")
                // Tag transparent figures so the dark reader can matte them (HR-FMT.4); opaque photos stay bare.
                if (hit.transparent) img.addClass("reader-matte")
            } else {
                img.replaceWith(HtmlReaderTransform.figurePlaceholder())
            }
        }
        return doc.body().html().trim()
    }
}
