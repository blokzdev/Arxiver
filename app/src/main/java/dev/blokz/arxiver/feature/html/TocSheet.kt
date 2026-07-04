package dev.blokz.arxiver.feature.html

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.TocEntry
import dev.blokz.arxiver.core.ai.TocGroup
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * The reader's table of contents (P-HTML PH.6, SPEC-P-HTML §11): a [ModalBottomSheet] — the app's
 * one contextual-surface vocabulary — over the pure golden-tested `TocModel`. Group headers carry
 * `semantics { heading() }` so TalkBack navigates them as headings; rows are ≥48dp touch targets;
 * blank labels fall back to typed generic strings (never raw LaTeXML ids — "S3.SS2" is TalkBack
 * garbage). Tap = dismiss-then-act, delegated to the ViewModel's jump arbitration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TocSheet(
    entries: List<TocEntry>,
    onSelect: (TocEntry, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TocSheetContent(entries = entries, onSelect = onSelect)
    }
}

@Composable
private fun TocSheetContent(
    entries: List<TocEntry>,
    onSelect: (TocEntry, String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.html_toc_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.html_toc_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
            return@Column
        }
        LazyColumn {
            var lastGroup: TocGroup? = null
            for (entry in entries) {
                if (entry.group != lastGroup) {
                    lastGroup = entry.group
                    val header = entry.group
                    item(key = "header-${header.name}") {
                        SectionHeader(
                            text = stringResource(header.titleRes()),
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
                item(key = entry.anchorId) {
                    TocRow(entry = entry, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun TocRow(
    entry: TocEntry,
    onSelect: (TocEntry, String) -> Unit,
) {
    // Resolve here so the tap handler (and its TalkBack announcement) gets the same label the row shows.
    val label = entry.displayLabel()
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onSelect(entry, label) }
                .padding(
                    start = Spacing.lg + Spacing.md * minOf(entry.depth, 3),
                    end = Spacing.lg,
                )
                .wrapContentHeight(Alignment.CenterVertically),
    )
}

private fun TocGroup.titleRes(): Int =
    when (this) {
        TocGroup.SECTIONS -> R.string.html_toc_sections
        TocGroup.FIGURES -> R.string.html_toc_figures
        TocGroup.TABLES -> R.string.html_toc_tables
        TocGroup.BIBLIOGRAPHY -> R.string.html_toc_bibliography
    }

/** Blank labels → a typed generic name; the bibliography row always uses its fixed title. */
@Composable
internal fun TocEntry.displayLabel(): String =
    when {
        group == TocGroup.BIBLIOGRAPHY -> stringResource(R.string.html_toc_bibliography)
        label.isNotBlank() -> label
        group == TocGroup.FIGURES -> stringResource(R.string.html_toc_untitled_figure)
        group == TocGroup.TABLES -> stringResource(R.string.html_toc_untitled_table)
        else -> stringResource(R.string.html_toc_untitled_section)
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TocSheetContentPreview() {
    ArxiverTheme {
        TocSheetContent(
            entries =
                listOf(
                    TocEntry("S1", "Introduction", TocGroup.SECTIONS, 0),
                    TocEntry("S1.SS1", "Motivation and scope of this work", TocGroup.SECTIONS, 1),
                    TocEntry("S2", "Method", TocGroup.SECTIONS, 0),
                    TocEntry("S2.F1", "Model architecture overview", TocGroup.FIGURES, 0),
                    TocEntry("S3.T1", "", TocGroup.TABLES, 0),
                    TocEntry("bib.bib1", "", TocGroup.BIBLIOGRAPHY, 0),
                ),
            onSelect = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TocSheetEmptyPreview() {
    ArxiverTheme { TocSheetContent(entries = emptyList(), onSelect = { _, _ -> }) }
}
