package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A cached reader body on disk + which source produced it (so the PH.4 banner survives a cache hit)
 * + the version the dir actually serves (PH.6 keys the reading-position sidecar on the SERVED
 * version — `newest()` can serve an older dir than the paper's latestVersion).
 */
data class CachedHtml(
    val file: File,
    val source: HtmlSource,
    val version: Int,
)

/**
 * A completed cached reader body (`.complete` present) + the model that has body-indexed it, or null when
 * never indexed / stale (P-FullText PFT.2). The `.bodyindex` sidecar carries the model, so a `MODEL_NAME`
 * bump (which wipes the body chunks) leaves a stale model here → the filesystem-driven backfill re-indexes.
 */
data class CachedBodyRef(
    val id: ArxivId,
    val version: Int,
    val indexedModel: String?,
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
                // REPLACE_EXISTING so the PH.5 two-phase re-store (text-first body → image body) overwrites
                // the prior index.html — File.renameTo does NOT overwrite on Windows (the JVM-test host).
                Files.move(tmp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
                File(vdir, COMPLETE).writeText(source.name)
                AppResult.Success(index)
            }.getOrElse { e -> AppResult.Failure(AppError.Storage(e.message)) }
        }

    /** The cached body for an exact version, or null when absent/incomplete (sentinel gate). */
    fun localHtml(
        id: ArxivId,
        version: Int,
    ): CachedHtml? = read(versionDir(id, version), version)

    /** The newest cached version of [id] by parsed version number, or null. */
    fun newest(id: ArxivId): CachedHtml? {
        val prefix = "${id.value.replace('/', '_')}v"
        val best =
            dir().listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
                ?.mapNotNull { f -> f.name.removePrefix(prefix).toIntOrNull()?.let { it to f } }
                ?.maxByOrNull { it.first }
                ?: return null
        // Pass the version parsed above — never re-parse dir names (legacy ids contain 'v').
        return read(best.second, best.first)
    }

    /**
     * Persist the reading position for the version dir actually served (PH.6). Atomic tmp→move like
     * [store]; best-effort (a failed write just loses the resume point). The sidecar is invisible to
     * the [read] gates (`index.html` + `.complete` only) and survives the PH.5 two-phase re-store.
     */
    suspend fun storePosition(
        id: ArxivId,
        version: Int,
        position: ReaderPosition,
    ): Unit =
        withContext(dispatchers.io) {
            runCatching {
                val vdir = versionDir(id, version).apply { mkdirs() }
                val tmp = File(vdir, "$POSITION.part")
                tmp.writeText("1|${position.anchorId.orEmpty()}|${position.offsetCssPx}|${position.fraction}")
                Files.move(tmp.toPath(), File(vdir, POSITION).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }

    /** The persisted position for an exact version, or null when absent/corrupt (open at top). */
    fun readPosition(
        id: ArxivId,
        version: Int,
    ): ReaderPosition? =
        runCatching {
            val parts = File(versionDir(id, version), POSITION).readText().split("|")
            if (parts.size != 4 || parts[0] != "1") return@runCatching null
            ReaderPosition(
                anchorId = parts[1].ifEmpty { null },
                offsetCssPx = parts[2].toInt().coerceAtLeast(0),
                fraction = parts[3].toFloat().coerceIn(0f, 1f),
            )
        }.getOrNull()

    /**
     * Stamp the `.bodyindex` sidecar (content = the [model] that indexed this version's body) — P-FullText
     * PFT.2. Atomic tmp→move like [store]; best-effort. Written only AFTER a successful body index, so a
     * failed/absent index leaves no sidecar and is retried. Invisible to the [read] gates.
     */
    suspend fun storeBodyIndex(
        id: ArxivId,
        version: Int,
        model: String,
    ): Unit =
        withContext(dispatchers.io) {
            runCatching {
                val vdir = versionDir(id, version).apply { mkdirs() }
                val tmp = File(vdir, "$BODY_INDEX.part")
                tmp.writeText(model)
                Files.move(tmp.toPath(), File(vdir, BODY_INDEX).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }

    /** The model that body-indexed this exact version, or null (never indexed / unreadable). PFT.2. */
    fun readBodyIndex(
        id: ArxivId,
        version: Int,
    ): String? =
        runCatching {
            File(versionDir(id, version), BODY_INDEX).readText().trim().ifEmpty { null }
        }.getOrNull()

    /**
     * Every completed cached body with its current body-index model (P-FullText PFT.2 backfill). Enumerates
     * the html cache dirs — NEVER the paper table — so a paper with no cached HTML is simply not a candidate
     * (no newest-N starvation), and a `MODEL_NAME` bump self-heals (the wiped body chunks leave a stale
     * sidecar model here → re-indexed). The dir name is `<id with '/'→'_'>v<ver>`; arXiv ids never contain a
     * literal 'v', so the last 'v' is the version separator and '_'→'/' recovers the id (the reader is
     * arXiv-only — a non-arXiv id never reaches this cache).
     */
    fun cachedBodies(): List<CachedBodyRef> =
        dir().listFiles { f -> f.isDirectory }
            ?.mapNotNull { vdir ->
                if (!File(vdir, "index.html").exists() || !File(vdir, COMPLETE).exists()) return@mapNotNull null
                val name = vdir.name
                val vAt = name.lastIndexOf('v')
                if (vAt <= 0) return@mapNotNull null
                val version = name.substring(vAt + 1).toIntOrNull() ?: return@mapNotNull null
                val id = ArxivId(name.substring(0, vAt).replace('_', '/'))
                CachedBodyRef(id, version, readBodyIndex(id, version))
            }.orEmpty()

    /**
     * True iff **any** cached HTML version of [id] has been body-indexed with [model] (Phase P-Reader2 PFT.5.5
     * arbitration). The PDF body path re-checks this INSIDE the shared per-storageId lock before it indexes, so
     * HTML (the preferred, cleaner source) is never clobbered by a PDF fallback — even if the HTML index landed
     * between the PDF trigger firing and the PDF path acquiring the lock. "Any version" so a stale-HTML-v1 paper
     * is not re-indexed from its newer PDF-v2 (the accepted version-skew cost: PDF never clobbers HTML).
     */
    fun hasCurrentModelBodyIndex(
        id: ArxivId,
        model: String,
    ): Boolean {
        val prefix = "${id.value.replace('/', '_')}v"
        return dir().listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
            ?.any { vdir ->
                val version = vdir.name.removePrefix(prefix).toIntOrNull() ?: return@any false
                readBodyIndex(id, version) == model
            } ?: false
    }

    private fun read(
        vdir: File,
        version: Int,
    ): CachedHtml? {
        val index = File(vdir, "index.html")
        val complete = File(vdir, COMPLETE)
        if (!index.exists() || index.length() == 0L || !complete.exists()) return null
        val source = runCatching { HtmlSource.valueOf(complete.readText().trim()) }.getOrNull() ?: return null
        return CachedHtml(index, source, version)
    }

    private companion object {
        const val COMPLETE = ".complete"
        const val POSITION = ".position"
        const val BODY_INDEX = ".bodyindex"
    }
}
