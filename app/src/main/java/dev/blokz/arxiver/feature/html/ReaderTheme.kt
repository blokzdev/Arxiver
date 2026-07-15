package dev.blokz.arxiver.feature.html

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.blokz.arxiver.core.ai.ReaderTheme
import dev.blokz.arxiver.ui.markdown.toCssHex

/**
 * Resolves the [ReaderTheme] (hex colors) for the HTML reader from the current Material color scheme,
 * so [dev.blokz.arxiver.core.ai.ReaderDocWriter] can inject `:root{--reader-*}` CSS vars. Light/dark
 * falls out of MaterialTheme automatically. Kept separate from `rememberRichTheme()` (different shape;
 * the reader uses a solid `surface` background, not the answer bubble's transparent fill).
 */
@Composable
internal fun rememberReaderTheme(): ReaderTheme {
    val scheme = MaterialTheme.colorScheme
    return remember(
        scheme.onSurface,
        scheme.surface,
        scheme.primary,
        scheme.outlineVariant,
        scheme.onSurfaceVariant,
        scheme.surfaceVariant,
    ) {
        ReaderTheme(
            text = scheme.onSurface.toCssHex(),
            background = scheme.surface.toCssHex(),
            link = scheme.primary.toCssHex(),
            // Border/rule hue (low-contrast by design) vs. readable muted TEXT (HR-FMT.3) — split so captions
            // and blockquotes clear WCAG AA, which outlineVariant-as-text was failing in both light and dark.
            muted = scheme.outlineVariant.toCssHex(),
            mutedText = scheme.onSurfaceVariant.toCssHex(),
            codeBackground = scheme.surfaceVariant.toCssHex(),
        )
    }
}
