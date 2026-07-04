package dev.blokz.arxiver.feature.html

import dev.blokz.arxiver.core.ai.ReaderPosition
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The single home for every JS snippet the reader injects (P-HTML PH.6) — jump, restore, and the
 * position probe share one builder so the cite-intercept, TOC, and restore paths can never diverge.
 * All ids are document-derived (untrusted) → embedded via [JSONObject.quote]; the probe's RETURN
 * value round-trips through the untrusted page → parsed defensively + clamped ([parseProbeResult]).
 * Host-initiated `evaluateJavascript` only (the page CSP `script-src 'none'` + the no-page-JS-bridge
 * invariant stand; SPEC-P-HTML §12).
 */
internal object ReaderScrollJs {
    /** Scroll the element with [anchorId] to the viewport top. Only a user's own tap animates. */
    fun jump(
        anchorId: String,
        smooth: Boolean,
    ): String {
        val lit = JSONObject.quote(anchorId)
        val behavior = if (smooth) "smooth" else "auto"
        return "var e=document.getElementById($lit);" +
            "if(e)e.scrollIntoView({behavior:'$behavior',block:'start'});"
    }

    /** Re-apply a saved [position]: anchor + offset when an anchor stands, else the fraction floor. */
    fun restore(position: ReaderPosition): String =
        if (position.anchorId != null) {
            jump(position.anchorId!!, smooth = false) +
                "window.scrollBy(0,${position.offsetCssPx.coerceAtLeast(0)});"
        } else {
            val f = position.fraction.coerceIn(0f, 1f)
            "window.scrollTo(0,Math.max(document.documentElement.scrollHeight-window.innerHeight,0)*$f);"
        }

    /**
     * Probe the current reading position: scan [anchorIds] rects, pick the last anchor at/above the
     * viewport top, return `{a: id|null, o: cssPxPastAnchor, f: scrollFraction}` as a JSON string.
     * All page-space CSS px — no host-side density math.
     */
    fun probe(anchorIds: List<String>): String {
        val ids = JSONArray(anchorIds).toString()
        return "(function(){var ids=$ids,best=null,bestTop=-Infinity;" +
            "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(!e)continue;" +
            "var t=e.getBoundingClientRect().top;if(t<=1&&t>bestTop){bestTop=t;best=ids[i];}}" +
            "var f=window.scrollY/Math.max(document.documentElement.scrollHeight-window.innerHeight,1);" +
            "return JSON.stringify({a:best,o:best?Math.round(-bestTop):0,f:f});})()"
    }

    /**
     * Read the current selection, CAPPED IN-PAGE (PH.7): `.slice(0, SELECTION_READ_CAP)` runs inside
     * the snippet so a select-all on a multi-MB `data:`-inlined paper never crosses the bridge at
     * size — the JSON-encoded return is bounded before it leaves the page.
     */
    fun selection(): String = "(function(){return window.getSelection().toString().slice(0,$SELECTION_READ_CAP);})()"

    /**
     * Parse + sanitize the untrusted selection return (PH.7). Order is load-bearing:
     * 1. Unicode-aware whitespace collapse — `\\p{Z}` catches U+00A0/U+2028/U+2029 that Java's plain
     *    `\\s` misses (defuses blockquote breakout + the confirm-card visual-lie vector);
     * 2. THEN strip format/control chars (`\\p{Cf}` zero-width/bidi overrides, `\\p{Cc}` raw
     *    controls) — after the collapse, or `\n`/`\t` (which are Cc) would be deleted instead of
     *    becoming spaces and words would glue together;
     * 3. trim, re-cap (belt-and-suspenders), null-if-blank.
     */
    fun parseSelectionResult(raw: String?): String? =
        runCatching {
            if (raw.isNullOrBlank() || raw == "null") return@runCatching null
            val unquoted = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
            unquoted
                .replace(Regex("[\\s\\p{Z}\\u0085]+"), " ")
                .replace(Regex("[\\p{Cf}\\p{Cc}]"), "")
                .trim()
                .take(SELECTION_READ_CAP)
                .ifBlank { null }
        }.getOrNull()

    /** In-page selection cap (chars). PROVISIONAL — device-ratified (VERIFICATION §M). */
    const val SELECTION_READ_CAP = 2000

    /** Parse the probe's untrusted return; null on any garbage. Values clamped; anchor NOT yet trusted. */
    fun parseProbeResult(raw: String?): ReaderPosition? =
        runCatching {
            if (raw.isNullOrBlank() || raw == "null") return@runCatching null
            // evaluateJavascript returns a JSON-encoded string — unquote via the tokener first.
            val unquoted = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
            val obj = JSONObject(unquoted)
            ReaderPosition(
                anchorId = obj.optString("a").takeIf { it.isNotEmpty() && it != "null" },
                offsetCssPx = obj.optInt("o").coerceAtLeast(0),
                fraction = obj.optDouble("f", 0.0).toFloat().coerceIn(0f, 1f),
            )
        }.getOrNull()
}
