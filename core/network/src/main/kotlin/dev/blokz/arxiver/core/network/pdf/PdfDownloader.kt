package dev.blokz.arxiver.core.network.pdf

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Streams a PDF to local storage. Downloads happen at most one at a time
 * (callers go through the repository) and write via a temp file so partial
 * downloads never masquerade as complete ones.
 */
class PdfDownloader(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun download(
        url: String,
        destination: File,
    ): AppResult<File> =
        withContext(dispatchers.io) {
            if (destination.exists() && destination.length() > 0) {
                return@withContext AppResult.Success(destination)
            }
            val tmp = File(destination.parentFile, destination.name + ".part")
            runCatching {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Failure(AppError.Upstream(response.code))
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    destination.parentFile?.mkdirs()
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    check(tmp.renameTo(destination)) { "rename failed" }
                }
                AppResult.Success(destination)
            }.getOrElse { e ->
                tmp.delete()
                when (e) {
                    is IOException -> AppResult.Failure(AppError.Offline)
                    else -> AppResult.Failure(AppError.Unexpected(e))
                }
            }
        }
}
