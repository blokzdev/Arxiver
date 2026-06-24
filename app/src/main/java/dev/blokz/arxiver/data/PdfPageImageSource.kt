package dev.blokz.arxiver.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.graphics.createBitmap
import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Produces a vision-ready image of a paper's PDF page (P-Rich R3d). Both calls are **network-free**
 * — they only ever read an **already-downloaded** PDF from `filesDir/pdfs/` (so the vision path never
 * triggers an arXiv fetch / hits the rate limiter). The picker + preset gating depend on this.
 */
interface PageImageSource {
    /** Page count if the paper's PDF is already local, else null (drives gating + the page picker). */
    suspend fun pageCountIfLocal(paperId: String): Int?

    /** Renders 0-based [pageIndex] to a downscaled JPEG [ChatImage], or null if not local / out of range. */
    suspend fun pageImage(
        paperId: String,
        pageIndex: Int,
    ): ChatImage?
}

/** Vision-image cap: ≤ this on the long edge (both Claude & Gemini downscale beyond ~1568px). */
internal const val MAX_LONG_EDGE = 1568
private const val JPEG_QUALITY = 80

/** Pure long-edge downscale math (no Android) — unit-tested; never upscales. */
internal fun cappedSize(
    width: Int,
    height: Int,
    maxLongEdge: Int = MAX_LONG_EDGE,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge || longEdge == 0) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    val scale = maxLongEdge.toFloat() / longEdge
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/**
 * Framework-`PdfRenderer` implementation. A **fresh** renderer + file descriptor per call (the
 * viewer's holder is private + Compose-scoped — not shared), all on the IO dispatcher, closed
 * promptly. The local PDF is found by globbing `filesDir/pdfs/<id>v*.pdf` (no DB/network read).
 */
class PdfPageImageSource(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : PageImageSource {
    /** Newest local PDF for [paperId] (filename `<id with / → _>v<version>.pdf`), or null. */
    private fun localPdf(paperId: String): File? {
        val prefix = paperId.replace('/', '_') + "v"
        return File(context.filesDir, "pdfs")
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".pdf") && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    override suspend fun pageCountIfLocal(paperId: String): Int? =
        withContext(dispatchers.io) {
            val file = localPdf(paperId) ?: return@withContext null
            withRenderer(file) { it.pageCount }
        }

    override suspend fun pageImage(
        paperId: String,
        pageIndex: Int,
    ): ChatImage? =
        withContext(dispatchers.io) {
            val file = localPdf(paperId) ?: return@withContext null
            withRenderer(file) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@withRenderer null
                renderer.openPage(pageIndex).use { page ->
                    val (w, h) = cappedSize(page.width, page.height)
                    val bitmap = createBitmap(w, h)
                    // JPEG has no alpha — a transparent PDF page would encode as black behind figures.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val base64 = bitmap.toJpegBase64()
                    bitmap.recycle()
                    ChatImage(
                        mediaType = "image/jpeg",
                        base64 = base64,
                        label = "page ${pageIndex + 1} of arXiv:$paperId",
                    )
                }
            }
        }

    /** Open a fresh [PdfRenderer] on [file], run [block], always close the renderer + fd. */
    private inline fun <T> withRenderer(
        file: File,
        block: (PdfRenderer) -> T,
    ): T? {
        val fd =
            runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
                ?: return null
        val renderer =
            runCatching { PdfRenderer(fd) }.getOrNull() ?: run {
                fd.close()
                return null
            }
        return try {
            block(renderer)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    private fun Bitmap.toJpegBase64(): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
