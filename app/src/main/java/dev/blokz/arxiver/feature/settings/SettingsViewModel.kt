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
    ) : ViewModel() {
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
            ) { interval, model, embedded, pdfMb ->
                SettingsUiState(
                    syncIntervalHours = interval,
                    modelState = model,
                    embeddedCount = embedded,
                    pdfCacheMb = pdfMb,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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
