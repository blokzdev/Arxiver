package dev.blokz.arxiver.feature.paper

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperDetailScreen(
    onBack: () -> Unit,
    onOpenPdf: (String) -> Unit,
    viewModel: PaperDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    state.paper?.let { paper ->
                        IconButton(
                            onClick = {
                                val send =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${paper.title}\n${paper.id.absUrl()}")
                                    }
                                context.startActivity(
                                    Intent.createChooser(send, context.getString(R.string.action_share)),
                                )
                            },
                        ) {
                            Icon(Icons.Filled.Share, stringResource(R.string.action_share))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.notFound ->
                    Text(
                        text = stringResource(R.string.paper_not_found),
                        modifier = Modifier.align(Alignment.Center),
                    )
                else -> state.paper?.let { PaperDetailContent(it, onOpenPdf) }
            }
        }
    }
}

@Composable
private fun PaperDetailContent(
    paper: Paper,
    onOpenPdf: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = paper.title,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = paper.authors.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paper.categories) { code ->
                AssistChip(onClick = {}, label = { Text(code) })
            }
        }
        Text(
            text =
                stringResource(
                    R.string.paper_dates,
                    dateFormat.format(paper.publishedAt.atZone(ZoneId.systemDefault())),
                    paper.latestVersion,
                    dateFormat.format(paper.updatedAt.atZone(ZoneId.systemDefault())),
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onOpenPdf(paper.id.value) }) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_open_pdf),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.paper_abstract_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = paper.abstract,
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        MetadataSection(paper)
    }
}

@Composable
private fun MetadataSection(paper: Paper) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetadataRow(stringResource(R.string.paper_meta_arxiv_id), "${paper.id.value}v${paper.latestVersion}")
        paper.comment?.let { MetadataRow(stringResource(R.string.paper_meta_comment), it) }
        paper.journalRef?.let { MetadataRow(stringResource(R.string.paper_meta_journal), it) }
        paper.doi?.let { MetadataRow(stringResource(R.string.paper_meta_doi), it) }
        paper.citationCount?.let {
            MetadataRow(stringResource(R.string.paper_meta_citations), it.toString())
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PaperDetailContentPreview() {
    ArxiverTheme {
        PaperDetailContent(paper = PreviewFixtures.paper, onOpenPdf = {})
    }
}
