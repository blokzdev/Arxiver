package dev.blokz.arxiver.feature.html

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.theme.ArxiverTheme

/** Find-in-page match state (document-lifetime; cleared on every reload/query change). */
internal data class FindCounts(
    val active: Int,
    val total: Int,
    val done: Boolean,
)

/**
 * Pure count-label reducer (unit-tested): NEUTRAL (null) before the first callback or on a blank
 * query — Chromium delays short-query counting, and silence must never read as "0 results";
 * "no matches" latches ONLY once counting is done; otherwise "n of m" live-updates while counting.
 */
internal sealed interface FindCountLabel {
    data object Neutral : FindCountLabel

    data object NoMatches : FindCountLabel

    data class Matches(val active: Int, val total: Int) : FindCountLabel
}

internal fun reduceFindCounts(
    query: String,
    counts: FindCounts?,
): FindCountLabel =
    when {
        query.isBlank() || counts == null -> FindCountLabel.Neutral
        counts.total > 0 -> FindCountLabel.Matches(counts.active + 1, counts.total)
        counts.done -> FindCountLabel.NoMatches // FindHelper clamps ordinal at 0 — never "1/0"
        else -> FindCountLabel.Neutral
    }

/**
 * The find-in-page bar (P-HTML PH.7): swaps into the reader's topBar slot at the SAME height so the
 * WebView never resizes (zero PH.6 reflow). SelectionTopBar's surfaceContainerHigh frame + the
 * Explore pill-field idiom; no match-case toggle (Chromium hardcodes case-insensitive).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderFindBar(
    query: String,
    counts: FindCounts?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val label = reduceFindCounts(query, counts)
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_find_close))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.html_find_hint)) },
                singleLine = true,
                shape = CircleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, stringResource(R.string.cd_clear_query))
                        }
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
            )
        },
        actions = {
            Text(
                text =
                    when (label) {
                        FindCountLabel.Neutral -> ""
                        FindCountLabel.NoMatches -> stringResource(R.string.html_find_no_matches)
                        is FindCountLabel.Matches ->
                            stringResource(R.string.html_find_match, label.active, label.total)
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val enabled = label is FindCountLabel.Matches
            IconButton(onClick = onPrevious, enabled = enabled) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.cd_find_previous))
            }
            IconButton(onClick = onNext, enabled = enabled) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.cd_find_next))
            }
        },
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReaderFindBarPreview() {
    ArxiverTheme {
        ReaderFindBar(
            query = "attention",
            counts = FindCounts(active = 2, total = 17, done = true),
            onQueryChange = {},
            onSubmit = {},
            onNext = {},
            onPrevious = {},
            onClose = {},
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReaderFindBarNoMatchesPreview() {
    ArxiverTheme {
        ReaderFindBar(
            query = "zyzzyva",
            counts = FindCounts(active = 0, total = 0, done = true),
            onQueryChange = {},
            onSubmit = {},
            onNext = {},
            onPrevious = {},
            onClose = {},
        )
    }
}
