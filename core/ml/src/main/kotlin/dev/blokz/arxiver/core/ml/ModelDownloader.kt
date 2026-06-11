package dev.blokz.arxiver.core.ml

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface ModelState {
    data object NotDownloaded : ModelState

    data class Downloading(val progressPercent: Int) : ModelState

    data class Ready(val file: File) : ModelState

    data class Failed(val error: AppError) : ModelState
}

/**
 * Downloads the embedding model on first use (ARCHITECTURE §3.4): pinned URL,
 * SHA-256 verified, atomic rename — a partial or tampered file can never
 * become the active model.
 */
class ModelDownloader(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val modelDir: File,
    private val spec: ModelSpec = ModelSpec.BGE_SMALL_EN_V15_Q8,
) {
    data class ModelSpec(
        val fileName: String,
        val url: String,
        val sha256: String,
        val dimensions: Int,
        val displayName: String,
    ) {
        companion object {
            val BGE_SMALL_EN_V15_Q8 =
                ModelSpec(
                    fileName = "bge-small-en-v1.5-q8.onnx",
                    url = "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx",
                    sha256 = "6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4",
                    dimensions = 384,
                    displayName = "BGE small en v1.5 (quantized)",
                )
        }
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ModelState> = _state

    val modelFile: File get() = File(modelDir, spec.fileName)

    private fun initialState(): ModelState =
        if (modelFile.exists()) ModelState.Ready(modelFile) else ModelState.NotDownloaded

    /** Idempotent: returns the existing file when present and verified earlier. */
    suspend fun ensureDownloaded(): AppResult<File> =
        withContext(dispatchers.io) {
            if (modelFile.exists()) {
                _state.value = ModelState.Ready(modelFile)
                return@withContext AppResult.Success(modelFile)
            }
            val tmp = File(modelDir, spec.fileName + ".part")
            runCatching {
                modelDir.mkdirs()
                _state.value = ModelState.Downloading(0)
                val request = Request.Builder().url(spec.url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext fail(AppError.Upstream(response.code))
                    }
                    val body = response.body ?: throw IOException("empty body")
                    val total = body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read: Int
                            var written = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    _state.value = ModelState.Downloading((written * 100 / total).toInt())
                                }
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != spec.sha256) {
                        tmp.delete()
                        return@withContext fail(AppError.Unexpected(IOException("model checksum mismatch")))
                    }
                    check(tmp.renameTo(modelFile)) { "rename failed" }
                }
                _state.value = ModelState.Ready(modelFile)
                AppResult.Success(modelFile)
            }.getOrElse { e ->
                tmp.delete()
                fail(if (e is IOException) AppError.Offline else AppError.Unexpected(e))
            }
        }

    fun delete() {
        modelFile.delete()
        _state.value = ModelState.NotDownloaded
    }

    private fun fail(error: AppError): AppResult<File> {
        _state.value = ModelState.Failed(error)
        return AppResult.Failure(error)
    }
}
