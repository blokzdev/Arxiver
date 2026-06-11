package dev.blokz.arxiver.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val popular: List<Pair<ArxivCategory, Boolean>> = emptyList(),
    val followedCount: Int = 0,
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val categoryRepository: CategoryRepository,
        private val settingsRepository: SettingsRepository,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        val uiState: StateFlow<OnboardingUiState> =
            categoryRepository.observeGroupedCategories()
                .map { grouped ->
                    val all = grouped.values.flatten()
                    val byCode = all.associateBy { it.category.code }
                    OnboardingUiState(
                        popular =
                            POPULAR_CODES.mapNotNull { code ->
                                byCode[code]?.let { it.category to it.followed }
                            },
                        followedCount = all.count { it.followed },
                    )
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

        fun toggle(
            category: ArxivCategory,
            followed: Boolean,
        ) {
            viewModelScope.launch { categoryRepository.setFollowed(category, followed) }
        }

        fun finish() {
            viewModelScope.launch {
                settingsRepository.setOnboarded()
                syncScheduler.syncNow()
            }
        }

        companion object {
            /** A cross-discipline starter set; the full directory lives in Browse. */
            private val POPULAR_CODES =
                listOf(
                    "cs.LG", "cs.AI", "cs.CL", "cs.CV", "cs.CR", "cs.RO", "cs.SE",
                    "stat.ML", "math.OC", "math.PR", "quant-ph", "hep-th",
                    "astro-ph.CO", "cond-mat.str-el", "q-bio.NC", "econ.EM", "eess.SP",
                )
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_pitch),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.onboarding_pick_categories),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.popular.forEach { (category, followed) ->
                FilterChip(
                    selected = followed,
                    onClick = { viewModel.toggle(category, !followed) },
                    label = { Text(category.name) },
                )
            }
        }
        Text(
            text = stringResource(R.string.onboarding_more_in_browse),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.onboarding_claude_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = {
                viewModel.finish()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.followedCount > 0,
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}
