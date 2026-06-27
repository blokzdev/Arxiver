package dev.blokz.arxiver.ui.markdown

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders a rich AI answer (markdown + LaTeX math) in a **sandboxed, offline** WebView
 * (Phase P-Rich R1). Math is the reason: a native renderer can't do inline `$x$`, but KaTeX
 * can — so a math answer is rendered as one HTML document ([RichHtml]) with bundled KaTeX.
 * The foundation R2 reuses for Mermaid diagrams.
 *
 * Sandbox: JS on (KaTeX only), **all network blocked**, no content access, no cross-file
 * access, no `@JavascriptInterface`. Only bundled `file:///android_asset/katex/` loads.
 * The page talks back only through safe `arxiver://` link intents (no data crosses):
 * `arxiver://cite/n` → [onCitationClick]; `arxiver://height/px` → self-size to content;
 * `arxiver://paper/<id>` → [onArxivPaperClick] (open a cross-referenced paper, P-Rich R3a).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichBlockWebView(
    markdown: String,
    modifier: Modifier = Modifier,
    onCitationClick: ((Int) -> Unit)? = null,
    onArxivPaperClick: ((String) -> Unit)? = null,
) {
    val theme = rememberRichTheme()
    val html =
        remember(markdown, theme) {
            RichHtml.answerHtml(
                markdown = markdown,
                textColor = theme.textColor,
                citationColor = theme.citationColor,
                codeBackground = theme.codeBackground,
                mutedColor = theme.mutedColor,
                dark = theme.dark,
                crossRefColor = theme.crossRefColor,
            )
        }
    var heightDp by remember { mutableIntStateOf(0) }
    val onCite by rememberUpdatedState(onCitationClick)
    val onPaper by rememberUpdatedState(onArxivPaperClick)

    AndroidView(
        modifier = modifier.fillMaxWidth().then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier),
        factory = { context ->
            WebView(context).apply {
                applyRichSandbox()
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url
                            if (url?.scheme == "arxiver") {
                                when (url.host) {
                                    "cite" -> url.lastPathSegment?.toIntOrNull()?.let { onCite?.invoke(it) }
                                    "height" -> url.lastPathSegment?.toIntOrNull()?.let { heightDp = it }
                                    // An arXiv id is a string (e.g. `2403.01234` or `math/0211159`),
                                    // not an Int — read the raw last segment (slash decoded back in).
                                    "paper" -> url.lastPathSegment?.let { onPaper?.invoke(it) }
                                }
                            }
                            // Block every real navigation (http/https/etc.); asset sub-resource
                            // loads don't route through here.
                            return true
                        }
                    }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "utf-8", null)
        },
    )
}

/** Compose color -> CSS `#RRGGBB` (alpha dropped; the WebView background is transparent). */
internal fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/**
 * Applies the **offline sandbox** shared by the on-screen [RichBlockWebView] and the off-screen
 * [RichImageExporter] (P-Share PS.4b): JS on for the bundled KaTeX/Mermaid only, **all network
 * blocked**, no content/cross-file access, bundled `file:///android_asset/` loads only. Extracted
 * so the capture WebView can never silently drift from `blockNetworkLoads = true`.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyRichSandbox() {
    setBackgroundColor(0) // transparent; the host paints the background
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    settings.apply {
        javaScriptEnabled = true // KaTeX/Mermaid only; the page runs no model-supplied JS
        blockNetworkLoads = true
        allowContentAccess = false
        allowFileAccess = true // for the bundled file:///android_asset/ KaTeX + Mermaid
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
}

/**
 * The themed colors a rich block renders with — one source of truth shared by the on-screen
 * [RichBlockWebView] and the off-screen [RichImageExporter], so an **exported image matches the
 * screen**. Hex strings feed [RichHtml]; [backgroundArgb] is the opaque fill painted behind the
 * (transparent) WebView for a standalone PNG.
 */
data class RichTheme(
    val textColor: String,
    val citationColor: String,
    val codeBackground: String,
    val mutedColor: String,
    val crossRefColor: String,
    val dark: Boolean,
    val backgroundArgb: Int,
)

/** Resolves the current [RichTheme] from the Material color scheme + light/dark. */
@Composable
internal fun rememberRichTheme(): RichTheme {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return remember(
        scheme.onSurface,
        scheme.primary,
        scheme.surfaceVariant,
        scheme.outlineVariant,
        scheme.tertiary,
        scheme.surfaceContainerHigh,
        dark,
    ) {
        RichTheme(
            textColor = scheme.onSurface.toCssHex(),
            citationColor = scheme.primary.toCssHex(),
            codeBackground = scheme.surfaceVariant.toCssHex(),
            mutedColor = scheme.outlineVariant.toCssHex(),
            // Distinct accent so an arXiv cross-ref reads as navigation, not a [n] citation.
            crossRefColor = scheme.tertiary.toCssHex(),
            dark = dark,
            // Match the assistant bubble (surfaceContainerHigh); force opaque so the PNG isn't see-through.
            backgroundArgb = scheme.surfaceContainerHigh.toArgb() or (0xFF shl 24),
        )
    }
}
