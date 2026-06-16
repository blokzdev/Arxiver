package dev.blokz.arxiver.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R

/**
 * Contextual action bar shown while a paper list is in multi-select mode (SPEC-UI §3). Distinct
 * tonal surface, an "N selected" title, a leading clear (✕) that exits selection, and screen-supplied
 * trailing bulk [actions] (Organize / Save / Send to Claude / …). Reused across every list screen so
 * selection behaves identically everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.selection_count, count, count)) },
        actions = actions,
    )
}
