package dev.blokz.arxiver.feature.claude

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.claude.RoutineTemplate
import dev.blokz.arxiver.core.claude.RoutineTemplateCatalog
import dev.blokz.arxiver.ui.theme.ArxiverTheme

/**
 * Browsable catalog of curated routine templates (SPEC-ROUTINES-CATALOG §3).
 * Templates are static bundled data, so the screen is stateless — no
 * ViewModel, no loading/empty states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateCatalogScreen(
    onBack: () -> Unit,
    onTemplateClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.template_catalog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.template_catalog_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(RoutineTemplateCatalog.templates, key = { it.id }) { template ->
                TemplateCard(template = template, onClick = { onTemplateClick(template.id) })
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: RoutineTemplate,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(template.name, style = MaterialTheme.typography.titleMedium)
            Text(
                template.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                SuggestionChip(
                    onClick = onClick,
                    label = { Text(stringResource(template.action.labelRes())) },
                )
                if (template.connectors.isNotEmpty()) {
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(template.connectors.joinToString()) },
                    )
                }
            }
        }
    }
}

/** UI labels for the action catalog — reuses the dispatch sheet's strings. */
internal fun RoutineAction.labelRes(): Int =
    when (this) {
        RoutineAction.DIGEST -> R.string.action_digest
        RoutineAction.DEEP_DIVE -> R.string.action_deep_dive
        RoutineAction.COMPARE -> R.string.action_compare
        RoutineAction.WEEKLY_REVIEW -> R.string.action_weekly_review
        RoutineAction.LITERATURE_SCAN -> R.string.action_literature_scan
        RoutineAction.CUSTOM, RoutineAction.PING -> R.string.action_custom
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TemplateCatalogPreview() {
    ArxiverTheme {
        TemplateCatalogScreen(onBack = {}, onTemplateClick = {})
    }
}
