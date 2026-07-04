package dev.blokz.arxiver.feature.html

import android.os.SystemClock
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
import dev.blokz.arxiver.core.ai.ReaderPosition
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Resolves a paper's reader document for [HtmlReaderScreen] (Phase P-HTML PH.4/PH.5/PH.6): cache hit
 * (HtmlStorage, re-gated for external hosts) → else fetch (native→ar5iv→PDF/error). On a fresh fetch the
 * figures arrive in **two phases** (PH.5): expose the figcaption-placeholder body immediately (text +
 * math paint without waiting on rate-limited images), then fetch the images (serial, deadline-bounded),
 * inline them as `data:` URIs, re-persist, and re-expose. The screen turns the doc into HTML via
 * `ReaderDocWriter.write(doc, theme)` in a `remember(doc, theme)`, so theming recomputes for free and
 * this ViewModel stays Compose-free.
 *
 * PH.6 — this VM owns ALL scroll POLICY (the WebView layer is a dumb, generation-stamped sink):
 * - **Single target slot** [restoreTarget]: seeded from the `.position` sidecar, overwritten by explicit
 *   jumps (TOC/cite taps) and — after the jump-settle window — by scroll-idle probes. **Restore never
 *   writes the slot** (it only reads it at load-completion time), so a saved position can never race
 *   ahead of a jump; every reload path (phase-2 figure swap, rotation, theme toggle, process death)
 *   funnels through the same read-current-target-at-onPageFinished restore.
 * - **Persistence**: a debounced collector writes the slot to the sidecar keyed on the version
 *   ACTUALLY SERVED (`newest()` can serve an older dir than latestVersion); `onCleared` flushes once
 *   more on the injected application scope so back-nav / PDF-fallback exits keep the last position.
 */
data class HtmlReaderUiState(
    val loading: Boolean = true,
    val doc: ReaderDocument? = null,
    val error: AppError? = null,
    /** For the reader-hosted AskSheet (conversation/share title); null while loading (PH.7). */
    val paperTitle: String? = null,
)

sealed interface HtmlReaderEffect {
    /** No usable HTML (auto), or the user chose "Open PDF instead" — navigate to the PDF viewer. */
    data class FallbackToPdf(val id: String) : HtmlReaderEffect

    /** A TOC row was tapped — scroll the reader (smooth) + announce for TalkBack. */
    data class JumpToAnchor(val anchorId: String, val label: String) : HtmlReaderEffect
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
        private val libraryRepository: dev.blokz.arxiver.data.LibraryRepository,
        private val dispatchers: DispatcherProvider,
        private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        internal val paperId = ArxivId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(HtmlReaderUiState())
        val uiState: StateFlow<HtmlReaderUiState> = _uiState.asStateFlow()

        private val _effects = Channel<HtmlReaderEffect>(Channel.BUFFERED)
        val effects = _effects.receiveAsFlow()

        /** Injectable clock seam (Hilt can't provide a default-lambda ctor param); tests override. */
        internal var now: () -> Long = { SystemClock.elapsedRealtime() }

        /** The version dir the sidecar is keyed on — the version actually served, not latestVersion. */
        private var servedVersion: Int = 1

        private val target = MutableStateFlow<ReaderPosition?>(null)
        private var jumpSetAt = 0L

        init {
            load()
            persistLoop()
        }

        fun retry() = load()

        /** Pin an Ask answer into this paper's notes (PH.7; PaperDetailViewModel's exact layering). */
        fun pinNote(content: String) {
            viewModelScope.launch { libraryRepository.addNote(paperId.value, content) }
        }

        fun openPdfInstead() {
            viewModelScope.launch { _effects.send(HtmlReaderEffect.FallbackToPdf(paperId.value)) }
        }

        /** Read by the restore funnel at load-completion time; never written by it. */
        fun restoreTarget(): ReaderPosition? = target.value

        /** An explicit jump (TOC or in-page cite tap) claims the slot + starts the settle window. */
        fun onJump(anchorId: String) {
            jumpSetAt = now()
            target.value = ReaderPosition(anchorId, 0, 0f)
        }

        fun onTocSelect(
            anchorId: String,
            label: String,
        ) {
            onJump(anchorId)
            viewModelScope.launch { _effects.send(HtmlReaderEffect.JumpToAnchor(anchorId, label)) }
        }

        /** A reload re-applied a standing jump — the settle clock must not be stale (reset it). */
        fun onRestoreApplied() {
            if (jumpSetAt != 0L) jumpSetAt = now()
        }

