package dev.blokz.arxiver.feature.paper

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.Citation
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.PdfStorage
import dev.blokz.arxiver.feature.claude.DispatchSheet
import dev.blokz.arxiver.feature.paper.ask.AskSheet
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdown
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdownLabels
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.ScoreBar
import dev.blokz.arxiver.ui.components.SkeletonLine
import dev.blokz.arxiver.ui.feedback.FeedbackAction
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.sharePdf
import dev.blokz.arxiver.ui.shareText
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
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
    onOpenRoutines: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: PaperDetailViewModel = hiltViewModel(),
) {
    var showDispatch by remember { mutableStateOf(false) }
    var showAsk by remember { mutableStateOf(false) }
    var showOrganize by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val feedback = LocalFeedbackController.current
    val clipboard = LocalClipboardManager.current
    val savedMessage = stringResource(R.string.today_snackbar_saved)
    val addToLabel = stringResource(R.string.action_add_to_collection)
    val pinnedToNotesMessage = stringResource(R.string.ask_pinned_to_notes)
    val referenceCopiedMessage = stringResource(R.string.paper_reference_copied)
    val bibtexCopiedMessage = stringResource(R.string.paper_bibtex_copied)
    val pdfNotDownloadedMessage = stringResource(R.string.paper_pdf_not_downloaded)
    val exportLabels =
        ConversationMarkdownLabels(
            you = stringResource(R.string.ask_export_you),
            assistant = stringResource(R.string.ask_export_assistant),
            sources = stringResource(R.string.ask_export_sources),
            footer = stringResource(R.string.ask_export_footer),
        )
    val state by viewModel.uiState.collectAsState()
    val entry by viewModel.entry.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val memberCollectionIds by viewModel.memberCollectionIds.collectAsState()
    val related by viewModel.related.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // The serif hero in the content is the identity moment; the bar title
    // fades in only once the hero scrolls away.
    val scrollState = rememberScrollState()
    val heroThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val showBarTitle by remember {
        derivedStateOf { scrollState.value > heroThresholdPx }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showBarTitle,
                        enter = fadeIn(tween(ArxiverMotion.DURATION_SHORT)),
                        exit = fadeOut(tween(ArxiverMotion.DURATION_SHORT)),
                    ) {
                        Text(
                            text = state.paper?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    state.paper?.let { paper ->
                        IconButton(onClick = { showAsk = true }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_ask))
                        }
                        IconButton(onClick = { showDispatch = true }) {
                            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_send_to_claude))
                        }
                        IconButton(
                            onClick = {
                                val wasSaved = entry != null
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSaved()
                                // On a fresh save, offer the second step: file it into a collection/tag.
                                if (!wasSaved) {
                                    feedback.show(
                                        FeedbackMessage(
                                            text = savedMessage,
                                            secondary = FeedbackAction(addToLabel) { showOrganize = true },
                                        ),
                                    )
                                }
                            },
                        ) {
                            Crossfade(targetState = entry != null, label = "bookmark") { saved ->
                                if (saved) {
                                    Icon(Icons.Filled.Bookmark, stringResource(R.string.cd_unsave_paper))
                                } else {
                                    Icon(Icons.Filled.BookmarkBorder, stringResource(R.string.cd_save_paper))
                                }
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
                        Box {
                            IconButton(onClick = { showActionsMenu = true }) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more_actions))
                            }
                            DropdownMenu(
                                expanded = showActionsMenu,
                                onDismissRequest = { showActionsMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share_pdf)) },
                                    leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        val pdf = PdfStorage.localPdf(context, paper.id.value)
                                        if (pdf != null) {
                                            context.sharePdf(pdf, subject = paper.title)
                                        } else {
                                            // Offline-safe fallback: share the arXiv PDF link, no silent download.
                                            context.shareText(paper.pdfUrl, subject = paper.title)
                                            feedback.show(FeedbackMessage(text = pdfNotDownloadedMessage))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_copy_reference)) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        clipboard.setText(AnnotatedString(Citation.reference(paper)))
                                        feedback.show(FeedbackMessage(text = referenceCopiedMessage))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_copy_bibtex)) },
                                    leadingIcon = { Icon(Icons.Filled.Code, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        clipboard.setText(AnnotatedString(Citation.bibtex(paper)))
                                        feedback.show(FeedbackMessage(text = bibtexCopiedMessage))
                                    },
                                )
                            }
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
                state.loading -> DetailSkeleton()
                state.notFound -> ErrorState(message = stringResource(R.string.paper_not_found))
                else ->
                    state.paper?.let { paper ->
                        PaperDetailContent(
                            paper = paper,
                            entry = entry,
                            notes = notes,
                            tags = tags,
                            collections = collections,
                            memberCollectionIds = memberCollectionIds,
                            related = related,
                            scrollState = scrollState,
                            onOpenPdf = onOpenPdf,
                            onPaperClick = onPaperClick,
                            onOpenConnections = onOpenConnections,
                            onSetStatus = viewModel::setStatus,
                            onSetRating = viewModel::setRating,
                            onAddNote = viewModel::addNote,
                            onDeleteNote = viewModel::deleteNote,
                            onAddTag = viewModel::addTag,
                            onRemoveTag = viewModel::removeTag,
                            onAddToCollection = viewModel::addToCollection,
                            onRemoveFromCollection = viewModel::removeFromCollection,
                            onCreateCollection = viewModel::createCollectionWithPaper,
                        )
                    }
            }
        }
    }

    if (showDispatch) {
        state.paper?.let { paper ->
            DispatchSheet(
                paperIds = listOf(paper.id.value),
                onDismiss = { showDispatch = false },
                onGoToRoutines = {
                    showDispatch = false
                    onOpenRoutines()
                },
            )
        }
    }

    if (showAsk) {
        state.paper?.let { paper ->
            AskSheet(
                scope = dev.blokz.arxiver.core.search.RetrievalScope.Paper(paper.id.value),
                onDismiss = { showAsk = false },
                onConfigureProvider = {
                    showAsk = false
                    onOpenAiSettings()
                },
                // Tapping an `arXiv:<id>` in an answer opens that paper in-app (validated here,
                // fetch-on-demand if not in the library) — P-Rich R3a.
                onOpenCrossRef = { rawId ->
                    dev.blokz.arxiver.core.model.ArxivId.parse(rawId)?.let { (id, _) ->
                        showAsk = false
                        onPaperClick(id.value)
                    }
                },
                // Pin the answer into this paper's notes, with a confirming snackbar — P-Rich R3a.
                onPinAnswer = { answer ->
                    viewModel.addNote(answer)
                    feedback.show(FeedbackMessage(text = pinnedToNotesMessage))
                },
                // Share an answer / the whole conversation as Markdown via the OS sheet — P-Rich R4.
                onShareAnswer = { m ->
                    context.shareText(ConversationMarkdown.answer(m, exportLabels), subject = paper.title)
                },
                onShareConversation = { msgs ->
                    context.shareText(
                        ConversationMarkdown.conversation(msgs, paper.title, exportLabels),
                        subject = paper.title,
                    )
                },
            )
        }
    }

    if (showOrganize) {
        state.paper?.let { paper ->
            dev.blokz.arxiver.feature.organize.OrganizeSheet(
                paperIds = listOf(paper.id.value),
                onDismiss = { showOrganize = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaperDetailContent(
    paper: Paper,
    entry: LibraryEntryEntity?,
    notes: List<NoteEntity>,
    tags: List<TagEntity>,
    collections: List<dev.blokz.arxiver.core.database.entity.CollectionEntity>,
    memberCollectionIds: Set<Long>,
    related: List<RelatedPaper>,
    scrollState: androidx.compose.foundation.ScrollState,
    onOpenPdf: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenConnections: (String) -> Unit,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
    onAddToCollection: (Long) -> Unit,
    onRemoveFromCollection: (Long) -> Unit,
    onCreateCollection: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(text = paper.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = paper.authors.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            paper.categories.forEach { code -> AssistChip(onClick = {}, label = { Text(code) }) }
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

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FilledTonalButton(onClick = { onOpenPdf(paper.id.value) }) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_open_pdf),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            FilledTonalButton(onClick = { onOpenConnections(paper.id.value) }) {
                Icon(Icons.Filled.Hub, contentDescription = null)
                Text(
                    text = stringResource(R.string.paper_view_connections),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }

        if (entry != null) {
            LibrarySection(entry, onSetStatus, onSetRating)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DetailHeading(stringResource(R.string.paper_abstract_heading))
        ExpandableAbstract(paper.abstract)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (entry != null) {
            CollectionsSection(
                collections = collections,
                memberIds = memberCollectionIds,
                onAdd = onAddToCollection,
                onRemove = onRemoveFromCollection,
                onCreate = onCreateCollection,
            )
            TagsSection(tags, onAddTag, onRemoveTag)
            NotesSection(notes, onAddNote, onDeleteNote)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (related.isNotEmpty()) {
            RelatedSection(related, onPaperClick)
        }

        MetadataSection(paper)
    }
}

/** Detail headings match the app-wide section-header style sans padding. */
@Composable
private fun DetailHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ExpandableAbstract(abstract: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.animateContentSize(tween(ArxiverMotion.DURATION_MEDIUM))) {
        Text(
            text = abstract,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_ABSTRACT_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.action_less else R.string.action_more))
        }
    }
}

private const val COLLAPSED_ABSTRACT_LINES = 10

@Composable
private fun LibrarySection(
    entry: LibraryEntryEntity,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSetRating(if (entry.rating == star) null else star)
                    },
                ) {
                    val filled = (entry.rating ?: 0) >= star
                    Crossfade(targetState = filled, label = "star-$star") { isFilled ->
                        Icon(
                            imageVector = if (isFilled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = starDescription,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_tags_heading))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            tags.forEach { tag ->
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
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.paper_add_tag)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
        if (adding) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CollectionsSection(
    collections: List<dev.blokz.arxiver.core.database.entity.CollectionEntity>,
    memberIds: Set<Long>,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_collections_heading))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            collections.forEach { collection ->
                val selected = collection.id in memberIds
                FilterChip(
                    selected = selected,
                    onClick = { if (selected) onRemove(collection.id) else onAdd(collection.id) },
                    label = { Text(collection.name) },
                    leadingIcon =
                        if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
            AssistChip(
                onClick = { creating = true },
                label = { Text(stringResource(R.string.paper_new_collection)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
        if (creating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.library_collection_name_hint)) },
                )
                TextButton(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                        creating = false
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.action_create)) }
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

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_notes_heading))
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_related_heading))
        related.forEach { item ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPaperClick(item.paper.id.value) }
                        .padding(vertical = Spacing.sm),
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
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = Spacing.sm),
                ) {
                    Text(
                        text = "${(item.similarity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ScoreBar(
                        score = item.similarity.toFloat(),
                        modifier =
                            Modifier
                                .padding(top = Spacing.xs)
                                .width(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(paper: Paper) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MetadataRow(stringResource(R.string.paper_meta_arxiv_id), "${paper.id.value}v${paper.latestVersion}")
            paper.comment?.let { MetadataRow(stringResource(R.string.paper_meta_comment), it) }
            paper.journalRef?.let { MetadataRow(stringResource(R.string.paper_meta_journal), it) }
            paper.doi?.let { MetadataRow(stringResource(R.string.paper_meta_doi), it) }
            paper.citationCount?.let { MetadataRow(stringResource(R.string.paper_meta_citations), it.toString()) }
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

/** Content-shaped placeholder for the detail load (SPEC-UI §4). */
@Composable
private fun DetailSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SkeletonLine(widthFraction = 0.95f)
        SkeletonLine(widthFraction = 0.8f)
        SkeletonLine(widthFraction = 0.5f)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonLine(widthFraction = 0.2f)
            SkeletonLine(widthFraction = 0.2f)
        }
        repeat(6) { SkeletonLine(widthFraction = if (it == 5) 0.6f else 1f) }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaperDetailContentPreview() {
    ArxiverTheme {
        PaperDetailContent(
            paper = PreviewFixtures.paper,
            entry =
                LibraryEntryEntity(
                    paperId = PreviewFixtures.paper.id.value,
                    addedAt = 0L,
                    status = LibraryEntryEntity.STATUS_READING,
                    rating = 4,
                ),
            notes = emptyList(),
            tags = emptyList(),
            collections = emptyList(),
            memberCollectionIds = emptySet(),
            related = emptyList(),
            scrollState = rememberScrollState(),
            onOpenPdf = {},
            onPaperClick = {},
            onOpenConnections = {},
            onSetStatus = {},
            onSetRating = {},
            onAddNote = {},
            onDeleteNote = {},
            onAddTag = {},
            onRemoveTag = {},
            onAddToCollection = {},
            onRemoveFromCollection = {},
            onCreateCollection = {},
        )
    }
}
