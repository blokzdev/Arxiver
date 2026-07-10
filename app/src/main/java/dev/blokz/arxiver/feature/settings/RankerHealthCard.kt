package dev.blokz.arxiver.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.eval.ScoreDistribution
import dev.blokz.arxiver.core.search.eval.SegmentResult
import dev.blokz.arxiver.sync.RankerHealth
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Debug-only ranker-health readout (P5.1). Renders the CACHED result of the last worker-run eval — this card
 * computes nothing. The call site gates it behind `BuildConfig.DEBUG`, so R8 strips it from release; it exists
 * so the Co-Founder can read precision/AUC per segment on a real device and report back (the on-device eval
 * story: metrics over the user's own labels, never transmitted).
 */
@Composable
fun RankerHealthCard(health: RankerHealth?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                stringResource(R.string.debug_ranker_health_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (health == null) {
                Text(
                    stringResource(R.string.debug_ranker_health_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val r = health.report
            Line(stringResource(R.string.debug_ranker_segment_overall), r.overall)
            Line(stringResource(R.string.debug_ranker_segment_rich), r.rich)
            Line(stringResource(R.string.debug_ranker_segment_title_only), r.titleOnly)
            DistLine(stringResource(R.string.debug_ranker_segment_rich), health.richDistribution)
            DistLine(stringResource(R.string.debug_ranker_segment_title_only), health.titleOnlyDistribution)
            if (r.regimeContaminated) {
                Text(
                    stringResource(R.string.debug_ranker_regime_contaminated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                pluralStringResource(R.plurals.debug_ranker_label_count, r.labelCount, r.labelCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(
    label: String,
    result: SegmentResult,
) {
    val text =
        when (result) {
            is SegmentResult.Measured ->
                stringResource(
                    R.string.debug_ranker_measured,
                    label,
                    result.auc,
                    result.aucLow,
                    result.aucHigh,
                    result.precisionAtK,
                )
            is SegmentResult.Insufficient ->
                stringResource(
                    R.string.debug_ranker_insufficient,
                    label,
                    result.essPositives,
                    result.essNegatives,
                )
        }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DistLine(
    label: String,
    dist: ScoreDistribution?,
) {
    if (dist == null) return
    Text(
        stringResource(R.string.debug_ranker_distribution, label, dist.count, dist.p50, dist.p90, dist.aboveCut),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