        /**
         * A scroll-idle probe result. The payload round-trips through the untrusted page: the anchor
         * id is only kept if it names a real anchor of the CURRENT doc; values are clamped. Probes
         * never overwrite a fresh explicit jump (settle-window precedence) — after settle, the first
         * probe demotes, which post-jump is the same anchor at ~0 offset anyway.
         */
        fun onPositionProbed(pos: ReaderPosition?) {
            pos ?: return
            if (jumpSetAt != 0L && now() - jumpSetAt < JUMP_SETTLE_MS) return
            val anchors = _uiState.value.doc?.anchors ?: return
            val validAnchor = pos.anchorId?.takeIf { id -> anchors.any { it.id == id } }
            target.value =
                ReaderPosition(
                    anchorId = validAnchor,
                    offsetCssPx = pos.offsetCssPx.coerceAtLeast(0),
                    fraction = pos.fraction.coerceIn(0f, 1f),
                )
        }

        private fun persistLoop() {
            viewModelScope.launch {
                // collectLatest = debounce: a newer position cancels the pending write.
                target.collectLatest { pos ->
                    if (pos != null) {
                        delay(POSITION_SAVE_DEBOUNCE_MS)
                        htmlStorage.storePosition(paperId, servedVersion, pos)
                    }
                }
            }
        }

        override fun onCleared() {
            // Honest final flush — back-nav / PDF-fallback exit within the debounce window still
            // persists. Application scope + NonCancellable: viewModelScope is already cancelling.
            val pos = target.value ?: return
            val version = servedVersion
            applicationScope.launch(NonCancellable) { htmlStorage.storePosition(paperId, version, pos) }
        }

        private fun load() {
            _uiState.update { it.copy(loading = true, doc = null, error = null) }
            viewModelScope.launch {
                // latestVersion drives the cache key + the fetch URL; ?:1 is safe (the fetcher does a
                // versioned→bare-latest 404 retry, so an imperfect version self-heals). The same row
                // fetch also carries the title for the reader-hosted AskSheet (PH.7 — no extra hit).
                val paper = paperRepository.paper(paperId)
                val version = paper?.latestVersion ?: 1
                _uiState.update { it.copy(paperTitle = paper?.title) }

                val cached =
                    withContext(dispatchers.io) {
                        (htmlStorage.localHtml(paperId, version) ?: htmlStorage.newest(paperId))?.let { c ->
                            val body = c.file.readText()
                            // Re-gate the persisted body (cheap regex): a poisoned cache never renders.
                            if (HtmlReaderTransform.assertNoExternalHost(body)) {
                                servedVersion = c.version
                                target.value = htmlStorage.readPosition(paperId, c.version)
                                reconstruct(body, c.source)
                            } else {
                                null
                            }
                        }
                    }
                if (cached != null) {
                    _uiState.update { it.copy(loading = false, doc = cached) }
                    return@launch
                }

                servedVersion = version
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

        /**
         * A cache hit has the body + source on disk; fidelity is fetch-time-only. Anchors are
         * re-derived from the persisted body (PH.6) so a cached open gets a real TOC — runs inside
         * the caller's `dispatchers.io` block (multi-MB `data:`-laden bodies never parse on main).
         */
        private fun reconstruct(
            bodyHtml: String,
            source: HtmlSource,
        ): ReaderDocument =
            ReaderDocument(
                bodyHtml = bodyHtml,
                fidelity = FidelityReport(Fidelity.OK, null, 0, 0, 0),
                anchors = HtmlReaderTransform.extractAnchors(bodyHtml),
                source = source,
            )

        internal companion object {
            /** Wall-clock cap on phase-2 figure fetching so a survey paper can't hold the shared ≥3s slot
             * for minutes; on the ≥3s limiter this admits ~8–10 figures, the rest stay placeholders (PH.6
             * refresh). PROVISIONAL — ratify against a real figure-heavy paper (HUMAN.md / VERIFICATION §M). */
            const val IMAGE_FETCH_DEADLINE_MS = 30_000L

            /** An explicit jump holds the target slot this long against probe demotion. PROVISIONAL —
             * device-ratify against the real smooth-scroll duration (VERIFICATION §M). */
            const val JUMP_SETTLE_MS = 2_500L

            /** Below this scroll fraction (with no anchor) a restore is a no-op — top-of-page noise. */
            const val MIN_RESTORE_FRACTION = 0.02f

            /** Scroll-idle probes are persisted after this quiet window. PROVISIONAL. */
            const val POSITION_SAVE_DEBOUNCE_MS = 1_000L
        }
    }
