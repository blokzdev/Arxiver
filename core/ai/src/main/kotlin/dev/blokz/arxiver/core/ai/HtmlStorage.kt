package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import kotlinx.coroutines.withContext
import java.io.File

/** A cached reader body on disk + which source produced it (so the PH.4 banner survives a cache hit). */
data class CachedHtml(
    val file: File,
    val source: HtmlSource,
)

/**
 * Filesystem-only cache for sanitized+transformed reader bodies (Phase P-HTML PH.3, SPEC-P-HTML §10),
 * mirroring `PdfStorage` discipline. One directory per paper version (so PH.5 images can co-reside):
 * `filesDir/html/<id_>v<ver>/index.html`. A **`.complete` sentinel written last** (whose content is the
 * [HtmlSource]) means a process-killed partial is never treated as a cache hit under `blockNetworkLoads`.
 * No Room (deferred); rendered HTML is never added to the backup DTO. Takes `filesDir: File` (not a
 * Context) so it is JVM/Robolectric-testable.
 */
class HtmlStorage(
    private val filesDir: File,
    private val dispatchers: DispatcherProvider,
) {
    fun dir(): File = File(filesDir, "html")

    private fun versionDir(
        id: ArxivId,
        version: Int,
    ): File = File(dir(), "${id.value.replace('/', '_')}v$version")

    /** Persist the transformed body atomically; the `.complete` sentinel (content = source) is written last. */
    suspend fun store(
        id: ArxivId,
        version: Int,
        source: HtmlSource,
        bodyHtml: String,
    ): AppResult<File> =
        withContext(dispatchers.io) {
            runCatching {
                val vdir = versionDir(id, version).apply { mkdirs() }
                val index = File(vdir, "index.html")
                val tmp = File(vdir, "index.html.part")
                tmp.writeText(bodyHtml)
                check(tmp.renameTo(index)) { "rename failed" }
                File(vdir, COMPLETE).writeText(source.name)
                AppResult.Success(index)
            }.getOrElse { e -> AppResult.Failure(AppError.Storage(e.message)) }
        }

    /** The cached body for an exact version, or null when absent/incomplete (sentinel gate). */
    fun localHtml(
        id: ArxivId,
        version: Int,
    ): CachedHtml? = read(versionDir(id, version))

    /** The newest cached version of [id] by parsed version number, or null. */
    fun newest(id: ArxivId): CachedHtml? {
        val prefix = "${id.value.replace('/', '_')}v"
        val best =
            dir().listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
                ?.mapNotNull { f -> f.name.removePrefix(prefix).toIntOrNull()?.let { it to f } }
                ?.maxByOrNull { it.first }
                ?.second ?: return null
        return read(best)
    }

    private fun read(vdir: File): CachedHtml? {
        val index = File(vdir, "index.html")
        val complete = File(vdir, COMPLETE)
        if (!index.exists() || index.length() == 0L || !complete.exists()) return null
        val source = runCatching { HtmlSource.valueOf(complete.readText().trim()) }.getOrNull() ?: return null
        return CachedHtml(index, source)
    }

    private companion object {
        const val COMPLETE = ".complete"
    }
}
