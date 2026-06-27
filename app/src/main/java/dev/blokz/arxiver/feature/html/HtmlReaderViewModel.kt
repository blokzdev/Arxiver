package dev.blokz.arxiver.feature.html

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.ai.Fidelity
import dev.blokz.arxiver.core.ai.FidelityReport
import dev.blokz.arxiver.core.ai.HtmlFetchResult
import dev.blokz.arxiver.core.ai.HtmlFetcher
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.ai.ReaderDocument
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Resolves a paper's reader document for [HtmlReaderScreen] (Phase P-HTML PH.4): cache hit (HtmlStorage)
 * → else fetch (native→ar5iv→PDF/error) → persist (best-effort) → expose the [ReaderDocument]. The
 * screen turns the doc into HTML via `ReaderDocWriter.write(doc, theme)` in a `remember(doc, theme)`,
 * so theming/light-dark recompute for free and this ViewModel stays pure (no Compose/theme dependency).
 * The one-shot `FallbackToPdf` nav is a [Channel] effect (not state) so it fires exactly once.
 */
data class HtmlReaderUiState(
    val loading: Boolean = true,
    val doc: ReaderDocument? = null,
    val error: AppError? = null,
)

sealed interface HtmlReaderEffect {
    /** No usable HTML (auto), or the user chose "Open PDF instead" — navigate to the PDF viewer. */
    data class FallbackToPdf(val id: String) : HtmlReaderEffect
}

@HiltViewModel
class HtmlReaderViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val htmlFetcher: HtmlFetcher,
        private val htmlStorage: HtmlStorage,
        private val paperRepository: PaperRepository,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val paperId = ArxivId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(HtmlReaderUiState())
        val uiState: StateFlow<HtmlReaderUiState> = _uiState.asStateFlow()

        private val _effects = Channel<HtmlReaderEffect>(Channel.BUFFERED)
        val effects = _effects.receiveAsFlow()

        init {
            load()
        }

        fun retry() = load()

        fun openPdfInstead() {
            viewModelScope.launch { _effects.send(HtmlReaderEffect.FallbackToPdf(paperId.value)) }
        }

        private fun load() {
            _uiState.update { it.copy(loading = true, doc = null, error = null) }
            viewModelScope.launch {
                // latestVersion drives the cache key + the fetch URL; ?:1 is safe (the fetcher does a
                // versioned→bare-latest 404 retry, so an imperfect version self-heals).
                val version = paperRepository.paper(paperId)?.latestVersion ?: 1

                val cached =
                    withContext(dispatchers.io) {
                        (htmlStorage.localHtml(paperId, version) ?: htmlStorage.newest(paperId))?.let { c ->
                            reconstruct(c.file.readText(), c.source)
                        }
                    }
                if (cached != null) {
                    _uiState.update { it.copy(loading = false, doc = cached) }
                    return@launch
                }

                when (val result = htmlFetcher.fetch(paperId, version)) {
                    is HtmlFetchResult.Native -> ready(version, HtmlSource.NATIVE, result.doc)
                    is HtmlFetchResult.Ar5iv -> ready(version, HtmlSource.AR5IV, result.doc)
                    HtmlFetchResult.FallbackToPdf -> _effects.send(HtmlReaderEffect.FallbackToPdf(paperId.value))
                    is HtmlFetchResult.Error -> _uiState.update { it.copy(loading = false, error = result.error) }
                }
            }
        }

        private suspend fun ready(
            version: Int,
            source: HtmlSource,
            doc: ReaderDocument,
        ) {
            // Persist the sanitized+transformed body once (best-effort; a Storage failure still renders).
            htmlStorage.store(paperId, version, source, doc.bodyHtml)
            _uiState.update { it.copy(loading = false, doc = doc) }
        }

        /** A cache hit has the body + source on disk; fidelity is fetch-time-only and anchors are a PH.6 concern. */
        private fun reconstruct(
            bodyHtml: String,
            source: HtmlSource,
        ): ReaderDocument =
            ReaderDocument(
                bodyHtml = bodyHtml,
                fidelity = FidelityReport(Fidelity.OK, null, 0, 0, 0),
                anchors = emptyList(),
                source = source,
            )
    }
