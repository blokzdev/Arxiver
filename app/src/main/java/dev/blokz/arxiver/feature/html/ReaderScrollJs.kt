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
