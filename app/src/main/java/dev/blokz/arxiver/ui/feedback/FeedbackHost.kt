package dev.blokz.arxiver.ui.feedback

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private enum class FeedbackResult { PRIMARY, SECONDARY, DISMISSED }

private class FeedbackDisplay(
    val message: FeedbackMessage,
    val outcome: CompletableDeferred<FeedbackResult>,
)

/**
 * The single app-level feedback surface. Collects [FeedbackController] messages and shows one at a
 * time as an elevated, dismissible snackbar. Custom duration is the M3-only-Short/Long/Indefinite
 * workaround: race [withTimeoutOrNull] against the user's tap/dismiss.
 *
 * Pass to the app shell `Scaffold(snackbarHost = { FeedbackHost(controller) })` so it sits above the
 * bottom bar with the right window insets.
 */
@Composable
fun FeedbackHost(
    controller: FeedbackController,
    modifier: Modifier = Modifier,
) {
    var display by remember { mutableStateOf<FeedbackDisplay?>(null) }
    // Retain the last message so the exit animation still has content to render while `display` is null.
    var retained by remember { mutableStateOf<FeedbackMessage?>(null) }
    display?.let { retained = it.message }

    LaunchedEffect(controller) {
        controller.messages.collect { message ->
            val current = FeedbackDisplay(message, CompletableDeferred())
            display = current
            val result =
                withTimeoutOrNull(message.durationMillis) { current.outcome.await() }
                    ?: FeedbackResult.DISMISSED
            when (result) {
                FeedbackResult.PRIMARY -> message.primary?.onPerform?.invoke()
                FeedbackResult.SECONDARY -> message.secondary?.onPerform?.invoke()
                FeedbackResult.DISMISSED -> Unit
            }
            display = null
        }
    }

    AnimatedVisibility(
        visible = display != null,
        enter =
            slideInVertically(tween(ArxiverMotion.DURATION_MEDIUM)) { it } +
                fadeIn(tween(ArxiverMotion.DURATION_MEDIUM)),
        exit =
            slideOutVertically(tween(ArxiverMotion.DURATION_SHORT)) { it } +
                fadeOut(tween(ArxiverMotion.DURATION_SHORT)),
        modifier = modifier,
    ) {
        retained?.let { message ->
            ArxiverSnackbar(
                message = message,
                onPrimary = { display?.outcome?.complete(FeedbackResult.PRIMARY) },
                onSecondary = { display?.outcome?.complete(FeedbackResult.SECONDARY) },
                onDismiss = { display?.outcome?.complete(FeedbackResult.DISMISSED) },
            )
        }
    }
}

@Composable
private fun ArxiverSnackbar(
    message: FeedbackMessage,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.md),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = Spacing.md).weight(1f),
            )
            message.secondary?.let { action ->
                TextButton(onClick = {
                    action.onPerform()
                    onSecondary()
                }) {
                    Text(action.label, color = MaterialTheme.colorScheme.primary)
                }
            }
            message.primary?.let { action ->
                TextButton(onClick = {
                    action.onPerform()
                    onPrimary()
                }) {
                    Text(action.label, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_feedback),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ArxiverSnackbarPreview() {
    ArxiverTheme {
        ArxiverSnackbar(
            message =
                FeedbackMessage(
                    text = "Saved to library",
                    primary = FeedbackAction("Undo") {},
                    secondary = FeedbackAction("Add to…") {},
                ),
            onPrimary = {},
            onSecondary = {},
            onDismiss = {},
        )
    }
}
