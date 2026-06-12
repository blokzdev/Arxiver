package dev.blokz.arxiver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Content-shaped loading placeholders (SPEC-UI §4: skeletons, not
 * spinners, after first frame). A shared alpha pulse — no shimmer
 * gradient dependency.
 */
@Composable
private fun pulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "skeleton-alpha",
    )
    return alpha
}

@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
) {
    Spacer(
        modifier =
            modifier
                .fillMaxWidth(widthFraction)
                .height(14.dp)
                .alpha(pulseAlpha())
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Mirrors PaperListItem's anatomy: two title lines, authors, a chip pill. */
@Composable
fun SkeletonPaperItem(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        SkeletonLine(widthFraction = 0.92f)
        SkeletonLine(
            widthFraction = 0.65f,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        SkeletonLine(
            widthFraction = 0.45f,
            modifier =
                Modifier
                    .padding(top = Spacing.md)
                    .height(10.dp),
        )
        Spacer(
            modifier =
                Modifier
                    .padding(top = Spacing.md)
                    .width(72.dp)
                    .height(22.dp)
                    .alpha(pulseAlpha())
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    itemCount: Int = 8,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(itemCount) {
            SkeletonPaperItem()
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SkeletonListPreview() {
    ArxiverTheme {
        SkeletonList(itemCount = 3)
    }
}
