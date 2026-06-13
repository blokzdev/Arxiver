package dev.blokz.arxiver.feature.onboarding

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
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

        /**
         * Persists the onboarded flag and kicks the first sync, THEN hands
         * control back for navigation — navigating first would pop this
         * ViewModel and cancel the writes mid-flight.
         */
        fun finish(onComplete: () -> Unit) {
            viewModelScope.launch {
                settingsRepository.setOnboarded()
                syncScheduler.syncNow()
                onComplete()
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
    OnboardingContent(
        state = state,
        onToggle = viewModel::toggle,
        onStart = { viewModel.finish(onComplete = onDone) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onToggle: (ArxivCategory, Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Brand moment: the serif wordmark IS the welcome.
            Text(
                text = stringResource(R.string.app_name),
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.xxl),
            )
            Text(
                text = stringResource(R.string.onboarding_pitch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_pick_categories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.popular.forEach { (category, followed) ->
                    FilterChip(
                        selected = followed,
                        onClick = { onToggle(category, !followed) },
                        label = { Text(category.name) },
                        leadingIcon =
                            if (followed) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
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
        }
        AnimatedContent(targetState = state.followedCount, label = "follow-count") { count ->
            Text(
                text = pluralStringResource(R.plurals.browse_following_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }
        FilledTonalButton(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.followedCount > 0,
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingPreview() {
    ArxiverTheme {
        OnboardingContent(
            state =
                OnboardingUiState(
                    popular =
                        listOf(
                            ArxivCategory("cs.LG", "Machine Learning", "Computer Science") to true,
                            ArxivCategory("cs.CL", "Computation and Language", "Computer Science") to false,
                            ArxivCategory("quant-ph", "Quantum Physics", "Physics") to false,
                        ),
                    followedCount = 1,
                ),
            onToggle = { _, _ -> },
            onStart = {},
        )
    }
}
