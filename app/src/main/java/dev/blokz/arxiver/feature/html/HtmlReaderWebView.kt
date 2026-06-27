package dev.blokz.arxiver.feature.html

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.blokz.arxiver.ui.markdown.applyRichSandbox
import org.json.JSONObject

/**
 * The full-screen, natively-scrolling reader WebView (Phase P-HTML PH.4). Reuses the shared offline
 * [applyRichSandbox] (`blockNetworkLoads=true`, no `@JavascriptInterface`) + `blockNetworkImage=true`,
 * and intercepts the in-app `arxiver://` scheme the transform emits:
 * - `anchor?frag=…` → host-driven `scrollIntoView` (the cite→bibliography jump). The frag is
 *   document-derived (untrusted), so it's embedded via [JSONObject.quote] — a bulletproof JS string
 *   literal (the page CSP `script-src 'none'` does **not** guard host-injected `evaluateJavascript`).
 * - `paper/<id>` → [onPaperClick] (open a cross-referenced paper in-app).
 * - `external?url=…` → [onExternalUrl] (the screen confirms, then opens via an Intent — never auto-open).
 * Unlike [dev.blokz.arxiver.ui.markdown.RichBlockWebView] this does **not** self-size — it fills its
 * route and scrolls. Base URL stays **null** (PH.5): images are pre-fetched and base64-**inlined** as
 * `data:image` URIs into the body, so there are still no sub-resources and BOTH `blockNetworkLoads` and
 * `blockNetworkImage` stay armed — the reserved PH.5 virtual-origin (which would have forced
 * `blockNetworkImage=false`) is retired in favour of `data:` inlining.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlReaderWebView(
    html: String,
    onPaperClick: (String) -> Unit,
    onExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPaper by rememberUpdatedState(onPaperClick)
    val onExternal by rememberUpdatedState(onExternalUrl)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                applyRichSandbox()
                settings.blockNetworkImage = true // belt-and-suspenders over blockNetworkLoads
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url ?: return true
                            if (url.scheme == "arxiver") {
                                when (url.host) {
                                    "anchor" ->
                                        url.getQueryParameter("frag")?.let { frag ->
                                            val lit = JSONObject.quote(frag)
                                            view?.evaluateJavascript(
                                                "var e=document.getElementById($lit);" +
                                                    "if(e)e.scrollIntoView({behavior:'smooth',block:'start'});",
                                                null,
                                            )
                                        }
                                    "paper" -> url.lastPathSegment?.let { onPaper(it) }
                                    "external" -> url.getQueryParameter("url")?.let { onExternal(it) }
                                    // "height" / anything else → ignore (the reader scrolls natively).
                                }
                            }
                            return true // block every real navigation; asset sub-resources don't route here
                        }
                    }
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        onRelease = { it.destroy() },
    )
}
