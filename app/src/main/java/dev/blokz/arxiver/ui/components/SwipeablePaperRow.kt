package dev.blokz.arxiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * A [PaperListItem] wrapped with horizontal swipe gestures (SPEC-UI §5). Swipe right = save, swipe
 * left = remove/dismiss. Each direction is opt-in: pass `null` to disable it (e.g. an arXiv search
 * result has no "dismiss"). Both directions carry TalkBack [CustomAccessibilityAction] equivalents.
 *
 * Swipe is inert while [selectionMode] is on, so the horizontal gesture and long-press multi-select
 * coexist. The action fires and the row snaps back; items that leave the list are removed by the data
 * flow — so the same wrapper serves both "swipe to triage" (Today) and "swipe to save in place"
 * (Browse/Search) without leaving a blank dismissed slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeablePaperRow(
    paper: Paper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onSwipeSave: (() -> Unit)? = null,
    onSwipeDismiss: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    showDivider: Boolean = true,
    score: Double? = null,
    status: String? = null,
    rating: Int? = null,
    badge: PaperBadge? = null,
    vote: Int? = null,
    onVote: ((Boolean) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    // Read the latest callbacks/mode inside confirmValueChange (the state captures it once).
    val save by rememberUpdatedState(onSwipeSave)
    val dismiss by rememberUpdatedState(onSwipeDismiss)
    val inSelection by rememberUpdatedState(selectionMode)

    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (inSelection) return@rememberSwipeToDismissBoxState false
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        save?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    SwipeToDismissBoxValue.EndToStart ->
                        dismiss?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                // Always snap back; the data flow removes rows that actually left the list.
                false
            },
        )

    val saveLabel = stringResource(R.string.cd_save_paper)
    val dismissLabel = stringResource(R.string.cd_dismiss_paper)
    val a11yActions = swipeAccessibilityActions(saveLabel, dismissLabel, onSwipeSave, onSwipeDismiss)

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !selectionMode && onSwipeSave != null,
        enableDismissFromEndToStart = !selectionMode && onSwipeDismiss != null,
        modifier = modifier.semantics { if (a11yActions.isNotEmpty()) customActions = a11yActions },
        backgroundContent = {
            val (color, icon, alignment) =
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Triple(
                            MaterialTheme.colorScheme.primaryContainer,
                            Icons.Filled.BookmarkAdd,
                            Alignment.CenterStart,
                        )
                    else ->
                        Triple(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            Icons.Filled.Close,
                            Alignment.CenterEnd,
                        )
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = Spacing.xl),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null)
            }
        },
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            PaperListItem(
                paper = paper,
                onClick = onClick,
                onLongClick = onLongClick,
                score = score,
                status = status,
                rating = rating,
                badge = badge,
                selectionMode = selectionMode,
                selected = selected,
                showDivider = showDivider,
                vote = vote,
                onVote = onVote,
            )
        }
    }
}

/**
 * TalkBack equivalents for the swipe gestures (SPEC-UI §5): one action per enabled direction. Pure so
 * the opt-in logic is unit-tested without a Compose harness.
 */
internal fun swipeAccessibilityActions(
    saveLabel: String,
    dismissLabel: String,
    onSwipeSave: (() -> Unit)?,
    onSwipeDismiss: (() -> Unit)?,
): List<CustomAccessibilityAction> =
    buildList {
        if (onSwipeSave != null) {
            add(
                CustomAccessibilityAction(saveLabel) {
                    onSwipeSave()
                    true
                },
            )
        }
        if (onSwipeDismiss != null) {
            add(
                CustomAccessibilityAction(dismissLabel) {
                    onSwipeDismiss()
                    true
                },
            )
        }
    }
