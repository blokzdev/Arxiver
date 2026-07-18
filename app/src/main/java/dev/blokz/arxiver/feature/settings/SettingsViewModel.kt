package dev.blokz.arxiver.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.PdfStorage
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val syncIntervalHours: Int = SettingsRepository.DEFAULT_SYNC_HOURS,
    val modelState: ModelState = ModelState.NotDownloaded,
    val embeddedCount: Int = 0,
    val pdfCacheMb: Long = 0,
    val digestEnabled: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val modelDownloader: ModelDownloader,
        private val embeddingDao: EmbeddingDao,
        private val syncScheduler: SyncScheduler,
        private val backupManager: dev.blokz.arxiver.data.BackupManager,
        private val dispatchers: DispatcherProvider,
        rankerEvalState: dev.blokz.arxiver.sync.RankerEvalState,
    ) : ViewModel() {
        /** P5.1 debug diagnostic — the latest on-device ranker eval (null until a worker run publishes one). */
        val rankerHealth: kotlinx.coroutines.flow.StateFlow<dev.blokz.arxiver.sync.RankerHealth?> =
            rankerEvalState.latest

        private val pdfCacheMb = MutableStateFlow(0L)

        private val _backupJson = MutableStateFlow<String?>(null)
        val backupJson: StateFlow<String?> = _backupJson

        private val _importResult = MutableStateFlow<String?>(null)
        val importResult: StateFlow<String?> = _importResult

        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.syncIntervalHours,
                modelDownloader.state,
                embeddingDao.observeCount(),
                pdfCacheMb,
                settingsRepository.digestEnabled,
            ) { interval, model, embedded, pdfMb, digest ->
                SettingsUiState(
                    syncIntervalHours = interval,
                    modelState = model,
                    embeddedCount = embedded,
                    pdfCacheMb = pdfMb,
                    digestEnabled = digest,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        /**
         * Ambient-digest opt-in (PA.1b). Persist the flag; the runtime POST_NOTIFICATIONS request is the
         * screen's job (it needs an Activity). Turning OFF just clears the flag — no permission change.
         */
        fun setDigestEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDigestEnabled(enabled) }
        }

        /** "Emerging in your areas" opt-in (P-Discover2 PD.3b) — a plain toggle; the shelf posts nothing, so no
         *  permission is involved. Separate flow (the main uiState combine is already at kotlinx's typed 5-arg max). */
        val trendingEnabled: StateFlow<Boolean> =
            settingsRepository.trendingEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun setTrendingEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setTrendingEnabled(enabled) }
        }

        /** "Recommended for you" auto-refresh opt-in (P-RecShelf PRS.4) — default OFF; opting out wipes the cache. */
        val recShelfAutoRefreshEnabled: StateFlow<Boolean> =
            settingsRepository.recShelfAutoRefreshEnabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false,
            )

        fun setRecShelfAutoRefreshEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setRecShelfAutoRefreshEnabled(enabled) }
        }

        /**
         * The shared reader night-mode preference (P-Reader2 RNM.4) — Settings is the ONLY surface where
         * `SYSTEM` is reachable (the readers' toolbar toggle only flips Light↔Dark). Separate flow (the main
         * uiState combine is already at kotlinx's typed 5-arg max, same as [trendingEnabled]).
         */
        val readerThemeMode: StateFlow<dev.blokz.arxiver.data.ReaderThemeMode> =
            settingsRepository.readerThemeMode.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                dev.blokz.arxiver.data.ReaderThemeMode.SYSTEM,
            )

        fun setReaderTheme(mode: dev.blokz.arxiver.data.ReaderThemeMode) {
            viewModelScope.launch { settingsRepository.setReaderThemeMode(mode) }
        }

        init {
            refreshPdfCacheSize()
        }

        fun setSyncInterval(hours: Int) {
            viewModelScope.launch {
                settingsRepository.setSyncIntervalHours(hours)
                syncScheduler.reschedulePeriodicSync(hours.toLong())
            }
        }

        fun downloadModel() = syncScheduler.embedNow()

        fun reindex() {
            viewModelScope.launch {
                embeddingDao.deleteAll()
                embeddingDao.clearEmbeddedMarks()
                syncScheduler.embedNow()
            }
        }

        fun deleteModel() {
            viewModelScope.launch {
                withContext(dispatchers.io) { modelDownloader.delete() }
                embeddingDao.deleteAll()
                embeddingDao.clearEmbeddedMarks()
            }
        }

        fun exportBackup() {
            viewModelScope.launch { _backupJson.value = backupManager.export() }
        }

        fun consumeBackup() {
            _backupJson.value = null
        }

        fun importBackup(content: String) {
            viewModelScope.launch {
                _importResult.value =
                    runCatching {
                        val summary = backupManager.import(content)
                        syncScheduler.embedNow() // SPEC-DATA §6: embedding backfill after import
                        "ok:${summary.papers}:${summary.routinesNeedingTokens}"
                    }.getOrElse { "error:${it.message}" }
            }
        }

        fun consumeImportResult() {
            _importResult.value = null
        }

        fun clearPdfCache() {
            viewModelScope.launch {
                withContext(dispatchers.io) {
                    PdfStorage.dir(context).deleteRecursively()
                }
                refreshPdfCacheSize()
            }
        }

        private fun refreshPdfCacheSize() {
            viewModelScope.launch {
                pdfCacheMb.value =
                    withContext(dispatchers.io) {
                        val dir = PdfStorage.dir(context)
                        (dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }) / (1024 * 1024)
                    }
            }
        }
    }
