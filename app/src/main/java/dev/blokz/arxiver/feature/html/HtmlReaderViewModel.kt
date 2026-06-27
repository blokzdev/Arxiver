package dev.blokz.arxiver.feature.html

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.ai.Fidelity
import dev.blokz.arxiver.core.ai.FidelityReport
import dev.blokz.arxiver.core.ai.HtmlFetchResult
import dev.blokz.arxiver.core.ai.HtmlFetcher
import dev.blokz.arxiver.core.ai.HtmlImageFetcher
import dev.blokz.arxiver.core.ai.HtmlImageInliner
import dev.blokz.arxiver.core.ai.HtmlReaderTransform
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.ai.InlinedImage
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Resolves a paper's reader document for [HtmlReaderScreen] (Phase P-HTML PH.4/PH.5): cache hit
 * (HtmlStorage, re-gated for external hosts) → else fetch (native→ar5iv→PDF/error). On a fresh fetch the
 * figures arrive in **two phases** (PH.5): expose the figcaption-placeholder body immediately (text +
 * math paint without waiting on rate-limited images), then fetch the images (serial, deadline-bounded),
 * inline them as `data:` URIs, re-persist, and re-expose. The screen turns the doc into HTML via
 * `ReaderDocWriter.write(doc, theme)` in a `remember(doc, theme)`, so theming recomputes for free and
 * this ViewModel stays Compose-free. The one-shot `FallbackToPdf` nav is a [Channel] effect (not state).
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
        private val htmlImageFetcher: HtmlImageFetcher,
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
                            val body = c.file.readText()
                            // Re-gate the persisted body (cheap regex): a poisoned cache never renders.
                            if (HtmlReaderTransform.assertNoExternalHost(body)) reconstruct(body, c.source) else null
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
            // Phase 1 — figures as placeholders: a self-contained body that is the safe offline baseline.
            // Persist + expose immediately so text + math paint without waiting on the (rate-limited) images.
            val placeholderBody = HtmlImageInliner.inline(doc.bodyHtml, emptyMap())
            htmlStorage.store(paperId, version, source, placeholderBody)
            _uiState.update {
                it.copy(loading = false, doc = doc.copy(bodyHtml = placeholderBody, images = emptyList()))
            }
            if (doc.images.isEmpty()) return

            // Phase 2 — fetch the figures (serial, rate-limited), deadline-bounded so a survey paper can't
            // hold the shared slot for minutes; partials survive the deadline via the per-image callback.
            val resolved = LinkedHashMap<String, InlinedImage>()
            withTimeoutOrNull(IMAGE_FETCH_DEADLINE_MS) {
                htmlImageFetcher.fetchAll(doc.images) { key, image -> resolved[key] = image }
            }
            if (resolved.isEmpty()) return // offline / all failed → keep the placeholder body + cache

            val imageBody = HtmlImageInliner.inline(doc.bodyHtml, resolved)
            htmlStorage.store(paperId, version, source, imageBody)
            _uiState.update { it.copy(doc = doc.copy(bodyHtml = imageBody, images = emptyList())) }
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

        private companion object {
            /** Wall-clock cap on phase-2 figure fetching so a survey paper can't hold the shared ≥3s slot
             * for minutes; on the ≥3s limiter this admits ~8–10 figures, the rest stay placeholders (PH.6
             * refresh). PROVISIONAL — ratify against a real figure-heavy paper (HUMAN.md / VERIFICATION §M). */
            const val IMAGE_FETCH_DEADLINE_MS = 30_000L
        }
    }
