package dev.blokz.arxiver.feature.claude

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineStarterInstructions
import dev.blokz.arxiver.core.claude.RoutineTemplate
import dev.blokz.arxiver.core.claude.RoutineTemplateCatalog
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import kotlinx.coroutines.launch

/**
 * One template: what it does, what it needs, the paste-ready instructions
 * (SPEC-ROUTINES-CATALOG §3). Static data — stateless apart from the
 * collapsible instruction preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailScreen(
    templateId: String,
    onBack: () -> Unit,
) {
    val template = RoutineTemplateCatalog.byId(templateId)
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(template?.name ?: stringResource(R.string.template_catalog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (template == null) {
            Text(
                stringResource(R.string.template_not_found),
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(24.dp),
            )
        } else {
            TemplateDetailContent(
                template = template,
                snackbar = snackbar,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            )
        }
    }
}

@Composable
private fun TemplateDetailContent(
    template: RoutineTemplate,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInstructions by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(template.purpose, style = MaterialTheme.typography.bodyMedium)

        Text(
            stringResource(R.string.template_needs_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            template.triggerGuidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                if (template.connectors.isEmpty()) {
                    stringResource(R.string.template_connectors_none)
                } else {
                    stringResource(R.string.template_connectors_required, template.connectors.joinToString())
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (template.optionalConnectors.isNotEmpty()) {
            Text(
                stringResource(R.string.template_connectors_optional, template.optionalConnectors.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = {
                    copyInstructionsToClipboard(context, RoutineStarterInstructions.generateFor(template))
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.routine_instructions_copied))
                    }
                },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text(
                    stringResource(R.string.template_copy_instructions),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        TextButton(onClick = { showInstructions = !showInstructions }) {
            Text(
                stringResource(
                    if (showInstructions) R.string.template_hide_instructions else R.string.template_show_instructions,
                ),
            )
        }
        if (showInstructions) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    RoutineStarterInstructions.generateFor(template),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TemplateDetailPreview() {
    ArxiverTheme {
        TemplateDetailScreen(templateId = "paper_digest", onBack = {})
    }
}
