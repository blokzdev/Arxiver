package dev.blokz.arxiver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

/** A toned provenance/status marker on the cell (SPEC-UI §1 role map). */
data class PaperBadge(val label: String, val tone: StatusTone)

/**
 * THE paper list cell (SPEC-UI §4): Today, Browse, Search, and Library all
 * render through this composable. Context slots are optional — [score] for
 * inbox relevance, [status]/[rating] for library rows, [badge] for search
 * provenance — and absent slots cost no space.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaperListItem(
    paper: Paper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    badge: PaperBadge? = null,
    score: Double? = null,
    status: String? = null,
    rating: Int? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(ArxiverMotion.DURATION_SHORT),
        label = "selection-background",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(background)
                .then(
                    if (selectionMode) {
                        Modifier.semantics { this.selected = selected }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandHorizontally(tween(ArxiverMotion.DURATION_SHORT)),
            exit = shrinkHorizontally(tween(ArxiverMotion.DURATION_SHORT)),
        ) {
            Crossfade(targetState = selected, label = "selection-check") { isSelected ->
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .padding(end = Spacing.md)
                            .size(22.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = paper.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = paper.authors.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SuggestionChip(
                    onClick = onClick,
                    label = { Text(paper.primaryCategory, style = MaterialTheme.typography.labelSmall) },
                    colors =
                        SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    border = null,
                )
                Text(
                    text = dateFormat.format(paper.updatedAt.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                paper.citationCount?.let {
                    val citationsLabel = pluralStringResource(R.plurals.connections_citation_count, it, it)
                    Text(
                        text = "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.semantics {
                                contentDescription = citationsLabel
                            },
                    )
                }
                status?.let {
                    StatusChip(text = statusLabel(it), tone = StatusTone.Annotation)
                }
                rating?.let {
                    val ratingLabel = stringResource(R.string.cd_rating, it)
                    Text(
                        text = "★ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier =
                            Modifier.semantics {
                                contentDescription = ratingLabel
                            },
                    )
                }
                badge?.let {
                    StatusChip(text = it.label, tone = it.tone)
                }
            }
            score?.let {
                val percent = (it.coerceIn(0.0, 1.0) * 100).toInt()
                val relevanceLabel = stringResource(R.string.cd_relevance, percent)
                ScoreBar(
                    score = it.toFloat(),
                    modifier =
                        Modifier
                            .padding(top = Spacing.xs)
                            .width(72.dp)
                            .semantics {
                                contentDescription = relevanceLabel
                            },
                )
            }
        }
    }
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun statusLabel(status: String): String =
    when (status) {
        "to_read" -> stringResource(R.string.library_filter_to_read)
        "reading" -> stringResource(R.string.library_filter_reading)
        "read" -> stringResource(R.string.library_filter_read)
        else -> status
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaperListItemPreview() {
    ArxiverTheme {
        Column {
            PaperListItem(paper = PreviewFixtures.paper, onClick = {})
            PaperListItem(
                paper = PreviewFixtures.papers[1],
                onClick = {},
                badge = PaperBadge("semantic", StatusTone.Machine),
                score = 0.83,
            )
            PaperListItem(
                paper = PreviewFixtures.papers[2],
                onClick = {},
                status = "reading",
                rating = 4,
            )
            PaperListItem(
                paper = PreviewFixtures.paper,
                onClick = {},
                selectionMode = true,
                selected = true,
                showDivider = false,
            )
        }
    }
}
