package dev.blokz.arxiver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * The one error-state anatomy: a centered message (announced to TalkBack as a
 * polite live region) and an optional retry. Shared across every screen so error
 * presentation stays consistent.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(Spacing.xxl)
                .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = Spacing.sm)) {
                Text(stringResource(R.string.action_retry))
            }
        }
        // Optional escape hatch (e.g. "Open in browser" when an in-app fetch fails but a canonical web URL
        // exists) — the graceful degrade so a failed load is never a terminal dead end.
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel)
            }
        }
    }
}

/** Maps a network/storage [AppError] to a user-facing message + retry (+ an optional secondary action). */
@Composable
fun ErrorState(
    error: AppError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    ErrorState(
        message =
            when (error) {
                is AppError.Offline -> stringResource(R.string.error_offline)
                is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
                else -> stringResource(R.string.error_generic)
            },
        modifier = modifier,
        onRetry = onRetry,
        secondaryLabel = secondaryLabel,
        onSecondary = onSecondary,
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStatePreview() {
    ArxiverTheme {
        ErrorState(error = AppError.Offline, onRetry = {})
    }
}
