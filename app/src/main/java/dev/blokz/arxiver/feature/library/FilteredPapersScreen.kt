package dev.blokz.arxiver.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.LibraryPaper
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.ui.components.PaperListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FilteredPapersViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        libraryRepository: LibraryRepository,
    ) : ViewModel() {
        val title: String = savedStateHandle["title"] ?: ""
        private val mode: String = checkNotNull(savedStateHandle["mode"])
        private val id: Long = checkNotNull(savedStateHandle.get<String>("id")).toLong()

        val papers: StateFlow<List<LibraryPaper>> =
            when (mode) {
                "collection" -> libraryRepository.observeCollectionPapers(id)
                else -> libraryRepository.observeTagPapers(id)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredPapersScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    viewModel: FilteredPapersViewModel = hiltViewModel(),
) {
    val papers by viewModel.papers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
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
            if (papers.isEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.library_filtered_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(papers, key = { it.paper.id.value }) { row ->
                        PaperListItem(paper = row.paper, onClick = { onPaperClick(row.paper.id.value) })
                    }
                }
            }
        }
    }
}
