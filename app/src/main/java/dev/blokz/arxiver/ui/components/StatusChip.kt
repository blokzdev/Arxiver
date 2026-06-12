package dev.blokz.arxiver.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Semantic tone per the SPEC-UI §1 color-role map: Machine = teal
 * (semantic/AI signals), Annotation = amber (the user's layer),
 * Positive = primary-tinted success, Error = failures only.
 */
enum class StatusTone { Neutral, Machine, Annotation, Positive, Error }

/** Small status pill — provenance badges, dispatch states, library markers. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null,
) {
    val (container, content) =
        when (tone) {
            StatusTone.Neutral ->
                MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
            StatusTone.Machine ->
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            StatusTone.Annotation ->
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            StatusTone.Positive ->
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            StatusTone.Error ->
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(end = Spacing.xs)
                            .size(14.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusChipPreview() {
    ArxiverTheme {
        Row {
            StatusChip("semantic", tone = StatusTone.Machine, modifier = Modifier.padding(Spacing.xs))
            StatusChip("reading", tone = StatusTone.Annotation, modifier = Modifier.padding(Spacing.xs))
            StatusChip("sent · 200", tone = StatusTone.Positive, modifier = Modifier.padding(Spacing.xs))
            StatusChip("failed", tone = StatusTone.Error, modifier = Modifier.padding(Spacing.xs))
        }
    }
}
