package dev.blokz.arxiver.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    ) : ViewModel() {
        private val pdfCacheMb = MutableStateFlow(0L)

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
                withContext(Dispatchers.IO) { modelDownloader.delete() }
                embeddingDao.deleteAll()
                embeddingDao.clearEmbeddedMarks()
            }
        }

        fun clearPdfCache() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "pdfs").deleteRecursively()
                }
                refreshPdfCacheSize()
            }
        }

        private fun refreshPdfCacheSize() {
            viewModelScope.launch {
                pdfCacheMb.value =
                    withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "pdfs")
                        (dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }) / (1024 * 1024)
                    }
            }
        }
    }
