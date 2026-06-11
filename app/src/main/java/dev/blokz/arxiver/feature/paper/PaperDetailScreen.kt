package dev.blokz.arxiver.feature.paper

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.Paper
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperDetailScreen(
    onBack: () -> Unit,
    onOpenPdf: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenConnections: (String) -> Unit,
    viewModel: PaperDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val entry by viewModel.entry.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val related by viewModel.related.collectAsState()
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
                        IconButton(onClick = viewModel::toggleSaved) {
                            if (entry != null) {
                                Icon(Icons.Filled.Bookmark, stringResource(R.string.cd_unsave_paper))
                            } else {
                                Icon(Icons.Filled.BookmarkBorder, stringResource(R.string.cd_save_paper))
                            }
                        }
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
                else ->
                    state.paper?.let { paper ->
                        PaperDetailContent(
                            paper = paper,
                            entry = entry,
                            notes = notes,
                            tags = tags,
                            related = related,
                            onOpenPdf = onOpenPdf,
                            onPaperClick = onPaperClick,
                            onOpenConnections = onOpenConnections,
                            onSetStatus = viewModel::setStatus,
                            onSetRating = viewModel::setRating,
                            onAddNote = viewModel::addNote,
                            onDeleteNote = viewModel::deleteNote,
                            onAddTag = viewModel::addTag,
                            onRemoveTag = viewModel::removeTag,
                        )
                    }
            }
        }
    }
}

@Composable
private fun PaperDetailContent(
    paper: Paper,
    entry: LibraryEntryEntity?,
    notes: List<NoteEntity>,
    tags: List<TagEntity>,
    related: List<RelatedPaper>,
    onOpenPdf: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenConnections: (String) -> Unit,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = paper.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = paper.authors.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paper.categories) { code -> AssistChip(onClick = {}, label = { Text(code) }) }
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
            FilledTonalButton(onClick = { onOpenConnections(paper.id.value) }) {
                Icon(Icons.Filled.Hub, contentDescription = null)
                Text(
                    text = stringResource(R.string.paper_view_connections),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (entry != null) {
            LibrarySection(entry, onSetStatus, onSetRating)
        }

        HorizontalDivider()

        Text(stringResource(R.string.paper_abstract_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = paper.abstract, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        if (entry != null) {
            TagsSection(tags, onAddTag, onRemoveTag)
            NotesSection(notes, onAddNote, onDeleteNote)
            HorizontalDivider()
        }

        if (related.isNotEmpty()) {
            RelatedSection(related, onPaperClick)
            HorizontalDivider()
        }

        MetadataSection(paper)
    }
}

@Composable
private fun LibrarySection(
    entry: LibraryEntryEntity,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LibraryEntryEntity.STATUS_TO_READ to stringResource(R.string.library_filter_to_read),
                LibraryEntryEntity.STATUS_READING to stringResource(R.string.library_filter_reading),
                LibraryEntryEntity.STATUS_READ to stringResource(R.string.library_filter_read),
            ).forEach { (value, label) ->
                FilterChip(
                    selected = entry.status == value,
                    onClick = { onSetStatus(value) },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { star ->
                val starDescription = pluralStringResource(R.plurals.cd_rate_star, star, star)
                IconButton(
                    onClick = { onSetRating(if (entry.rating == star) null else star) },
                ) {
                    val filled = (entry.rating ?: 0) >= star
                    Icon(
                        imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = starDescription,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.paper_tags_heading), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tags, key = { it.id }) { tag ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text("#${tag.name}") },
                    trailingIcon = {
                        IconButton(onClick = { onRemoveTag(tag.id) }) {
                            Icon(
                                Icons.Filled.Close,
                                stringResource(R.string.cd_remove_tag, tag.name),
                                modifier = Modifier.padding(0.dp),
                            )
                        }
                    },
                )
            }
            item {
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(stringResource(R.string.paper_add_tag)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        }
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.paper_tag_hint)) },
                )
                TextButton(
                    onClick = {
                        onAddTag(newTag)
                        newTag = ""
                        adding = false
                    },
                    enabled = newTag.isNotBlank(),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}

@Composable
private fun NotesSection(
    notes: List<NoteEntity>,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Long) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.paper_notes_heading), style = MaterialTheme.typography.titleMedium)
        notes.forEach { note ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDeleteNote(note.id) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_delete_note))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.paper_note_hint)) },
            )
            TextButton(
                onClick = {
                    onAddNote(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        }
    }
}

@Composable
private fun RelatedSection(
    related: List<RelatedPaper>,
    onPaperClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.paper_related_heading), style = MaterialTheme.typography.titleMedium)
        related.forEach { item ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPaperClick(item.paper.id.value) }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.paper.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Text(
                        text = item.paper.authors.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${(item.similarity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
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
        paper.citationCount?.let { MetadataRow(stringResource(R.string.paper_meta_citations), it.toString()) }
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
